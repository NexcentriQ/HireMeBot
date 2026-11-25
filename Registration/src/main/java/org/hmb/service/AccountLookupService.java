package org.hmb.service;

import org.hmb.dto.AccountLookupResponse;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.ExistingAccount;
import org.hmb.model.NextStep;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.VerifiedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * {@code GET /api/v1/auth/me}: the single "Continue with Google" entry point for new and returning students. It only
 * reads; it never inserts or updates anything (an account is created only by {@code POST /api/v1/students/register}).
 *
 * <ol>
 *   <li>Look the caller up by the <b>verified token's</b> Cognito subject ({@code user_identities.cognito_sub}). If
 *       found, {@link AccountRules#signIn} decides: {@code DASHBOARD}, {@code ACCOUNT_PENDING}, or a 403
 *       restriction. No Cognito call is needed: the subject was verified as a Google identity when it was
 *       registered, and a Cognito subject never changes.</li>
 *   <li>Not found: resolve the identity with the same {@link CognitoIdentityService} as registration (UserInfo
 *       verified email + AdminGetUser Google federation). Then, on one connection, re-check the subject (a concurrent
 *       registration in another tab may have just committed) and check the verified email: an account with that email
 *       under a different identity is a conflict, never linked. Otherwise {@code SIGNUP_FORM}.</li>
 * </ol>
 * No database connection is held while Cognito is called.
 */
public class AccountLookupService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountLookupService.class);

    private final DataSource dataSource;
    private final CognitoIdentityService identityService;
    private final UserIdentityRepository identities;
    private final UserRepository users;

    public AccountLookupService(DataSource dataSource, CognitoIdentityService identityService,
                                UserIdentityRepository identities, UserRepository users) {
        this.dataSource = dataSource;
        this.identityService = identityService;
        this.identities = identities;
        this.users = users;
    }

    /**
     * @throws RegistrationException 403 {@code ACCOUNT_DELETED} / {@code ACCOUNT_SUSPENDED} /
     *                               {@code ACCOUNT_TYPE_MISMATCH} / {@code GOOGLE_ACCOUNT_REQUIRED} /
     *                               {@code EMAIL_NOT_VERIFIED}, 409 {@code ACCOUNT_IDENTITY_CONFLICT}, 401, 503/500
     */
    public AccountLookupResponse lookup(VerifiedAccessToken token, BearerToken bearer) {
        Optional<AccountProfile> profile;
        try (Connection connection = dataSource.getConnection()) {
            profile = identities.findProfileByCognitoSub(connection, token.subject());
        } catch (SQLException e) {
            throw DatabaseFailures.map(e);
        }
        if (profile.isPresent()) {
            return existing(profile.get());
        }

        // Unknown subject: is this a verified Google user who may sign up?
        AuthenticatedIdentity identity = identityService.resolve(token, bearer);
        Optional<ExistingAccount> byEmail;
        try (Connection connection = dataSource.getConnection()) {
            profile = identities.findProfileByCognitoSub(connection, token.subject());
            if (profile.isPresent()) {
                return existing(profile.get());
            }
            byEmail = users.findByEmail(connection, identity.email());
        } catch (SQLException e) {
            throw DatabaseFailures.map(e);
        }
        if (byEmail.isPresent()) {
            RegistrationException refusal = AccountRules.refuseEmailOwner(byEmail.get());
            LOG.info("Account lookup: verified email belongs to user {} under another identity -> {}",
                    byEmail.get().userId(), refusal.code());
            throw refusal;
        }
        LOG.info("Account lookup: no HireMeBot account for this Google identity -> SIGNUP_FORM");
        return AccountLookupResponse.signupRequired();
    }

    private static AccountLookupResponse existing(AccountProfile profile) {
        NextStep nextStep;
        try {
            nextStep = AccountRules.signIn(profile.existing(), profile.provider());
        } catch (RegistrationException restricted) {
            LOG.info("Account lookup: user {} -> {}", profile.userId(), restricted.code());
            throw restricted;
        }
        LOG.info("Account lookup: user {} -> {}", profile.userId(), nextStep);
        return AccountLookupResponse.registered(nextStep, profile);
    }
}
