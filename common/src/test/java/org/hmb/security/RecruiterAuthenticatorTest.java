package org.hmb.security;

import org.hmb.exception.ApiException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.AccountProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The recruiter resolver with REAL token verification (locally signed JWTs) and a mocked database. The SQL itself is
 * covered by the real-MySQL tests in RecruiterLambda and Registration.
 */
class RecruiterAuthenticatorTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final long RECRUITER_ID = 1_000_000_004L;

    private final TestJwt jwt = new TestJwt();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final AccountProfileRepository profiles = mock(AccountProfileRepository.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.of(
                profile(AccountStatus.ACTIVE, false, false, true, false, IdentityProvider.GOOGLE)));
    }

    private RecruiterAuthenticator authenticator(boolean devHeaderPermitted) {
        return new RecruiterAuthenticator(jwt.verifier(Clock.systemUTC()), dataSource, profiles, devHeaderPermitted);
    }

    private static AccountProfile profile(AccountStatus status, boolean deleted, boolean student, boolean recruiter,
                                          boolean manager, IdentityProvider provider) {
        return new AccountProfile(RECRUITER_ID, "Rita", "Recruiter", "rita@gmail.com", "+17135550101", true, false,
                status, deleted, student, recruiter, manager, provider);
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.put(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    private Map<String, String> bearer(String token) {
        return headers("authorization", "Bearer " + token);
    }

    // ---- a valid token identifies the recruiter ------------------------------------------------------------------

    @Test
    void anApprovedRecruitersTokenResolvesToTheirInternalId() throws SQLException {
        RecruiterAuthenticator.Principal principal =
                authenticator(false).authenticate(bearer(jwt.accessToken(SUB)), Map.of());

        assertEquals(RECRUITER_ID, principal.recruiterId());
        verify(profiles).findByCognitoSub(connection, SUB); // looked up by the VERIFIED token's subject only
    }

    @Test
    void theAuthorizationHeaderNameAndBearerSchemeAreCaseInsensitive() {
        String token = jwt.accessToken(SUB);
        assertEquals(RECRUITER_ID, authenticator(false)
                .authenticate(headers("AUTHORIZATION", "bearer " + token), null).recruiterId());
        assertEquals(RECRUITER_ID, authenticator(false)
                .authenticate(headers("Authorization", "  BEARER   " + token + "  "), null).recruiterId());
    }

    @Test
    void anythingElseTheClientSendsCannotChangeTheActingRecruiter() {
        Map<String, String> forged = bearer(jwt.accessToken(SUB));
        forged.put("X-Recruiter-Id", "1000000099");
        forged.put("X-User-Id", "1000000098");
        forged.put("X-Dev-Recruiter-Id", "1000000097");
        forged.put("X-Email", "someone-else@gmail.com");

        assertEquals(RECRUITER_ID, authenticator(true).authenticate(forged, Map.of()).recruiterId());
    }

    // ---- a Bearer token always wins, and a bad one is never retried as the dev header ----------------------------

    @Test
    void aValidTokenBeatsTheDevelopmentHeader() {
        Map<String, String> both = bearer(jwt.accessToken(SUB));
        both.put("X-Dev-Recruiter-Id", "1000000001");

        assertEquals(RECRUITER_ID, authenticator(true).authenticate(both, Map.of()).recruiterId());
    }

    @Test
    void everyBadTokenIs401EvenWithAPermittedAndValidDevelopmentHeaderAndTheDatabaseIsNeverAsked() {
        Instant past = Instant.now().minusSeconds(7200);
        TestJwt otherPool = new TestJwt();
        String valid = jwt.accessToken(SUB);
        String[] parts = valid.split("\\.");
        String[] other = jwt.accessToken("11111111-2222-3333-4444-555555555555").split("\\.");
        List<String> bad = List.of(
                "not-a-jwt",
                jwt.sign(jwt.accessClaims(SUB).issueTime(Date.from(past.minusSeconds(3600)))
                        .expirationTime(Date.from(past)).build()),                                        // expired
                jwt.sign(jwt.accessClaims(SUB).issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Other1")
                        .build()),                                                                         // wrong issuer
                otherPool.accessToken(SUB),                                                                // forged signature
                jwt.sign(jwt.accessClaims(SUB).claim("client_id", "wrongclient00000000000000").build()),  // wrong client
                jwt.sign(jwt.idClaims(SUB, "rita@gmail.com").build()),                                     // ID token
                jwt.sign(jwt.accessClaims(SUB).claim("scope", "email").build()),                           // no openid
                TestJwt.unsigned(jwt.accessClaims(SUB).build()),                                           // alg none
                jwt.hmacWithPublicKey(jwt.accessClaims(SUB).build()),                                      // HS256 forgery
                parts[0] + "." + other[1] + "." + parts[2]);                                               // tampered
        RecruiterAuthenticator permissive = authenticator(true);
        for (String token : bad) {
            Map<String, String> request = bearer(token);
            request.put("X-Dev-Recruiter-Id", String.valueOf(RECRUITER_ID)); // valid, and would be accepted alone
            ApiException e = assertThrows(ApiException.class, () -> permissive.authenticate(request, Map.of()),
                    token.length() > 30 ? token.substring(0, 30) : token);
            assertEquals(401, e.getStatusCode());
            assertEquals("UNAUTHORIZED", e.getErrorCode());
        }
        verifyNoInteractions(dataSource, profiles);
    }

    @Test
    void malformedAuthorizationHeadersAre401AndNeverFallBackToTheDevelopmentHeader() {
        RecruiterAuthenticator permissive = authenticator(true);
        for (String value : new String[] {"", "   ", "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "Token abc",
                "Bearer a b", "Bearer abc!def", "x".repeat(9000)}) {
            Map<String, String> request = headers("authorization", value, "x-dev-recruiter-id", "1000000004");
            ApiException e = assertThrows(ApiException.class, () -> permissive.authenticate(request, Map.of()), value);
            assertEquals(401, e.getStatusCode(), value);
        }
        verifyNoInteractions(dataSource, profiles);
    }

    @Test
    void whenTheKeysCannotBeFetchedItIsA503NotAFallback() {
        TokenVerifier unavailable = token -> {
            throw ApiException.serviceUnavailable(new RuntimeException("jwks"));
        };
        RecruiterAuthenticator authenticator = new RecruiterAuthenticator(unavailable, dataSource, profiles, true);
        Map<String, String> request = headers("authorization", "Bearer abc.def.ghi", "x-dev-recruiter-id", "1000000004");

        assertEquals(503, assertThrows(ApiException.class, () -> authenticator.authenticate(request, Map.of()))
                .getStatusCode());
        verifyNoInteractions(profiles);
    }

    @Test
    void aTokenWithNoVerifierConfiguredIsRefusedNotTreatedAsHeaderAuth() {
        RecruiterAuthenticator headerOnly = RecruiterAuthenticator.devHeaderOnly(true);
        Map<String, String> request = headers("authorization", "Bearer abc.def.ghi", "x-dev-recruiter-id", "1000000004");

        assertEquals(401, assertThrows(ApiException.class, () -> headerOnly.authenticate(request, Map.of()))
                .getStatusCode());
    }

    // ---- API Gateway's authorizer claims must describe the same user -----------------------------------------------

    @Test
    void authorizerClaimsForAnotherSubjectAre401AndMatchingOrAbsentClaimsAreFine() {
        String token = jwt.accessToken(SUB);
        assertEquals(401, assertThrows(ApiException.class, () -> authenticator(false)
                .authenticate(bearer(token), Map.of("sub", "forged-subject"))).getStatusCode());
        verifyNoInteractions(profiles);

        assertEquals(RECRUITER_ID, authenticator(false).authenticate(bearer(token), Map.of("sub", SUB)).recruiterId());
        assertEquals(RECRUITER_ID, authenticator(false).authenticate(bearer(token), Map.of()).recruiterId());
        assertEquals(RECRUITER_ID, authenticator(false).authenticate(bearer(token), null).recruiterId());
    }

    // ---- the account behind a valid token must be an approved recruiter -----------------------------------------------

    @Test
    void onlyAnApprovedGoogleRecruiterPassesEveryOtherAccountIs403() throws SQLException {
        String token = jwt.accessToken(SUB);
        // student, manager, role-less user
        assertRefused(token, profile(AccountStatus.ACTIVE, false, true, false, false, IdentityProvider.GOOGLE),
                "This account is not a recruiter account");
        assertRefused(token, profile(AccountStatus.ACTIVE, false, false, false, true, IdentityProvider.GOOGLE),
                "This account is not a recruiter account");
        assertRefused(token, profile(AccountStatus.ACTIVE, false, false, false, false, IdentityProvider.GOOGLE),
                "This account is not a recruiter account");
        // a student who is also suspended is still "another account type", not "your recruiter account"
        assertRefused(token, profile(AccountStatus.SUSPENDED, false, true, false, false, IdentityProvider.GOOGLE),
                "This account is not a recruiter account");
        // recruiter states
        assertRefused(token, profile(AccountStatus.PENDING, false, false, true, false, IdentityProvider.GOOGLE),
                "Your recruiter account is waiting for approval");
        assertRefused(token, profile(AccountStatus.SUSPENDED, false, false, true, false, IdentityProvider.GOOGLE),
                "Your recruiter account has been suspended");
        assertRefused(token, profile(AccountStatus.ACTIVE, true, false, true, false, IdentityProvider.GOOGLE),
                "This account is unavailable");
        assertRefused(token, profile(AccountStatus.SUSPENDED, true, false, true, false, IdentityProvider.GOOGLE),
                "This account is unavailable"); // deleted wins
        assertRefused(token, profile(AccountStatus.ACTIVE, false, false, true, false, IdentityProvider.COGNITO),
                "A Google sign-in is required");

        // and a Cognito identity nobody registered
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class, () -> authenticator(false).authenticate(bearer(token), Map.of()));
        assertEquals(403, e.getStatusCode());
        assertEquals("FORBIDDEN", e.getErrorCode());
        assertEquals("This account is not registered as a recruiter", e.getMessage());
    }

    private void assertRefused(String token, AccountProfile account, String message) throws SQLException {
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.of(account));
        ApiException e = assertThrows(ApiException.class, () -> authenticator(false).authenticate(bearer(token), Map.of()),
                message);
        assertEquals(403, e.getStatusCode(), message);
        assertEquals("FORBIDDEN", e.getErrorCode(), message);
        assertEquals(message, e.getMessage());
    }

    @Test
    void aDatabaseOutageIsASanitized503AndOtherSqlErrorsAreA500() throws SQLException {
        String token = jwt.accessToken(SUB);
        when(dataSource.getConnection()).thenThrow(
                new SQLTransientConnectionException("hiremebot-pool - Connection is not available: jdbc:mysql://db user=root"));

        ApiException outage = assertThrows(ApiException.class, () -> authenticator(false).authenticate(bearer(token), Map.of()));
        assertEquals(503, outage.getStatusCode());
        assertFalse(outage.getMessage().contains("jdbc"));

        doReturn(connection).when(dataSource).getConnection();
        when(profiles.findByCognitoSub(connection, SUB)).thenThrow(new SQLException("Unknown column", "42S22", 1054));
        ApiException broken = assertThrows(ApiException.class, () -> authenticator(false).authenticate(bearer(token), Map.of()));
        assertEquals(500, broken.getStatusCode());
        assertFalse(broken.getMessage().contains("column"));
    }

    // ---- development header: local only, and only when no token is sent ------------------------------------------------

    @Test
    void theDevelopmentHeaderWorksOnlyWhenPermittedAndNoTokenIsSent() {
        assertEquals(1_000_000_004L, authenticator(true)
                .authenticate(headers("x-dev-recruiter-id", "1000000004"), Map.of()).recruiterId());
        assertEquals(1_000_000_004L, authenticator(true)
                .authenticate(headers("X-DEV-RECRUITER-ID", " 1000000004 "), Map.of()).recruiterId());
        verifyNoInteractions(dataSource, profiles); // the services still check the recruiter themselves
    }

    @Test
    void aMissingOrMalformedDevelopmentHeaderIs400OnTheLocalPathOnly() {
        RecruiterAuthenticator local = authenticator(true);
        for (String value : new String[] {null, "", "   ", "abc", "0", "-5", "1.5", "99999999999999999999"}) {
            Map<String, String> request = new HashMap<>();
            if (value != null) {
                request.put("x-dev-recruiter-id", value);
            }
            assertEquals(400, assertThrows(ApiException.class, () -> local.authenticate(request, Map.of()))
                    .getStatusCode(), String.valueOf(value));
        }
        assertEquals("Missing required header X-Dev-Recruiter-Id", assertThrows(ApiException.class,
                () -> local.authenticate(new HashMap<>(), Map.of())).getMessage());
    }

    @Test
    void whenNotPermittedTheDevelopmentHeaderIsIgnoredAndNoCredentialIs401() {
        RecruiterAuthenticator deployed = authenticator(false);
        for (Map<String, String> request : List.of(headers("x-dev-recruiter-id", "1000000004"), new HashMap<String, String>())) {
            ApiException e = assertThrows(ApiException.class, () -> deployed.authenticate(request, Map.of()));
            assertEquals(401, e.getStatusCode());
            assertEquals("UNAUTHORIZED", e.getErrorCode());
        }
        assertEquals(401, assertThrows(ApiException.class, () -> deployed.authenticate(null, null)).getStatusCode());
        verifyNoInteractions(dataSource, profiles);
    }

    // ---- the gate is built from the environment: explicit flag AND sam local ---------------------------------------------

    private static Map<String, String> cognitoEnv(String... extra) {
        Map<String, String> env = new HashMap<>(Map.of("COGNITO_REGION", "us-east-2",
                "COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY", "COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9"));
        for (int i = 0; i < extra.length; i += 2) {
            env.put(extra[i], extra[i + 1]);
        }
        return env;
    }

    @Test
    void aDeployedEnvironmentNeverAcceptsTheHeaderEvenWithTheFlagSet() {
        Map<String, String> request = headers("x-dev-recruiter-id", "1000000004");
        for (Map<String, String> env : List.of(
                cognitoEnv(),                                                               // flag absent
                cognitoEnv("ALLOW_DEV_RECRUITER_HEADER", "false", "AWS_SAM_LOCAL", "true"), // flag off
                cognitoEnv("ALLOW_DEV_RECRUITER_HEADER", "true"),                           // flag on, NOT sam local = deployed
                cognitoEnv("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "false"))) {
            RecruiterAuthenticator deployed = RecruiterAuthenticator.fromEnvironment(env, dataSource, Clock.systemUTC());
            assertEquals(401, assertThrows(ApiException.class, () -> deployed.authenticate(request, Map.of()))
                    .getStatusCode(), env.toString());
        }
    }

    @Test
    void aLocalSamRunWithTheFlagAcceptsTheHeaderButStillPrefersAToken() {
        RecruiterAuthenticator local = RecruiterAuthenticator.fromEnvironment(
                cognitoEnv("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "true"), dataSource, Clock.systemUTC());

        assertEquals(1_000_000_004L, local.authenticate(headers("x-dev-recruiter-id", "1000000004"), Map.of()).recruiterId());
        // a bad token with a valid header is still 401 (fails at parse time, so no JWKS is fetched in this unit test)
        Map<String, String> badToken = headers("authorization", "Bearer not-a-jwt", "x-dev-recruiter-id", "1000000004");
        assertEquals(401, assertThrows(ApiException.class, () -> local.authenticate(badToken, Map.of())).getStatusCode());
    }

    @Test
    void aMissingOrInvalidCognitoSettingFailsClosedWhenBuilding() {
        Map<String, String> env = cognitoEnv();
        env.remove("COGNITO_CLIENT_ID");
        assertThrows(IllegalStateException.class,
                () -> RecruiterAuthenticator.fromEnvironment(env, dataSource, Clock.systemUTC()));
    }

    @Test
    void aRefusedTokenIsAnswerredWithTheGenericMessageOnly() {
        String token = jwt.accessToken(SUB);
        ApiException e = assertThrows(ApiException.class, () -> authenticator(false)
                .authenticate(bearer(token + "tampered"), Map.of()));
        assertEquals("Authentication is required.", e.getMessage());
        assertFalse(e.getMessage().contains(token.substring(0, 20)));
    }
}
