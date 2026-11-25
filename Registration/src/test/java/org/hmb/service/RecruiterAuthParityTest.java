package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.exception.ApiException;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;
import org.hmb.repository.AccountProfileRepository;
import org.hmb.security.CognitoAuthConfig;
import org.hmb.security.RecruiterAuthenticator;
import org.hmb.security.TokenVerifier;
import org.hmb.security.VerifiedAccessToken;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keeps the recruiter APIs' resolver ({@link RecruiterAuthenticator} in {@code common}) and the sign-in endpoints'
 * decision table ({@link RecruiterAccountRules}) saying the same thing about every account: an account reaches the
 * recruiter dashboard on {@code GET /api/v1/recruiters/auth/me} if and only if the recruiter APIs accept it, and every
 * refusal has the same reason. Also pins the Cognito issuer and JWKS URL built by the two configs.
 */
class RecruiterAuthParityTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final long USER_ID = 1_000_000_004L;

    /** The authenticator's refusal message for each reason the sign-in rules report. */
    private static String messageFor(String reason) {
        return switch (reason) {
            case "ACCOUNT_DELETED" -> "This account is unavailable";
            case "ACCOUNT_TYPE_MISMATCH" -> "This account is not a recruiter account";
            case "ACCOUNT_SUSPENDED" -> "Your recruiter account has been suspended";
            case "GOOGLE_ACCOUNT_REQUIRED" -> "A Google sign-in is required";
            case "APPROVAL_PENDING" -> "Your recruiter account is waiting for approval";
            default -> throw new IllegalArgumentException(reason);
        };
    }

    @Test
    void everyAccountStateIsDecidedTheSameWayByBothImplementations() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        AccountProfileRepository profiles = mock(AccountProfileRepository.class);
        doReturn(connection).when(dataSource).getConnection();
        TokenVerifier verifier = token -> new VerifiedAccessToken(SUB, "google_1", "client", Set.of("openid"),
                Instant.now().plusSeconds(600));
        RecruiterAuthenticator authenticator = new RecruiterAuthenticator(verifier, dataSource, profiles, false);

        int cases = 0;
        int allowed = 0;
        for (boolean deleted : new boolean[] {false, true}) {
            for (String role : new String[] {"recruiter", "student", "manager", "none"}) {
                for (AccountStatus status : AccountStatus.values()) {
                    for (IdentityProvider provider : IdentityProvider.values()) {
                        AccountProfile profile = new AccountProfile(USER_ID, "Rita", "Recruiter", "rita@gmail.com",
                                "+17135550101", true, false, status, deleted, role.equals("student"),
                                role.equals("recruiter"), role.equals("manager"), provider);
                        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.of(profile));
                        String state = "deleted=" + deleted + " role=" + role + " status=" + status + " provider=" + provider;

                        // what the sign-in endpoint decides ...
                        String expectedReason;
                        boolean expectedAllowed;
                        try {
                            RecruiterNextStep step = RecruiterAccountRules.signIn(profile.existing(), provider);
                            expectedAllowed = step == RecruiterNextStep.RECRUITER_DASHBOARD;
                            expectedReason = expectedAllowed ? null : step.name();
                        } catch (RegistrationException refused) {
                            expectedAllowed = false;
                            expectedReason = refused.code();
                        }
                        // ... and what the recruiter APIs decide
                        boolean actualAllowed;
                        String actualMessage = null;
                        try {
                            actualAllowed = authenticator.authenticate(Map.of("Authorization", "Bearer abc.def.ghi"), Map.of())
                                    .recruiterId() == USER_ID;
                        } catch (ApiException refused) {
                            actualAllowed = false;
                            actualMessage = refused.getMessage();
                            assertEquals(403, refused.getStatusCode(), state);
                        }

                        assertEquals(expectedAllowed, actualAllowed, state);
                        if (!expectedAllowed) {
                            assertEquals(messageFor(expectedReason), actualMessage, state);
                        } else {
                            allowed++;
                        }
                        cases++;
                    }
                }
            }
        }
        assertEquals(48, cases);
        assertEquals(1, allowed, "only an undeleted ACTIVE Google recruiter gets in");
    }

    @Test
    void bothConfigsBuildTheSameIssuerAndJwksUrl() {
        Map<String, String> env = Map.of("COGNITO_REGION", "us-east-2", "COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY",
                "COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9",
                "COGNITO_DOMAIN", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "TERMS_VERSION", "2026-11-01");

        CognitoAuthConfig shared = CognitoAuthConfig.fromEnv(env);
        RegistrationConfig registration = RegistrationConfig.fromEnv(env);

        assertEquals(registration.issuer(), shared.issuer());
        assertEquals(registration.jwksUri(), shared.jwksUri());
        assertEquals(registration.clientId(), shared.clientId());
    }
}
