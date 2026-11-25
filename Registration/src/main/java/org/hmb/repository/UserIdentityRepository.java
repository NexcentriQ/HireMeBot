package org.hmb.repository;

import org.hmb.model.AccountProfile;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * {@code user_identities} reads and the registration insert. Stateless; the caller owns the connection.
 *
 * <p>Both reads are driven by {@code uq_cognito_sub} (at most one row), join {@code users} on its primary key, and
 * LEFT JOIN {@code students}, {@code recruiters} and {@code managers} on their primary keys, so an identity of any
 * role is found and reported with its role flags (a recruiter is "not a student" and a student is "not a recruiter")
 * instead of being mistaken for an unregistered user.
 */
public class UserIdentityRepository {

    private final AccountProfileRepository profiles = new AccountProfileRepository();

    /** Registration pre-check: the account that already owns this Cognito subject. */
    static final String FIND_BY_COGNITO_SUB_SQL = """
            SELECT u.user_id, u.account_status, u.is_deleted, s.student_id, r.recruiter_id, m.manager_id
            FROM user_identities ui
            JOIN users u ON u.user_id = ui.user_id
            LEFT JOIN students s ON s.student_id = u.user_id
            LEFT JOIN recruiters r ON r.recruiter_id = u.user_id
            LEFT JOIN managers m ON m.manager_id = u.user_id
            WHERE ui.cognito_sub = ?
            """;

    static final String INSERT_SQL = """
            INSERT INTO user_identities (
                user_id,
                provider,
                cognito_sub
            )
            VALUES (?, ?, ?)
            """;

    public Optional<ExistingAccount> findByCognitoSub(Connection connection, String cognitoSub) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_COGNITO_SUB_SQL)) {
            statement.setString(1, cognitoSub);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(UserRepository.existingAccount(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Sign-in lookup: read-only, the profile returned to the account owner. The query lives in common's
     * {@link AccountProfileRepository}, shared with the recruiter APIs' authenticator.
     */
    public Optional<AccountProfile> findProfileByCognitoSub(Connection connection, String cognitoSub)
            throws SQLException {
        return profiles.findByCognitoSub(connection, cognitoSub);
    }

    public void insert(Connection connection, long userId, IdentityProvider provider, String cognitoSub)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setLong(1, userId);
            statement.setString(2, provider.name());
            statement.setString(3, cognitoSub);
            UserRepository.expectOneRow(statement.executeUpdate(), "user_identities");
        }
    }
}
