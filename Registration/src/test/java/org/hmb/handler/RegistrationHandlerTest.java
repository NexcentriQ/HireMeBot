package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.config.RegistrationConfig;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.NextStep;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.BearerToken;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.CognitoIdentityService;
import org.hmb.service.RegistrationValidator;
import org.hmb.service.StudentRegistrationService;
import org.hmb.support.Events;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistrationHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";

    private final TestTokens tokens = new TestTokens();
    private final CognitoAccessTokenVerifier verifier =
            new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID, tokens.jwkSource(), Clock.systemUTC());
    private final StudentRegistrationService service = mock(StudentRegistrationService.class);
    private final RegistrationHandler handler = new RegistrationHandler(verifier, service);

    private static StudentRegistrationData registered() {
        return new StudentRegistrationData(1_000_000_000L, 1_000_000_000L, "Alex", "Smith", "alex@gmail.com",
                "+17135550101", true, false, AccountStatus.PENDING, "STUDENT", NextStep.ACCOUNT_PENDING);
    }

    // ---- success -------------------------------------------------------------------------------------------------

    @Test
    void registersAndReturnsTheContract() throws Exception {
        when(service.register(any(), any(), any())).thenReturn(registered());

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.register(tokens.accessToken(SUB), Events.VALID_BODY), null);

        assertEquals(201, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        assertEquals("req-test-1", response.getHeaders().get("X-Request-Id"));
        JsonNode body = JSON.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertEquals("STUDENT_REGISTERED", body.get("code").asText());
        assertEquals("Student registered successfully", body.get("message").asText());
        JsonNode data = body.get("data");
        assertEquals(1_000_000_000L, data.get("userId").asLong());
        assertEquals(1_000_000_000L, data.get("studentId").asLong());
        assertEquals("alex@gmail.com", data.get("email").asText());
        assertEquals("+17135550101", data.get("phoneNumber").asText());
        assertTrue(data.get("emailVerified").asBoolean());
        assertFalse(data.get("phoneVerified").asBoolean());
        assertEquals("PENDING", data.get("accountStatus").asText());
        assertEquals("STUDENT", data.get("accountType").asText());
        assertEquals("ACCOUNT_PENDING", data.get("nextStep").asText());
    }

    @Test
    void passesTheVerifiedSubjectAndRawTokenToTheService() {
        when(service.register(any(), any(), any())).thenReturn(registered());
        String token = tokens.accessToken(SUB);

        handler.handleRequest(Events.register(token, Events.VALID_BODY), null);

        ArgumentCaptor<VerifiedAccessToken> verified = ArgumentCaptor.forClass(VerifiedAccessToken.class);
        ArgumentCaptor<BearerToken> bearer = ArgumentCaptor.forClass(BearerToken.class);
        verify(service).register(verified.capture(), bearer.capture(), any());
        assertEquals(SUB, verified.getValue().subject());
        assertEquals(token, bearer.getValue().value());
    }

    @Test
    void mockOtpStateFromTheClientIsIgnored() {
        when(service.register(any(), any(), any())).thenReturn(registered());
        String body = """
                {"firstName":"Alex","lastName":"Smith","phoneNumber":"+17135550101","acceptedTerms":true,
                 "phoneVerified":true,"otpVerified":true,"otpCode":"000000","email":"someone-else@gmail.com"}""";

        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.register(tokens.accessToken(SUB), body), null);

        assertEquals(201, response.getStatusCode());
        ArgumentCaptor<StudentRegistrationRequest> request = ArgumentCaptor.forClass(StudentRegistrationRequest.class);
        verify(service).register(any(), any(), request.capture());
        assertTrue(request.getValue().ignoredFields().containsAll(List.of("phoneVerified", "otpVerified", "email")));
    }

    // ---- authentication: 401 and nothing else happens -------------------------------------------------------------

    @Test
    void missingOrMalformedAuthorizationIs401() throws Exception {
        assertUnauthorized(Events.register(null, Events.VALID_BODY));
        Map<String, String> headers = new HashMap<>(Map.of("content-type", "application/json"));
        for (String value : List.of("", "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", "Token abc", "Bearer a b",
                "Bearer " + "x".repeat(9000))) {
            headers.put("authorization", value);
            assertUnauthorized(Events.event("POST", Events.PATH, headers, Events.VALID_BODY));
        }
    }

    @Test
    void invalidExpiredWrongPoolWrongClientAndIdTokensAre401() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        TestTokens otherPool = new TestTokens();
        for (String token : List.of(
                "garbage",
                tokens.sign(tokens.accessClaims(SUB).issueTime(Date.from(past))
                        .expirationTime(Date.from(past.plusSeconds(3600))).build()),
                otherPool.sign(otherPool.accessClaims(SUB)
                        .issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_AnotherPool").build()),
                tokens.sign(tokens.accessClaims(SUB).claim("client_id", "wrongclient00000000000000").build()),
                tokens.sign(tokens.idClaims(SUB, "alex@gmail.com").build()),
                TestTokens.unsigned(tokens.accessClaims(SUB).build()))) {
            assertUnauthorized(Events.register(token, Events.VALID_BODY));
        }
    }

    @Test
    void authorizerClaimsForAnotherSubjectAre401() throws Exception {
        APIGatewayV2HTTPEvent event = Events.register(tokens.accessToken(SUB), Events.VALID_BODY);
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = new APIGatewayV2HTTPEvent.RequestContext.Authorizer();
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt = new APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT();
        jwt.setClaims(Map.of("sub", "forged-subject"));
        authorizer.setJwt(jwt);
        event.getRequestContext().setAuthorizer(authorizer);

        assertUnauthorized(event);
    }

    @Test
    void unauthenticatedRequestsGet401BeforeBodyChecks() throws Exception {
        // No token AND a bad body / content type: authentication is reported first.
        Map<String, String> headers = new HashMap<>(Map.of("content-type", "text/plain"));
        assertUnauthorized(Events.event("POST", Events.PATH, headers, "not json"));
    }

    // ---- request shape ----------------------------------------------------------------------------------------------

    @Test
    void wrongContentTypeIs415() throws Exception {
        for (String contentType : List.of("text/plain", "application/x-www-form-urlencoded", "application/json; charset=latin1",
                "application/jsonp")) {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Bearer " + tokens.accessToken(SUB));
            headers.put("content-type", contentType);
            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(Events.event("POST", Events.PATH, headers, Events.VALID_BODY), null);
            assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        }
        Map<String, String> noContentType = new HashMap<>(Map.of("authorization", "Bearer " + tokens.accessToken(SUB)));
        assertError(handler.handleRequest(Events.event("POST", Events.PATH, noContentType, Events.VALID_BODY), null),
                415, "UNSUPPORTED_MEDIA_TYPE");
        verifyNoInteractions(service);
    }

    @Test
    void jsonWithUtf8CharsetIsAccepted() {
        when(service.register(any(), any(), any())).thenReturn(registered());
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + tokens.accessToken(SUB));
        headers.put("content-type", "Application/JSON; charset=\"UTF-8\"");
        assertEquals(201, handler.handleRequest(Events.event("POST", Events.PATH, headers, Events.VALID_BODY), null)
                .getStatusCode());
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        String big = "{\"firstName\":\"" + "a".repeat(RegistrationHandler.MAX_BODY_BYTES) + "\"}";
        assertError(handler.handleRequest(Events.register(tokens.accessToken(SUB), big), null), 413, "PAYLOAD_TOO_LARGE");

        APIGatewayV2HTTPEvent encoded = Events.register(tokens.accessToken(SUB),
                Base64.getEncoder().encodeToString(big.getBytes(StandardCharsets.UTF_8)));
        encoded.setIsBase64Encoded(true);
        assertError(handler.handleRequest(encoded, null), 413, "PAYLOAD_TOO_LARGE");
        verifyNoInteractions(service);
    }

    @Test
    void base64EncodedBodiesAreDecoded() {
        when(service.register(any(), any(), any())).thenReturn(registered());
        APIGatewayV2HTTPEvent event = Events.register(tokens.accessToken(SUB),
                Base64.getEncoder().encodeToString(Events.VALID_BODY.getBytes(StandardCharsets.UTF_8)));
        event.setIsBase64Encoded(true);
        assertEquals(201, handler.handleRequest(event, null).getStatusCode());
    }

    @Test
    void emptyOrMalformedBodiesAre400() throws Exception {
        for (String body : new String[] {null, "", "   ", "{", "[]", "not json"}) {
            assertError(handler.handleRequest(Events.register(tokens.accessToken(SUB), body), null), 400,
                    "INVALID_REQUEST");
        }
        verifyNoInteractions(service);
    }

    @Test
    void routeAndMethodChecks() throws Exception {
        Map<String, String> headers = Map.of("content-type", "application/json");
        assertError(handler.handleRequest(Events.event("POST", "/api/v1/students/other", headers, "{}"), null),
                404, "NOT_FOUND");
        APIGatewayV2HTTPResponse get = handler.handleRequest(Events.event("GET", Events.PATH, headers, null), null);
        assertError(get, 405, "METHOD_NOT_ALLOWED");
        assertEquals("POST", get.getHeaders().get("Allow"));
        // Trailing slash is the same route.
        when(service.register(any(), any(), any())).thenReturn(registered());
        Map<String, String> auth = new HashMap<>(headers);
        auth.put("authorization", "Bearer " + tokens.accessToken(SUB));
        assertEquals(201, handler.handleRequest(Events.event("POST", Events.PATH + "/", auth, Events.VALID_BODY), null)
                .getStatusCode());
    }

    // ---- validation through the real service (nothing reaches Cognito or MySQL) ----------------------------------------

    @Test
    void validationErrorsAre400WithFieldErrorsAndNoDownstreamCalls() throws Exception {
        CognitoIdentityService identity = mock(CognitoIdentityService.class);
        DataSource dataSource = mock(DataSource.class);
        RegistrationConfig config = new RegistrationConfig("us-east-2", TestTokens.POOL_ID, TestTokens.CLIENT_ID,
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", true);
        RegistrationHandler real = new RegistrationHandler(verifier, new StudentRegistrationService(dataSource,
                new RegistrationValidator(), identity, new UserRepository(), new StudentRepository(),
                new UserIdentityRepository(), config, Clock.systemUTC()));

        Map<String, String> cases = Map.of(
                "{\"lastName\":\"Smith\",\"phoneNumber\":\"+17135550101\",\"acceptedTerms\":true}", "firstName",
                "{\"firstName\":\"Alex\",\"phoneNumber\":\"+17135550101\",\"acceptedTerms\":true}", "lastName",
                "{\"firstName\":\"Alex\",\"lastName\":\"Smith\",\"phoneNumber\":\"12345\",\"acceptedTerms\":true}", "phoneNumber",
                "{\"firstName\":\"Alex\",\"lastName\":\"Smith\",\"phoneNumber\":\"+12345678901234567\",\"acceptedTerms\":true}", "phoneNumber",
                "{\"firstName\":\"Alex\",\"lastName\":\"Smith\",\"phoneNumber\":\"+17135550101\",\"acceptedTerms\":false}", "acceptedTerms",
                "{\"firstName\":\"Alex\",\"lastName\":\"Smith\",\"phoneNumber\":\"+17135550101\"}", "acceptedTerms");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            APIGatewayV2HTTPResponse response = real.handleRequest(Events.register(tokens.accessToken(SUB), c.getKey()), null);
            JsonNode body = assertError(response, 400, "VALIDATION_FAILED");
            assertEquals("Please provide valid registration details.", body.get("message").asText());
            assertEquals(c.getValue(), body.get("fieldErrors").get(0).get("field").asText(), c.getKey());
        }
        verifyNoInteractions(identity, dataSource);
    }

    // ---- error sanitization -------------------------------------------------------------------------------------------

    @Test
    void serverErrorsAreSanitizedAndCarryTheRequestId() throws Exception {
        when(service.register(any(), any(), any())).thenThrow(RegistrationException.serviceUnavailable(
                new java.sql.SQLTransientConnectionException("jdbc:mysql://db.internal:3306 user=root password=x")));

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.register(tokens.accessToken(SUB), Events.VALID_BODY), null);

        JsonNode body = assertError(response, 503, "SERVICE_UNAVAILABLE");
        assertEquals("req-test-1", body.get("requestId").asText());
        assertFalse(response.getBody().contains("jdbc"));
        assertFalse(response.getBody().contains("password"));
        assertFalse(response.getBody().contains("SQL"));
    }

    @Test
    void unexpectedExceptionsAreAGeneric500() throws Exception {
        when(service.register(any(), any(), any())).thenThrow(new IllegalStateException("secret internals"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.register(tokens.accessToken(SUB), Events.VALID_BODY), null);

        JsonNode body = assertError(response, 500, "INTERNAL_ERROR");
        assertEquals("An unexpected error occurred.", body.get("message").asText());
        assertFalse(response.getBody().contains("secret internals"));
    }

    private void assertUnauthorized(APIGatewayV2HTTPEvent event) throws Exception {
        JsonNode body = assertError(handler.handleRequest(event, null), 401, "UNAUTHORIZED");
        assertEquals("Authentication is required.", body.get("message").asText());
        verifyNoInteractions(service);
    }

    private static JsonNode assertError(APIGatewayV2HTTPResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatusCode(), response.getBody());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode body = JSON.readTree(response.getBody());
        assertFalse(body.get("success").asBoolean());
        assertEquals(code, body.get("code").asText());
        assertTrue(body.hasNonNull("requestId"));
        assertFalse(body.has("data"));
        return body;
    }
}
