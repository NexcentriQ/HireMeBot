package org.hmb.service;

import org.hmb.dto.RecruiterAccountLookupResponse;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.ExistingAccount;
import org.hmb.model.RecruiterNextStep;
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
 * {@code GET /api/v1/recruiters/auth/me}: the single "Continue with Google" entry point of the recruiter portal for new
 * and returning recruiters. It only reads; it never inserts or updates anything and never changes an account status
 * (a recruiter is created only by {@code POST /api/v1/recruiters/register} and approved only by the operator's manual
 * {@code UPDATE}).
 *
 * <ol>
 *   <li>Look the caller up by the <b>verified token's</b> Cognito subject ({@code user_identities.cognito_sub}). If
 *       found, {@link RecruiterAccountRules#signIn} decides: {@code RECRUITER_DASHBOARD} (ACTIVE),
 *       {@code APPROVAL_PENDING} (PENDING), or a 403 restriction (deleted, student/manager identity, suspended). No
 *       Cognito call is needed: the subject was verified as a Google identity when it was registered.</li>
 *   <li>Not found: resolve the identity with the same {@link CognitoIdentityService} as registration (UserInfo
 *       verified email + AdminGetUser Google federation). Then, on one connection, re-check the subject (a concurrent
 *       registration in another tab may have just committed) and check the verified email: an account with that email
 *       under a different identity is a conflict, never linked. Otherwise {@code RECRUITER_SIGNUP_FORM}.</li>
 * </ol>
 * No database connection is held while Cognito is called.
 */
public class RecruiterAccountLookupService {

    private static final Logger LOG = LoggerFactory.getLogger(RecruiterAccountLookupService.class);

    private final DataSource dataSource;
    private final CognitoIdentityService identityService;
    private final UserIdentityRepository identities;
    private final UserRepository users;

    public RecruiterAccountLookupService(DataSource dataSource, CognitoIdentityService identityService,
                                         UserIdentityRepository identities, UserRepository users) {
        this.dataSource = dataSource;
        this.identityService = identityService;
        this.identities = identities;
        this.users = users;
    }

    /**
     * @throws RegistrationException 403 {@code ACCOUNT_DELETED} / {@code ACCOUNT_TYPE_MISMATCH} /
     *                               {@code ACCOUNT_SUSPENDED} / {@code GOOGLE_ACCOUNT_REQUIRED} /
     *                               {@code EMAIL_NOT_VERIFIED}, 409 {@code ACCOUNT_IDENTITY_CONFLICT}, 401, 503/500
     */
    public RecruiterAccountLookupResponse lookup(VerifiedAccessToken token, BearerToken bearer) {
        Optional<AccountProfile> profile;
        try (Connection connection = dataSource.getConnection()) {
            profile = identities.findProfileByCognitoSub(connection, token.subject());
        } catch (SQLException e) {
            throw DatabaseFailures.map(e);
        }
        if (profile.isPresent()) {
            return existing(profile.get());
        }

        // Unknown subject: is this a verified Google user who may register as a recruiter?
        AuthenticatedIdentity identity;
        try {
            identity = identityService.resolve(token, bearer);
        } catch (RegistrationException e) {
            throw RecruiterAccountRules.recruiterWording(e);
        }
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
            RegistrationException refusal = RecruiterAccountRules.refuseEmailOwner(byEmail.get());
            LOG.info("Recruiter lookup: verified email belongs to user {} under another identity -> {}",
                    byEmail.get().userId(), refusal.code());
            throw refusal;
        }
        LOG.info("Recruiter lookup: no HireMeBot account for this Google identity -> RECRUITER_SIGNUP_FORM");
        return RecruiterAccountLookupResponse.signupRequired();
    }

    private static RecruiterAccountLookupResponse existing(AccountProfile profile) {
        RecruiterNextStep nextStep;
        try {
            nextStep = RecruiterAccountRules.signIn(profile.existing(), profile.provider());
        } catch (RegistrationException restricted) {
            LOG.info("Recruiter lookup: user {} -> {}", profile.userId(), restricted.code());
            throw restricted;
        }
        LOG.info("Recruiter lookup: user {} -> {}", profile.userId(), nextStep);
        return switch (nextStep) {
            case RECRUITER_DASHBOARD -> RecruiterAccountLookupResponse.dashboard(profile);
            case APPROVAL_PENDING -> RecruiterAccountLookupResponse.pending(profile);
            case RECRUITER_SIGNUP_FORM -> throw new IllegalStateException("signIn never returns the signup step");
        };
    }
}
