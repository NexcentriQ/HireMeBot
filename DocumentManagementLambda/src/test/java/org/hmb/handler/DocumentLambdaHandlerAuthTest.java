package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.Fixtures;
import org.hmb.dto.DocumentType;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.RegisterResponse;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.dto.UploadUrlResponse;
import org.hmb.exception.ApiException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.AccountProfileRepository;
import org.hmb.security.RecruiterAuthenticator;
import org.hmb.security.TokenVerifier;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.DocumentUploadService;
import org.hmb.service.DocumentUploadService.RegisterOutcome;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Both document routes with the Cognito-token authentication wired in (the full {@link RecruiterAuthenticator}; the
 * token verifier is a stub because real signature checks are tested in {@code common}, the account lookup is mocked).
 * The service only ever receives the id resolved from the token, and every refusal happens before it is called.
 */
class DocumentLambdaHandlerAuthTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final String GOOD = "good.access.token";
    private static final long TOKEN_RECRUITER = 1_000_000_004L;
    private static final String UPLOAD_URL_PATH = "/api/v1/documents/upload-url";
    private static final String REGISTER_PATH = "/api/v1/documents/register";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String UPLOAD_BODY = """
            {"studentId": 1000000005, "documentType": "RESUME", "fileName": "Akhil_Resume.pdf",
             "contentType": "application/pdf", "fileSize": 245760,
             "checksum": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}
            """;
    private static final String REGISTER_BODY = """
            {"studentId": 1000000005, "documentType": "RESUME",
             "objectKey": "students/1000000005/resumes/123e4567-e89b-12d3-a456-426614174000.pdf",
             "checksum": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
             "uploadToken": "token-value"}
            """;

    private final DocumentUploadService service = mock(DocumentUploadService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final AccountProfileRepository profiles = mock(AccountProfileRepository.class);

    private final TokenVerifier verifier = token -> {
        if (GOOD.equals(token.value())) {
            return new VerifiedAccessToken(SUB, "google_1", "client", Set.of("openid"), Instant.now().plusSeconds(600));
        }
        throw ApiException.unauthorized();
    };

    private DocumentLambdaHandler handler;

    @BeforeEach
    void setUp() throws SQLException {
        doReturn(connection).when(dataSource).getConnection();
        setAccount(AccountStatus.ACTIVE, false, true, false, false, IdentityProvider.GOOGLE);
        handler = handlerWith(false);
    }

    private DocumentLambdaHandler handlerWith(boolean devHeaderPermitted) {
        return new DocumentLambdaHandler(service, new RecruiterAuthenticator(verifier, dataSource, profiles, devHeaderPermitted));
    }

    private void setAccount(AccountStatus status, boolean deleted, boolean recruiter, boolean student, boolean manager,
                            IdentityProvider provider) throws SQLException {
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.of(new AccountProfile(TOKEN_RECRUITER,
                "Rita", "Recruiter", "rita@gmail.com", "+17135550101", true, false, status, deleted, student, recruiter,
                manager, provider)));
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + token);
        return headers;
    }

    private APIGatewayV2HTTPResponse post(String path, Map<String, String> headers, String body) {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withRequestId("req-test-1")
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod("POST").withPath(path).build())
                        .build())
                .build();
        return handler.handleRequest(event, null);
    }

    // ---- the recruiter is the one in the token ---------------------------------------------------------------------

    @Test
    void anApprovedRecruitersTokenRequestsAnUploadUrlAsThatRecruiter() throws Exception {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), eq(TOKEN_RECRUITER)))
                .thenReturn(UploadUrlResponse.alreadyExists(1000000001L));

        APIGatewayV2HTTPResponse response = post(UPLOAD_URL_PATH, bearer(GOOD), UPLOAD_BODY);

        assertEquals(200, response.getStatusCode());
        assertEquals("ALREADY_EXISTS", JSON.readTree(response.getBody()).get("status").asText());
        verify(service).requestUploadUrl(any(UploadUrlRequest.class), eq(TOKEN_RECRUITER));
        verifyNoMoreInteractions(service);
        assertFalse(response.getBody().contains(GOOD), "the token is never echoed");
    }

    @Test
    void anApprovedRecruitersTokenRegistersADocumentAsThatRecruiter() throws Exception {
        when(service.registerDocument(any(RegisterRequest.class), eq(TOKEN_RECRUITER))).thenReturn(
                new RegisterOutcome(RegisterResponse.success(1000000002L, Fixtures.STUDENT_ID, DocumentType.RESUME), true));

        APIGatewayV2HTTPResponse response = post(REGISTER_PATH, bearer(GOOD), REGISTER_BODY);

        assertEquals(201, response.getStatusCode());
        verify(service).registerDocument(any(RegisterRequest.class), eq(TOKEN_RECRUITER));
        verifyNoMoreInteractions(service);
    }

    @Test
    void anotherRecruitersIdInTheHeadersOrBodyNeverChangesTheActor() {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), eq(TOKEN_RECRUITER)))
                .thenReturn(UploadUrlResponse.alreadyExists(1L));
        handler = handlerWith(true); // even where the dev header is permitted
        Map<String, String> headers = bearer(GOOD);
        headers.put("X-Dev-Recruiter-Id", "1000000099");
        headers.put("X-Recruiter-Id", "1000000098");
        headers.put("X-User-Id", "1000000097");

        assertEquals(200, post(UPLOAD_URL_PATH, headers, UPLOAD_BODY).getStatusCode());
        // a recruiter id inside the body is an unknown field and rejected, never used
        String forged = UPLOAD_BODY.replaceFirst("\\{", "{\"recruiterId\": 1000000099,");
        assertEquals(400, post(UPLOAD_URL_PATH, bearer(GOOD), forged).getStatusCode());

        verify(service).requestUploadUrl(any(UploadUrlRequest.class), eq(TOKEN_RECRUITER));
        verifyNoMoreInteractions(service);
    }

    // ---- a token always wins; a bad one is never retried as the dev header ----------------------------------------------

    @Test
    void aValidTokenBeatsADifferentDevelopmentHeader() {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), eq(TOKEN_RECRUITER)))
                .thenReturn(UploadUrlResponse.alreadyExists(1L));
        handler = handlerWith(true);
        Map<String, String> headers = bearer(GOOD);
        headers.put("x-dev-recruiter-id", "1000000001");

        assertEquals(200, post(UPLOAD_URL_PATH, headers, UPLOAD_BODY).getStatusCode());
        verify(service).requestUploadUrl(any(UploadUrlRequest.class), eq(TOKEN_RECRUITER));
        verifyNoMoreInteractions(service);
    }

    @Test
    void anInvalidExpiredOrWrongTokenIs401OnBothRoutesEvenWithAValidPermittedDevelopmentHeader() throws Exception {
        handler = handlerWith(true);
        for (String token : List.of("expired.access.token", "id.token.value", "foreign.key.token", "garbage")) {
            Map<String, String> headers = bearer(token);
            headers.put("x-dev-recruiter-id", "1000000004");
            for (APIGatewayV2HTTPResponse response : List.of(post(UPLOAD_URL_PATH, headers, UPLOAD_BODY),
                    post(REGISTER_PATH, headers, REGISTER_BODY))) {
                assertEquals(401, response.getStatusCode(), token);
                JsonNode body = JSON.readTree(response.getBody());
                assertFalse(body.get("success").asBoolean());
                assertEquals("UNAUTHORIZED", body.get("error").asText());
            }
        }
        for (String value : List.of("", "Bearer", "Basic dXNlcjpwYXNz", "Bearer a b")) {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", value);
            headers.put("x-dev-recruiter-id", "1000000004");
            assertEquals(401, post(UPLOAD_URL_PATH, headers, UPLOAD_BODY).getStatusCode(), value);
        }
        verifyNoInteractions(service, profiles);
    }

    // ---- the account behind a valid token must be an approved recruiter ---------------------------------------------------

    @Test
    void everyAccountThatIsNotAnApprovedRecruiterIs403OnBothRoutesBeforeTheService() throws Exception {
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
            for (APIGatewayV2HTTPResponse response : List.of(post(UPLOAD_URL_PATH, bearer(GOOD), UPLOAD_BODY),
                    post(REGISTER_PATH, bearer(GOOD), REGISTER_BODY))) {
                assertEquals(403, response.getStatusCode(), c.name());
                JsonNode body = JSON.readTree(response.getBody());
                assertFalse(body.get("success").asBoolean(), c.name());
                assertEquals("FORBIDDEN", body.get("error").asText(), c.name());
            }
        }
        when(profiles.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty()); // a Google user nobody registered
        assertEquals(403, post(UPLOAD_URL_PATH, bearer(GOOD), UPLOAD_BODY).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void aDatabaseOutageWhileAuthenticatingIsASanitized503() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("jdbc:mysql://db.internal user=root"));

        APIGatewayV2HTTPResponse response = post(UPLOAD_URL_PATH, bearer(GOOD), UPLOAD_BODY);

        assertEquals(503, response.getStatusCode());
        assertEquals("SERVICE_UNAVAILABLE", JSON.readTree(response.getBody()).get("error").asText());
        assertFalse(response.getBody().contains("jdbc"));
        verifyNoInteractions(service);
    }

    // ---- the development header: local only, and only without a token ---------------------------------------------------------

    @Test
    void aDeployedStyleEnvironmentCannotAuthenticateWithTheHeaderEvenIfTheFlagIsSet() {
        Map<String, String> deployedEnv = Map.of("COGNITO_REGION", "us-east-2",
                "COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY", "COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9",
                "ALLOW_DEV_RECRUITER_HEADER", "true"); // flag on, but not running under sam local
        handler = new DocumentLambdaHandler(service,
                RecruiterAuthenticator.fromEnvironment(deployedEnv, dataSource, Clock.systemUTC()));
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dev-recruiter-id", "1000000004");

        assertEquals(401, post(UPLOAD_URL_PATH, headers, UPLOAD_BODY).getStatusCode());
        assertEquals(401, post(REGISTER_PATH, headers, REGISTER_BODY).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void routingErrorsAreAnsweredBeforeAuthenticationAsBefore() {
        APIGatewayV2HTTPEvent unknown = APIGatewayV2HTTPEvent.builder().withRawPath("/api/v1/documents/other")
                .withHeaders(new HashMap<>())
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod("POST").build()).build())
                .build();
        assertEquals(404, handler.handleRequest(unknown, null).getStatusCode());
        // ... and a known route with no credential is 401.
        assertEquals(401, post(UPLOAD_URL_PATH, new HashMap<>(), UPLOAD_BODY).getStatusCode());
    }
}
