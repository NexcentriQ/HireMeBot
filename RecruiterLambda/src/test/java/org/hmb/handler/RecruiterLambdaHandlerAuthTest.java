package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.TestData;
import org.hmb.dto.AssignedStudentsResponse;
import org.hmb.dto.RecruiterResponse;
import org.hmb.exception.ApiException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.AccountProfileRepository;
import org.hmb.security.RecruiterAuthenticator;
import org.hmb.security.TokenVerifier;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.ApplicationService;
import org.hmb.service.RecruiterStudentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Both recruiter routes with the Cognito-token authentication wired in (the full {@link RecruiterAuthenticator}; the
 * token verifier is a stub here because real signature checks are tested in {@code common}, and the account lookup is
 * mocked; real MySQL and real signed tokens are covered by the integration tests). The point of this class: the services
 * only ever receive the id resolved from the token, and every refusal happens before any service call.
 */
class RecruiterLambdaHandlerAuthTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final String GOOD = "good.access.token";
    private static final long TOKEN_RECRUITER = 1_000_000_004L;
    private static final String APPLICATIONS = "/api/v1/recruiter/applications";
    private static final String STUDENTS = "/api/v1/recruiter/students";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationService applications = mock(ApplicationService.class);
    private final RecruiterStudentService students = mock(RecruiterStudentService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final AccountProfileRepository profiles = mock(AccountProfileRepository.class);

    /** Accepts exactly one token; every other value behaves like any rejected token (the generic 401). */
    private final TokenVerifier verifier = token -> {
        if (GOOD.equals(token.value())) {
            return new VerifiedAccessToken(SUB, "google_1", "client", Set.of("openid"), Instant.now().plusSeconds(600));
        }
        throw ApiException.unauthorized();
    };

    private RecruiterLambdaHandler handler;

    @BeforeEach
    void setUp() throws SQLException {
        doReturn(connection).when(dataSource).getConnection();
        setAccount(AccountStatus.ACTIVE, false, true, false, false, IdentityProvider.GOOGLE);
        handler = handlerWith(false);
    }

    private RecruiterLambdaHandler handlerWith(boolean devHeaderPermitted) {
        return new RecruiterLambdaHandler(applications, students,
                new RecruiterAuthenticator(verifier, dataSource, profiles, devHeaderPermitted));
    }

    private void setAccount(AccountStatus status, boolean deleted, boolean recruiter, boolean student, boolean manager,
                            IdentityProvider provider) throws SQLException {
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.of(new AccountProfile(TOKEN_RECRUITER,
                "Rita", "Recruiter", "rita@gmail.com", "+17135550101", true, false, status, deleted, student, recruiter,
                manager, provider)));
    }

    private static APIGatewayV2HTTPEvent event(String method, String path, Map<String, String> headers, String body) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withRequestId("req-test-1")
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method).withPath(path).build())
                        .build())
                .build();
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + token);
        return headers;
    }

    private APIGatewayV2HTTPResponse getStudents(Map<String, String> headers) {
        return handler.handleRequest(event("GET", STUDENTS, headers, null), null);
    }

    private APIGatewayV2HTTPResponse postApplication(Map<String, String> headers) {
        return handler.handleRequest(event("POST", APPLICATIONS, headers, TestData.VALID_JSON), null);
    }

    // ---- the recruiter is the one in the token ---------------------------------------------------------------------

    @Test
    void anApprovedRecruitersTokenListsTheirStudentsWithoutAnyHeader() throws Exception {
        when(students.getAssignedStudents(TOKEN_RECRUITER))
                .thenReturn(AssignedStudentsResponse.of(TOKEN_RECRUITER, List.of()));

        APIGatewayV2HTTPResponse response = getStudents(bearer(GOOD));

        assertEquals(200, response.getStatusCode());
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertEquals(TOKEN_RECRUITER, data.get("recruiterId").asLong());
        verify(students).getAssignedStudents(TOKEN_RECRUITER);
        verifyNoMoreInteractions(students);
        verifyNoInteractions(applications);
        assertFalse(response.getBody().contains(GOOD), "the token is never echoed");
    }

    @Test
    void anApprovedRecruitersTokenRecordsAnApplicationAsThatRecruiter() throws Exception {
        when(applications.createApplication(eq(TOKEN_RECRUITER), any())).thenReturn(RecruiterResponse.created(1000000042L));

        APIGatewayV2HTTPResponse response = postApplication(bearer(GOOD));

        assertEquals(201, response.getStatusCode());
        assertEquals(1000000042L, JSON.readTree(response.getBody()).get("applicationId").asLong());
        verify(applications).createApplication(eq(TOKEN_RECRUITER), any());
        verifyNoMoreInteractions(applications);
    }

    @Test
    void anotherRecruitersIdInTheHeaderQueryStringOrBodyNeverChangesTheActor() {
        when(students.getAssignedStudents(TOKEN_RECRUITER))
                .thenReturn(AssignedStudentsResponse.of(TOKEN_RECRUITER, List.of()));
        handler = handlerWith(true); // even where the dev header is permitted
        Map<String, String> headers = bearer(GOOD);
        headers.put("X-Dev-Recruiter-Id", "1000000099");
        headers.put("X-Recruiter-Id", "1000000098");
        headers.put("X-User-Id", "1000000097");
        APIGatewayV2HTTPEvent event = event("GET", STUDENTS, headers, null);
        event.setQueryStringParameters(Map.of("recruiterId", "1000000096", "userId", "1000000095",
                "email", "someone-else@gmail.com"));

        assertEquals(200, handler.handleRequest(event, null).getStatusCode());

        verify(students).getAssignedStudents(TOKEN_RECRUITER);
        verifyNoMoreInteractions(students);
    }

    @Test
    void aRecruiterIdInTheApplicationBodyIsStillRejectedAndTheServiceIsNotCalled() {
        String json = TestData.VALID_JSON.replaceFirst("\\{", "{\"recruiterId\": 1000000099,");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event("POST", APPLICATIONS, bearer(GOOD), json), null);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(applications);
    }

    // ---- a token always wins; a bad one is never retried as the dev header ----------------------------------------------

    @Test
    void aValidTokenBeatsADifferentDevelopmentHeader() {
        when(students.getAssignedStudents(TOKEN_RECRUITER))
                .thenReturn(AssignedStudentsResponse.of(TOKEN_RECRUITER, List.of()));
        handler = handlerWith(true);
        Map<String, String> headers = bearer(GOOD);
        headers.put("x-dev-recruiter-id", "1000000001");

        assertEquals(200, getStudents(headers).getStatusCode());

        verify(students).getAssignedStudents(TOKEN_RECRUITER);
        verifyNoMoreInteractions(students);
    }

    @Test
    void anInvalidExpiredOrWrongTokenIs401OnBothRoutesEvenWithAValidPermittedDevelopmentHeader() throws Exception {
        handler = handlerWith(true);
        for (String token : List.of("expired.access.token", "id.token.value", "foreign.key.token", "garbage")) {
            Map<String, String> headers = bearer(token);
            headers.put("x-dev-recruiter-id", "1000000004");
            for (APIGatewayV2HTTPResponse response : List.of(getStudents(headers), postApplication(headers))) {
                assertEquals(401, response.getStatusCode(), token);
                JsonNode body = JSON.readTree(response.getBody());
                assertFalse(body.get("success").asBoolean());
                assertEquals("UNAUTHORIZED", body.get("error").asText());
                assertEquals("Authentication is required.", body.get("message").asText());
            }
        }
        // Malformed Authorization headers are 401 too, again without falling back to the header.
        for (String value : List.of("", "Bearer", "Basic dXNlcjpwYXNz", "Bearer a b")) {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", value);
            headers.put("x-dev-recruiter-id", "1000000004");
            assertEquals(401, getStudents(headers).getStatusCode(), value);
        }
        verifyNoInteractions(students, applications, profiles);
    }

    // ---- the account behind a valid token must be an approved recruiter ---------------------------------------------------

    @Test
    void everyAccountThatIsNotAnApprovedRecruiterIs403OnBothRoutesBeforeAnyService() throws Exception {
        record Case(String name, AccountStatus status, boolean deleted, boolean recruiter, boolean student,
                    boolean manager, IdentityProvider provider) {
        }
        List<Case> cases = List.of(
                new Case("student", AccountStatus.ACTIVE, false, false, true, false, IdentityProvider.GOOGLE),
                new Case("manager", AccountStatus.ACTIVE, false, false, false, true, IdentityProvider.GOOGLE),
                new Case("no role", AccountStatus.ACTIVE, false, false, false, false, IdentityProvider.GOOGLE),
                new Case("pending", AccountStatus.PENDING, false, true, false, false, IdentityProvider.GOOGLE),
                new Case("suspended", AccountStatus.SUSPENDED, false, true, false, false, IdentityProvider.GOOGLE),
                new Case("deleted", AccountStatus.ACTIVE, true, true, false, false, IdentityProvider.GOOGLE),
                new Case("not google", AccountStatus.ACTIVE, false, true, false, false, IdentityProvider.COGNITO));
        for (Case c : cases) {
            setAccount(c.status(), c.deleted(), c.recruiter(), c.student(), c.manager(), c.provider());
            for (APIGatewayV2HTTPResponse response : List.of(getStudents(bearer(GOOD)), postApplication(bearer(GOOD)))) {
                assertEquals(403, response.getStatusCode(), c.name());
                JsonNode body = JSON.readTree(response.getBody());
                assertFalse(body.get("success").asBoolean(), c.name());
                assertEquals("FORBIDDEN", body.get("error").asText(), c.name());
            }
        }
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty()); // a Google user nobody registered
        assertEquals(403, getStudents(bearer(GOOD)).getStatusCode());
        assertEquals(403, postApplication(bearer(GOOD)).getStatusCode());
        verifyNoInteractions(students, applications);
    }

    @Test
    void theRefusalMessagesAreSafeAndSpecific() throws Exception {
        setAccount(AccountStatus.PENDING, false, true, false, false, IdentityProvider.GOOGLE);
        assertEquals("Your recruiter account is waiting for approval",
                JSON.readTree(getStudents(bearer(GOOD)).getBody()).get("message").asText());
        setAccount(AccountStatus.ACTIVE, false, false, true, false, IdentityProvider.GOOGLE);
        assertEquals("This account is not a recruiter account",
                JSON.readTree(getStudents(bearer(GOOD)).getBody()).get("message").asText());
    }

    @Test
    void aDatabaseOutageWhileAuthenticatingIsASanitized503() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("jdbc:mysql://db.internal user=root"));

        APIGatewayV2HTTPResponse response = getStudents(bearer(GOOD));

        assertEquals(503, response.getStatusCode());
        assertEquals("SERVICE_UNAVAILABLE", JSON.readTree(response.getBody()).get("error").asText());
        assertFalse(response.getBody().contains("jdbc"));
        assertFalse(response.getBody().contains("root"));
        verifyNoInteractions(students);
    }

    // ---- the development header: local only, and only without a token ---------------------------------------------------------

    @Test
    void aDeployedStyleEnvironmentCannotAuthenticateWithTheHeaderEvenIfTheFlagIsSet() {
        Map<String, String> deployedEnv = Map.of("COGNITO_REGION", "us-east-2",
                "COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY", "COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9",
                "ALLOW_DEV_RECRUITER_HEADER", "true"); // flag on, but not running under sam local
        handler = new RecruiterLambdaHandler(applications, students,
                RecruiterAuthenticator.fromEnvironment(deployedEnv, dataSource, Clock.systemUTC()));
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dev-recruiter-id", "1000000004");

        assertEquals(401, getStudents(headers).getStatusCode());
        assertEquals(401, postApplication(headers).getStatusCode());
        verifyNoInteractions(students, applications);
    }

    @Test
    void theHeaderOnlyLocalPathStillWorksAndKeepsItsMissingHeaderMessage() throws Exception {
        handler = handlerWith(true);
        when(students.getAssignedStudents(1000000004L)).thenReturn(AssignedStudentsResponse.of(1000000004L, List.of()));
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Dev-Recruiter-Id", "1000000004");
        assertEquals(200, getStudents(headers).getStatusCode());

        APIGatewayV2HTTPResponse missing = getStudents(new HashMap<>());
        assertEquals(400, missing.getStatusCode());
        assertTrue(JSON.readTree(missing.getBody()).get("message").asText().contains("Missing required header"));
    }

    @Test
    void authorizerClaimsForAnotherSubjectAre401() {
        APIGatewayV2HTTPEvent event = event("GET", STUDENTS, bearer(GOOD), null);
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = new APIGatewayV2HTTPEvent.RequestContext.Authorizer();
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt = new APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT();
        jwt.setClaims(Map.of("sub", "forged-subject"));
        authorizer.setJwt(jwt);
        event.getRequestContext().setAuthorizer(authorizer);

        assertEquals(401, handler.handleRequest(event, null).getStatusCode());
        verifyNoInteractions(students);
    }

    @Test
    void routingErrorsAreAnsweredBeforeAuthenticationAsBefore() {
        // Routing errors are answered before authentication, exactly as before.
        assertEquals(404, handler.handleRequest(event("GET", "/api/v1/recruiter/other", new HashMap<>(), null), null)
                .getStatusCode());
        assertEquals(405, handler.handleRequest(event("POST", STUDENTS, new HashMap<>(), "{}"), null).getStatusCode());
        // ... and a route with the right method but no credential is 401.
        assertEquals(401, getStudents(new HashMap<>()).getStatusCode());
    }
}
