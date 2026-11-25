package org.hmb.security;

import org.hmb.exception.ApiException;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.StudentIdentityRepository;
import org.hmb.repository.StudentIdentityRepository.StudentIdentity;
import org.hmb.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The student resolver with REAL token verification (locally signed JWTs) and a mocked database. The SQL itself is
 * covered by {@code StudentTokenAuthIntegrationTest} on a real MySQL.
 */
class StudentAuthenticatorTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final long STUDENT_ID = 1_000_000_002L;

    private final TestJwt jwt = new TestJwt();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final StudentIdentityRepository identities = mock(StudentIdentityRepository.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        stored(AccountStatus.ACTIVE, false, true, IdentityProvider.GOOGLE);
    }

    private void stored(AccountStatus status, boolean deleted, boolean student, IdentityProvider provider)
            throws SQLException {
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(
                Optional.of(new StudentIdentity(STUDENT_ID, status, deleted, student, provider)));
    }

    private StudentAuthenticator authenticator(boolean devHeaderPermitted) {
        return new StudentAuthenticator(jwt.verifier(), dataSource, identities, devHeaderPermitted);
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

    private ApiException refused(Map<String, String> requestHeaders) {
        return assertThrows(ApiException.class, () -> authenticator(false).authenticate(requestHeaders, Map.of()));
    }

    // ---- a valid token identifies an ACTIVE student ------------------------------------------------------------------

    @Test
    void anActiveStudentsTokenResolvesToTheirInternalId() throws SQLException {
        StudentAuthenticator.Principal principal =
                authenticator(false).authenticate(bearer(jwt.accessToken(SUB)), Map.of());

        assertEquals(STUDENT_ID, principal.studentId());
        verify(identities).findByCognitoSub(connection, SUB); // looked up by the VERIFIED token's subject only
        verify(connection).close();
    }

    @Test
    void theAuthorizationHeaderNameAndBearerSchemeAreCaseInsensitive() {
        String token = jwt.accessToken(SUB);
        assertEquals(STUDENT_ID, authenticator(false)
                .authenticate(headers("AUTHORIZATION", "bearer " + token), null).studentId());
        assertEquals(STUDENT_ID, authenticator(false)
                .authenticate(headers("Authorization", "  BEARER   " + token + "  "), null).studentId());
    }

    @Test
    void anythingElseTheClientSendsCannotChangeTheActingStudent() {
        Map<String, String> forged = bearer(jwt.accessToken(SUB));
        forged.put("X-Dev-Student-Id", "1000000099");
        forged.put("X-Student-Id", "1000000098");
        forged.put("X-User-Id", "1000000097");

        assertEquals(STUDENT_ID, authenticator(true).authenticate(forged, Map.of()).studentId());
        assertEquals(STUDENT_ID, authenticator(false).authenticate(forged, Map.of()).studentId());
    }

    @Test
    void whenTheAuthorizerRanItsSubjectMustMatchTheVerifiedToken() {
        Map<String, String> headers = bearer(jwt.accessToken(SUB));

        assertEquals(STUDENT_ID, authenticator(false).authenticate(headers, Map.of("sub", SUB)).studentId());
        assertEquals(401, assertThrows(ApiException.class, () -> authenticator(false)
                .authenticate(headers, Map.of("sub", "11111111-2222-3333-4444-555555555555"))).getStatusCode());
    }

    // ---- accounts that must be refused (403) ------------------------------------------------------------------------

    @Test
    void aPendingStudentIs403() throws SQLException {
        stored(AccountStatus.PENDING, false, true, IdentityProvider.GOOGLE);

        ApiException e = refused(bearer(jwt.accessToken(SUB)));

        assertEquals(403, e.getStatusCode());
        assertEquals("FORBIDDEN", e.getErrorCode());
        assertEquals("Your student account is waiting for activation", e.getMessage());
    }

    @Test
    void aSuspendedStudentIs403() throws SQLException {
        stored(AccountStatus.SUSPENDED, false, true, IdentityProvider.GOOGLE);

        ApiException e = refused(bearer(jwt.accessToken(SUB)));

        assertEquals(403, e.getStatusCode());
        assertEquals("Your student account has been suspended", e.getMessage());
    }

    @Test
    void aDeletedStudentIs403EvenWhenActive() throws SQLException {
        stored(AccountStatus.ACTIVE, true, true, IdentityProvider.GOOGLE);

        ApiException e = refused(bearer(jwt.accessToken(SUB)));

        assertEquals(403, e.getStatusCode());
        assertEquals("This account is unavailable", e.getMessage());
    }

    @Test
    void anAccountWithoutAStudentsRowIs403() throws SQLException {
        stored(AccountStatus.ACTIVE, false, false, IdentityProvider.GOOGLE); // a recruiter or manager

        ApiException e = refused(bearer(jwt.accessToken(SUB)));

        assertEquals(403, e.getStatusCode());
        assertEquals("This account is not a student account", e.getMessage());
    }

    @Test
    void aNonGoogleIdentityIs403() throws SQLException {
        stored(AccountStatus.ACTIVE, false, true, IdentityProvider.COGNITO);

        assertEquals(403, refused(bearer(jwt.accessToken(SUB))).getStatusCode());
    }

    @Test
    void aValidTokenWithNoHireMeBotAccountIs403() throws SQLException {
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty());

        ApiException e = refused(bearer(jwt.accessToken(SUB)));

        assertEquals(403, e.getStatusCode());
        assertEquals("This account is not registered as a student", e.getMessage());
    }

    @Test
    void theStatusIsReadOnEveryRequestSoASuspensionTakesEffectImmediately() throws SQLException {
        StudentAuthenticator authenticator = authenticator(false);
        Map<String, String> headers = bearer(jwt.accessToken(SUB));
        assertEquals(STUDENT_ID, authenticator.authenticate(headers, Map.of()).studentId());

        stored(AccountStatus.SUSPENDED, false, true, IdentityProvider.GOOGLE);

        assertEquals(403, assertThrows(ApiException.class, () -> authenticator.authenticate(headers, Map.of()))
                .getStatusCode());
    }

    // ---- bad tokens are 401 and never fall back to the development header -----------------------------------------------

    @Test
    void everyBadTokenIs401EvenWithAPermittedAndValidDevelopmentHeaderAndTheDatabaseIsNeverAsked() {
        for (String bad : jwt.badTokensFor(SUB)) {
            Map<String, String> headers = bearer(bad);
            headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_ID));

            ApiException e = assertThrows(ApiException.class, () -> authenticator(true).authenticate(headers, Map.of()));

            assertEquals(401, e.getStatusCode(), "token: " + bad);
            assertEquals("UNAUTHORIZED", e.getErrorCode());
        }
        verifyNoInteractions(dataSource, identities);
    }

    @Test
    void aMalformedAuthorizationHeaderIs401AndNeverFallsBackToTheHeader() {
        for (String value : new String[]{"", "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "Token abc", "Bearer a b",
                "Bearer " + "x".repeat(9000)}) {
            Map<String, String> headers = headers("Authorization", value);
            headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_ID));

            assertEquals(401, assertThrows(ApiException.class, () -> authenticator(true).authenticate(headers, Map.of()))
                    .getStatusCode(), "header: " + value);
        }
        verifyNoInteractions(dataSource, identities);
    }

    @Test
    void aTokenIsRefusedWhenNoVerifierIsConfiguredAndNeverTreatedAsHeaderAuth() {
        Map<String, String> headers = bearer(jwt.accessToken(SUB));
        headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_ID));

        assertEquals(401, assertThrows(ApiException.class,
                () -> StudentAuthenticator.devHeaderOnly(true).authenticate(headers, Map.of())).getStatusCode());
    }

    // ---- the development header: local only, never with a token -------------------------------------------------------

    @Test
    void withoutAnyAuthorizationHeaderAndNoPermissionTheRequestIs401EvenWithTheDevHeader() {
        ApiException e = refused(headers("X-Dev-Student-Id", String.valueOf(STUDENT_ID)));

        assertEquals(401, e.getStatusCode());
        verifyNoInteractions(dataSource, identities);
    }

    @Test
    void whenPermittedAndNoTokenIsSentTheDevHeaderIdentifiesTheStudentWithoutTheDatabase() {
        assertEquals(1_000_000_005L, authenticator(true)
                .authenticate(headers("x-dev-student-id", " 1000000005 "), Map.of()).studentId());
        verifyNoInteractions(dataSource, identities);
    }

    @Test
    void aMissingOrMalformedDevHeaderIs400WhenPermitted() {
        for (String value : new String[]{null, " ", "abc", "0", "-5", "1.5", "99999999999999999999"}) {
            Map<String, String> headers = new HashMap<>();
            if (value != null) {
                headers.put("X-Dev-Student-Id", value);
            }
            assertEquals(400, assertThrows(ApiException.class, () -> authenticator(true).authenticate(headers, Map.of()))
                    .getStatusCode(), "value: " + value);
        }
    }

    @Test
    void theDevHeaderIsPermittedOnlyUnderSamLocalWithTheStudentFlag() {
        assertTrue(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "true", "AWS_SAM_LOCAL", "true"),
                DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "true"), DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("AWS_SAM_LOCAL", "true"), DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "false", "AWS_SAM_LOCAL", "true"),
                DevAuth.STUDENT_FLAG_VAR));
        // The recruiter flag does not enable the student header.
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "true"),
                DevAuth.STUDENT_FLAG_VAR));
    }

    // ---- outages ------------------------------------------------------------------------------------------------------

    @Test
    void aDatabaseOutageIs503() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool exhausted"));

        assertEquals(503, refused(bearer(jwt.accessToken(SUB))).getStatusCode());
    }

    @Test
    void anotherDatabaseErrorIs500AndLeaksNothing() throws SQLException {
        when(identities.findByCognitoSub(connection, SUB)).thenThrow(new SQLException("secret detail", "42S02", 1146));

        ApiException e = refused(bearer(jwt.accessToken(SUB)));

        assertEquals(500, e.getStatusCode());
        assertFalse(e.getMessage().contains("secret"));
    }
}
