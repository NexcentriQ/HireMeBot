package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.ExistingAccount;
import org.hmb.model.NewStudentUser;
import org.hmb.repository.UniqueKeyCatalog;
import org.hmb.repository.UniqueKeyCatalog.UniqueKey;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Function;

/**
 * The registration transaction shared by every role (student, recruiter): <b>one MySQL transaction</b> on one
 * connection with auto-commit off that refuses an already-registered Cognito subject or email, then inserts
 * {@code users} (generated id), the <em>role row</em> ({@code students} or {@code recruiters}) and
 * {@code user_identities} with that same id, then commits. Any failure rolls the whole transaction back, so no partial
 * {@code users}, orphaned role row or orphaned {@code user_identities} row can remain.
 *
 * <p>What differs per role is passed in and stays outside this class: the role-row insert ({@link RoleInsert}), the
 * refusal rules ({@link Refusals}, i.e. the role's decision table and wording) and the {@code users} row itself
 * (status, terms; {@link NewStudentUser} is the row for every role despite its name).
 *
 * <p><b>Concurrency.</b> The pre-check is a plain read and cannot stop two concurrent requests on its own; the
 * {@code UNIQUE(email)} and {@code UNIQUE(cognito_sub)} constraints do. The loser of a race gets a duplicate-key
 * error (or a deadlock), its transaction is rolled back, and the pre-check runs again in a fresh transaction that sees
 * the winner's committed rows, giving the same deterministic 409 as a sequential duplicate. A duplicate that is neither
 * of those is identified by the columns of the violated index ({@link UniqueKeyCatalog}), e.g. the owner's
 * {@code UNIQUE (first_name, last_name)} &rarr; 409 {@code NAME_ALREADY_REGISTERED}.
 *
 * <p>The Cognito user exists before this runs and is never deleted or changed here, even if the transaction fails.
 */
final class AccountRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(AccountRegistrar.class);

    /** Inserts the role row ({@code students} / {@code recruiters}) for the generated user id. */
    @FunctionalInterface
    interface RoleInsert {
        void insert(Connection connection, long userId) throws SQLException;
    }

    /**
     * The role's refusals for what is already stored: for an account owning the same Cognito subject, and for an
     * account owning the same email under a different identity (never linked).
     */
    record Refusals(Function<ExistingAccount, RegistrationException> bySubject,
                    Function<ExistingAccount, RegistrationException> byEmail) {
    }

    private final DataSource dataSource;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final UniqueKeyCatalog uniqueKeys;

    AccountRegistrar(DataSource dataSource, UserRepository users, UserIdentityRepository identities,
                     UniqueKeyCatalog uniqueKeys) {
        this.dataSource = dataSource;
        this.users = users;
        this.identities = identities;
        this.uniqueKeys = uniqueKeys;
    }

    /**
     * @return the database-generated {@code user_id}, which is also the role row's id
     * @throws RegistrationException the role's refusal, {@code NAME_ALREADY_REGISTERED}, 503 for connectivity or an
     *                               unexplained lock conflict, 500 otherwise
     */
    long register(AuthenticatedIdentity identity, NewStudentUser user, RoleInsert roleRow, Refusals refusals) {
        try (Connection connection = dataSource.getConnection()) {
            return insertInTransaction(connection, identity, user, roleRow, refusals);
        } catch (SQLException e) {
            throw DatabaseFailures.map(e);
        }
    }

    private long insertInTransaction(Connection connection, AuthenticatedIdentity identity, NewStudentUser user,
                                     RoleInsert roleRow, Refusals refusals) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            refuseIfRegistered(connection, identity, refusals);
            long userId = users.insert(connection, user);
            roleRow.insert(connection, userId);
            identities.insert(connection, userId, identity.provider(), identity.cognitoSubject());
            connection.commit();
            return userId;
        } catch (SQLException e) {
            rollback(connection, e);
            if (DatabaseFailures.isDuplicateKey(e) || DatabaseFailures.isLockConflict(e)) {
                LOG.info("Registration collided with a concurrent write ({}); transaction rolled back, re-checking",
                        DatabaseFailures.isDuplicateKey(e) ? "duplicate key " + DatabaseFailures.duplicateKeyName(e)
                                : "lock conflict " + e.getErrorCode());
                throw explainConflict(connection, identity, refusals, e);
            }
            throw e;
        } catch (RuntimeException | Error e) {
            rollback(connection, e);
            throw e;
        } finally {
            restoreAutoCommit(connection, previousAutoCommit);
        }
    }

    /**
     * Throws the role's refusal for an identity or email that is already stored: same Cognito subject (the role's
     * {@link Refusals#bySubject}), then same email under another identity ({@link Refusals#byEmail}).
     */
    private void refuseIfRegistered(Connection connection, AuthenticatedIdentity identity, Refusals refusals)
            throws SQLException {
        Optional<ExistingAccount> bySubject = identities.findByCognitoSub(connection, identity.cognitoSubject());
        if (bySubject.isPresent()) {
            throw logged(refusals.bySubject().apply(bySubject.get()), bySubject.get(), "cognito_sub");
        }
        Optional<ExistingAccount> byEmail = users.findByEmail(connection, identity.email());
        if (byEmail.isPresent()) {
            throw logged(refusals.byEmail().apply(byEmail.get()), byEmail.get(), "email");
        }
    }

    private static RegistrationException logged(RegistrationException refusal, ExistingAccount account,
                                                String matchedBy) {
        LOG.info("Registration refused ({}): existing user {} matched by {}", refusal.code(), account.userId(),
                matchedBy);
        return refusal;
    }

    /**
     * After a rolled-back duplicate-key or lock conflict. First the pre-check runs again in a new transaction, which
     * now sees a concurrent winner's committed rows (same subject or email &rarr; the usual refusal). If that explains
     * nothing, the violated unique index is identified by its columns ({@link UniqueKeyCatalog}): the
     * {@code (first_name, last_name)} index &rarr; 409 {@code NAME_ALREADY_REGISTERED}; email or subject that is no
     * longer there, or a lock conflict &rarr; retryable 503; any other index &rarr; 500. Always returns an exception.
     */
    private RegistrationException explainConflict(Connection connection, AuthenticatedIdentity identity,
                                                  Refusals refusals, SQLException cause) {
        try {
            refuseIfRegistered(connection, identity, refusals);
            if (!DatabaseFailures.isDuplicateKey(cause)) {
                LOG.warn("Registration hit a lock conflict (code {}); the client may retry", cause.getErrorCode());
                return RegistrationException.serviceUnavailable(cause);
            }
            UniqueKey key = uniqueKeys.classify(connection, cause);
            return switch (key) {
                case FULL_NAME -> {
                    LOG.info("Registration refused (NAME_ALREADY_REGISTERED): unique index {} on first/last name",
                            DatabaseFailures.duplicateKeyName(cause));
                    yield RegistrationException.nameAlreadyRegistered();
                }
                case EMAIL, COGNITO_SUBJECT -> {
                    // The conflicting row vanished before the re-check (deleted meanwhile): let the client retry.
                    LOG.warn("Duplicate {} could not be confirmed on re-check; the client may retry", key);
                    yield RegistrationException.serviceUnavailable(cause);
                }
                case USER_PROVIDER, UNKNOWN -> {
                    LOG.error("Duplicate key on an unexpected unique index: {} ({})",
                            DatabaseFailures.duplicateKeyName(cause), key);
                    yield RegistrationException.internal(cause);
                }
            };
        } catch (RegistrationException refusal) {
            return refusal;
        } catch (SQLException e) {
            e.addSuppressed(cause);
            return DatabaseFailures.map(e);
        } finally {
            rollback(connection, null);
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            if (original != null) {
                original.addSuppressed(e);
            }
            LOG.error("Rollback failed (SQLState={})", e.getSQLState());
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            LOG.warn("Could not restore auto-commit (SQLState={})", e.getSQLState());
        }
    }
}
