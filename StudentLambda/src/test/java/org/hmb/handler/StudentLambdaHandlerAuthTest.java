package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.dto.StudentProfileDto;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.StudentIdentityRepository;
import org.hmb.repository.StudentIdentityRepository.StudentIdentity;
import org.hmb.security.StudentAuthenticator;
import org.hmb.service.StudentApplicationService;
import org.hmb.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The student dashboard route with the real handler and a real {@link StudentAuthenticator} (real JWT verification,
 * locally signed), a mocked database lookup and a mocked service: the student passed to the service must come only from
 * the verified token.
 */
class StudentLambdaHandlerAuthTest {

    private static final String PATH = "/api/v1/student/applications";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUB_A = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final String SUB_B = "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9";
    private static final long STUDENT_A = 1_000_000_002L;
    private static final long STUDENT_B = 1_000_000_003L;

    private final TestJwt jwt = new TestJwt();
    private final StudentApplicationService service = mock(StudentApplicationService.class);
    private final StudentIdentityRepository identities = mock(StudentIdentityRepository.class);
    private final Connection connection = mock(Connection.class);
    private final DataSource dataSource = mock(DataSource.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        stored(SUB_A, STUDENT_A, AccountStatus.ACTIVE, false, true);
        stored(SUB_B, STUDENT_B, AccountStatus.ACTIVE, false, true);
        when(service.getApplications(anyLong())).thenAnswer(call -> responseFor(call.getArgument(0)));
    }

    private void stored(String sub, long id, AccountStatus status, boolean deleted, boolean student)
            throws SQLException {
        when(identities.findByCognitoSub(connection, sub)).thenReturn(
                Optional.of(new StudentIdentity(id, status, deleted, student, IdentityProvider.GOOGLE)));
    }

    private static StudentApplicationsResponse responseFor(long studentId) {
        return StudentApplicationsResponse.of(new StudentApplicationsResponse.Data(studentId, "2026-10-29",
                StudentProfileDto.of("Test", "Student", "s" + studentId + "@example.com", "+17135550123", false),
                0, 20, 0, Map.of(), null));
    }

    private StudentLambdaHandler handler(boolean devHeaderPermitted) {
        return new StudentLambdaHandler(service,
                new StudentAuthenticator(jwt.verifier(), dataSource, identities, devHeaderPermitted));
    }

    private APIGatewayV2HTTPResponse call(StudentLambdaHandler handler, Map<String, String> headers,
                                          Map<String, String> authorizerClaims) {
        var context = APIGatewayV2HTTPEvent.RequestContext.builder()
                .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod("GET").withPath(PATH).build());
        if (authorizerClaims != null) {
            context.withAuthorizer(APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                    .withJwt(APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
                            .withClaims(authorizerClaims).build())
                    .build());
        }
        return handler.handleRequest(APIGatewayV2HTTPEvent.builder().withRawPath(PATH).withHeaders(headers)
                .withRequestContext(context.build()).build(), null);
    }

    private Map<String, String> bearer(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + token);
        return headers;
    }

    @Test
    void anActiveStudentsTokenReturns200ForThatStudentOnly() throws Exception {
        APIGatewayV2HTTPResponse response = call(handler(false), bearer(jwt.accessToken(SUB_A)), Map.of("sub", SUB_A));

        assertEquals(200, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertEquals(STUDENT_A, body.get("data").get("studentId").asLong());
        verify(service).getApplications(STUDENT_A);
        verify(service, never()).getApplications(STUDENT_B);
    }

    @Test
    void studentAWithAnyStudentBIdentifierStillGetsStudentA() throws Exception {
        Map<String, String> headers = bearer(jwt.accessToken(SUB_A));
        headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_B));
        headers.put("X-Student-Id", String.valueOf(STUDENT_B));

        for (boolean devHeaderPermitted : new boolean[]{false, true}) {
            APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().withRawPath(PATH).withHeaders(headers)
                    .withQueryStringParameters(Map.of("studentId", String.valueOf(STUDENT_B)))
                    .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                            .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                    .withMethod("GET").withPath(PATH).build()).build())
                    .build();

            APIGatewayV2HTTPResponse response = handler(devHeaderPermitted).handleRequest(event, null);

            assertEquals(200, response.getStatusCode());
            assertEquals(STUDENT_A, JSON.readTree(response.getBody()).get("data").get("studentId").asLong());
        }
        verify(service, never()).getApplications(STUDENT_B);
    }

    @Test
    void badTokensAre401WithTheStandardEnvelopeAndNeverReachTheServiceOrTheDevHeader() throws Exception {
        for (String bad : jwt.badTokensFor(SUB_A)) {
            Map<String, String> headers = bearer(bad);
            headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_B));

            APIGatewayV2HTTPResponse response = call(handler(true), headers, null);

            assertEquals(401, response.getStatusCode(), "token: " + bad);
            JsonNode body = JSON.readTree(response.getBody());
            assertFalse(body.get("success").asBoolean());
            assertEquals("UNAUTHORIZED", body.get("error").asText());
            assertEquals("Authentication is required.", body.get("message").asText());
        }
        verifyNoInteractions(service);
    }

    @Test
    void noAuthorizationHeaderIs401EvenWithTheDevHeaderWhenItIsNotPermitted() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_A));

        assertEquals(401, call(handler(false), headers, null).getStatusCode());
        assertEquals(401, call(handler(false), new HashMap<>(), null).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void theDevHeaderWorksOnlyWhenPermittedAndNoTokenIsSent() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Dev-Student-Id", String.valueOf(STUDENT_B));

        assertEquals(200, call(handler(true), headers, null).getStatusCode());
        verify(service).getApplications(STUDENT_B);
    }

    @Test
    void pendingSuspendedDeletedAndNonStudentAccountsAre403() throws Exception {
        stored(SUB_A, STUDENT_A, AccountStatus.PENDING, false, true);
        assertEquals(403, call(handler(false), bearer(jwt.accessToken(SUB_A)), null).getStatusCode());

        stored(SUB_A, STUDENT_A, AccountStatus.SUSPENDED, false, true);
        assertEquals(403, call(handler(false), bearer(jwt.accessToken(SUB_A)), null).getStatusCode());

        stored(SUB_A, STUDENT_A, AccountStatus.ACTIVE, true, true);
        assertEquals(403, call(handler(false), bearer(jwt.accessToken(SUB_A)), null).getStatusCode());

        stored(SUB_A, STUDENT_A, AccountStatus.ACTIVE, false, false);
        APIGatewayV2HTTPResponse wrongRole = call(handler(false), bearer(jwt.accessToken(SUB_A)), null);
        assertEquals(403, wrongRole.getStatusCode());
        assertEquals("FORBIDDEN", JSON.readTree(wrongRole.getBody()).get("error").asText());

        verifyNoInteractions(service);
    }

    @Test
    void anAuthorizerSubjectThatDiffersFromTheTokenIs401() {
        assertEquals(401, call(handler(false), bearer(jwt.accessToken(SUB_A)), Map.of("sub", SUB_B)).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void routeAndMethodAreStillCheckedBeforeAuthentication() {
        APIGatewayV2HTTPEvent post = APIGatewayV2HTTPEvent.builder().withRawPath(PATH)
                .withHeaders(bearer(jwt.accessToken(SUB_A)))
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod("POST").withPath(PATH).build()).build())
                .build();

        assertEquals(405, handler(false).handleRequest(post, null).getStatusCode());
        verifyNoInteractions(service);
    }
}
