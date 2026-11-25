package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.dto.RecruiterAccountLookupResponse;
import org.hmb.dto.RecruiterRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;
import org.hmb.security.BearerToken;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.AccountLookupService;
import org.hmb.service.RecruiterAccountLookupService;
import org.hmb.service.RecruiterRegistrationService;
import org.hmb.service.StudentRegistrationService;
import org.hmb.support.Events;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The two recruiter routes through the real handler and the real JWT verifier (services mocked). */
class RecruiterHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";

    private final TestTokens tokens = new TestTokens();
    private final CognitoAccessTokenVerifier verifier =
            new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID, tokens.jwkSource(), Clock.systemUTC());
    private final StudentRegistrationService studentRegistration = mock(StudentRegistrationService.class);
    private final AccountLookupService studentLookup = mock(AccountLookupService.class);
    private final RecruiterRegistrationService registration = mock(RecruiterRegistrationService.class);
    private final RecruiterAccountLookupService lookup = mock(RecruiterAccountLookupService.class);
    private final RegistrationHandler handler =
            new RegistrationHandler(verifier, studentRegistration, studentLookup, registration, lookup);

    private static AccountProfile profile(AccountStatus status) {
        return new AccountProfile(1_000_000_001L, "Alex", "Smith", "recruiter@gmail.com", "+17135550101", true, false,
                status, false, false, true, false, IdentityProvider.GOOGLE);
    }

    private static RecruiterRegistrationData submitted() {
        return new RecruiterRegistrationData(1_000_000_001L, 1_000_000_001L, "Alex", "Smith", "recruiter@gmail.com",
                "+17135550101", true, false, AccountStatus.PENDING, "RECRUITER", RecruiterNextStep.APPROVAL_PENDING);
    }

    // ---- GET /api/v1/recruiters/auth/me --------------------------------------------------------------------------

    @Test
    void anActiveRecruiterGetsTheDashboardAndTheFullProfile() throws Exception {
        when(lookup.lookup(any(), any())).thenReturn(RecruiterAccountLookupResponse.dashboard(profile(AccountStatus.ACTIVE)));

        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.recruiterMe(tokens.accessToken(SUB)), null);

        assertEquals(200, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        JsonNode body = JSON.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("registered").asBoolean());
        assertEquals("RECRUITER_DASHBOARD", body.get("nextStep").asText());
        assertFalse(body.has("message"));
        JsonNode user = body.get("user");
        assertEquals(1_000_000_001L, user.get("userId").asLong());
        assertEquals(1_000_000_001L, user.get("recruiterId").asLong());
        assertEquals("Alex", user.get("firstName").asText());
        assertEquals("Smith", user.get("lastName").asText());
        assertEquals("recruiter@gmail.com", user.get("email").asText());
        assertEquals("+17135550101", user.get("phoneNumber").asText());
        assertTrue(user.get("emailVerified").asBoolean());
        assertFalse(user.get("phoneVerified").asBoolean());
        assertEquals("ACTIVE", user.get("accountStatus").asText());
        assertEquals("RECRUITER", user.get("accountType").asText());
        assertFalse(user.has("managerId"));
        assertFalse(response.getBody().contains(SUB), "the Cognito subject is never echoed");
        assertFalse(response.getBody().toLowerCase().contains("token"));
    }

    @Test
    void aPendingRecruiterGetsApprovalPendingNotTheDashboard() throws Exception {
        when(lookup.lookup(any(), any())).thenReturn(RecruiterAccountLookupResponse.pending(profile(AccountStatus.PENDING)));

        JsonNode body = JSON.readTree(handler.handleRequest(Events.recruiterMe(tokens.accessToken(SUB)), null).getBody());

        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("registered").asBoolean());
        assertEquals("APPROVAL_PENDING", body.get("nextStep").asText());
        assertEquals("Your recruiter account is waiting for approval.", body.get("message").asText());
        List<String> userFields = new ArrayList<>();
        body.get("user").fieldNames().forEachRemaining(userFields::add);
        // A pending recruiter gets exactly these four fields: no name, email, phone or verification flags yet.
        assertEquals(java.util.Set.of("userId", "recruiterId", "accountStatus", "accountType"),
                new java.util.HashSet<>(userFields));
        assertEquals("PENDING", body.get("user").get("accountStatus").asText());
        assertEquals("RECRUITER", body.get("user").get("accountType").asText());
    }

    @Test
    void aNewGoogleUserGetsTheRecruiterSignupFormWithoutUserData() throws Exception {
        when(lookup.lookup(any(), any())).thenReturn(RecruiterAccountLookupResponse.signupRequired());

        JsonNode body = JSON.readTree(handler.handleRequest(Events.recruiterMe(tokens.accessToken(SUB)), null).getBody());

        assertTrue(body.get("success").asBoolean());
        assertFalse(body.get("registered").asBoolean());
        assertEquals("RECRUITER_SIGNUP_FORM", body.get("nextStep").asText());
        assertEquals("Complete your HireMeBot recruiter registration.", body.get("message").asText());
        assertFalse(body.has("user"));
    }

    @Test
    void restrictedAccountsAreErrorsInTheStandardShapeWithoutANextStep() throws Exception {
        assertLookupError(RegistrationException.recruiterAccountSuspended(), 403, "ACCOUNT_SUSPENDED",
                "Your recruiter account has been suspended.");
        assertLookupError(RegistrationException.accountDeleted(), 403, "ACCOUNT_DELETED", "This account is unavailable.");
        assertLookupError(RegistrationException.recruiterTypeMismatch(), 403, "ACCOUNT_TYPE_MISMATCH",
                "This Google account is already associated with another HireMeBot account type.");
        assertLookupError(RegistrationException.recruiterIdentityConflict(), 409, "ACCOUNT_IDENTITY_CONFLICT",
                "This email is already associated with another HireMeBot account.");
        assertLookupError(RegistrationException.recruiterGoogleAccountRequired(), 403, "GOOGLE_ACCOUNT_REQUIRED", null);
        assertLookupError(RegistrationException.emailNotVerified(), 403, "EMAIL_NOT_VERIFIED", null);
        assertLookupError(RegistrationException.serviceUnavailable(null), 503, "SERVICE_UNAVAILABLE", null);
    }

    @Test
    void theLookupUsesOnlyTheVerifiedTokenAndIgnoresQueryParameters() {
        when(lookup.lookup(any(), any())).thenReturn(RecruiterAccountLookupResponse.signupRequired());
        APIGatewayV2HTTPEvent event = Events.recruiterMe(tokens.accessToken(SUB));
        event.setQueryStringParameters(Map.of("email", "someone@else.com", "userId", "1", "sub", "forged",
                "accountStatus", "ACTIVE"));
        event.setRawQueryString("email=someone@else.com&userId=1&sub=forged&accountStatus=ACTIVE");

        assertEquals(200, handler.handleRequest(event, null).getStatusCode());

        ArgumentCaptor<VerifiedAccessToken> token = ArgumentCaptor.forClass(VerifiedAccessToken.class);
        verify(lookup).lookup(token.capture(), any());
        assertEquals(SUB, token.getValue().subject());
    }

    // ---- POST /api/v1/recruiters/register ------------------------------------------------------------------------

    @Test
    void registrationReturns201WithTheContractAndNeverADashboardStep() throws Exception {
        when(registration.register(any(), any(), any())).thenReturn(submitted());

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.recruiterRegister(tokens.accessToken(SUB), Events.VALID_BODY), null);

        assertEquals(201, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        JsonNode body = JSON.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertEquals("RECRUITER_REGISTRATION_SUBMITTED", body.get("code").asText());
        assertEquals("Your recruiter registration is pending approval.", body.get("message").asText());
        JsonNode data = body.get("data");
        assertEquals(1_000_000_001L, data.get("userId").asLong());
        assertEquals(1_000_000_001L, data.get("recruiterId").asLong());
        assertEquals("Alex", data.get("firstName").asText());
        assertEquals("Smith", data.get("lastName").asText());
        assertEquals("recruiter@gmail.com", data.get("email").asText());
        assertEquals("+17135550101", data.get("phoneNumber").asText());
        assertTrue(data.get("emailVerified").asBoolean());
        assertFalse(data.get("phoneVerified").asBoolean());
        assertEquals("PENDING", data.get("accountStatus").asText());
        assertEquals("RECRUITER", data.get("accountType").asText());
        assertEquals("APPROVAL_PENDING", data.get("nextStep").asText());
        assertFalse(response.getBody().contains("RECRUITER_DASHBOARD"));
        verifyNoInteractions(studentRegistration, studentLookup);
    }

    @Test
    void clientSuppliedIdentityStatusAndOtpFieldsAreIgnoredAndOnlyLoggedByName() {
        when(registration.register(any(), any(), any())).thenReturn(submitted());
        String body = """
                {"firstName":"Alex","lastName":"Smith","phoneNumber":"+17135550101","acceptedTerms":true,
                 "email":"someone-else@gmail.com","cognitoSub":"forged","userId":1,"managerId":1000000003,
                 "accountStatus":"ACTIVE","emailVerified":true,"phoneVerified":true,"otpVerified":true,"otp":"000000"}""";

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(Events.recruiterRegister(tokens.accessToken(SUB), body), null);

        assertEquals(201, response.getStatusCode());
        ArgumentCaptor<StudentRegistrationRequest> request = ArgumentCaptor.forClass(StudentRegistrationRequest.class);
        verify(registration).register(any(), any(), request.capture());
        assertTrue(request.getValue().ignoredFields().containsAll(
                List.of("email", "cognitoSub", "userId", "managerId", "accountStatus", "phoneVerified", "otpVerified")));
        assertEquals("Alex", request.getValue().firstName());
    }

    @Test
    void registrationPassesTheVerifiedSubjectAndRawTokenToTheService() {
        when(registration.register(any(), any(), any())).thenReturn(submitted());
        String token = tokens.accessToken(SUB);

        handler.handleRequest(Events.recruiterRegister(token, Events.VALID_BODY), null);

        ArgumentCaptor<VerifiedAccessToken> verified = ArgumentCaptor.forClass(VerifiedAccessToken.class);
        ArgumentCaptor<BearerToken> bearer = ArgumentCaptor.forClass(BearerToken.class);
        verify(registration).register(verified.capture(), bearer.capture(), any());
        assertEquals(SUB, verified.getValue().subject());
        assertEquals(token, bearer.getValue().value());
    }

    @Test
    void registrationErrorsUseTheStandardEnvelopeWithRecruiterWording() throws Exception {
        assertRegisterError(RegistrationException.alreadyRegistered(), 409, "ACCOUNT_ALREADY_REGISTERED");
        assertRegisterError(RegistrationException.recruiterIdentityConflict(), 409, "ACCOUNT_IDENTITY_CONFLICT");
        assertRegisterError(RegistrationException.nameAlreadyRegistered(), 409, "NAME_ALREADY_REGISTERED");
        assertRegisterError(RegistrationException.recruiterTypeMismatch(), 403, "ACCOUNT_TYPE_MISMATCH");
        assertRegisterError(RegistrationException.recruiterAccountSuspended(), 403, "ACCOUNT_SUSPENDED");
        assertRegisterError(RegistrationException.accountDeleted(), 403, "ACCOUNT_DELETED");
        assertRegisterError(RegistrationException.emailNotVerified(), 403, "EMAIL_NOT_VERIFIED");
        assertRegisterError(RegistrationException.recruiterGoogleAccountRequired(), 403, "GOOGLE_ACCOUNT_REQUIRED");
        assertRegisterError(RegistrationException.serviceUnavailable(
                new java.sql.SQLTransientConnectionException("jdbc:mysql://db.internal user=root password=x")), 503,
                "SERVICE_UNAVAILABLE");
    }

    @Test
    void unexpectedExceptionsAreAGenericSanitized500() throws Exception {
        doThrow(new IllegalStateException("secret internals")).when(registration).register(any(), any(), any());

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.recruiterRegister(tokens.accessToken(SUB), Events.VALID_BODY), null);

        assertEquals(500, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", JSON.readTree(response.getBody()).get("code").asText());
        assertFalse(response.getBody().contains("secret internals"));
    }

    @Test
    void requestShapeChecksMatchTheStudentRegistration() throws Exception {
        for (String contentType : List.of("text/plain", "application/x-www-form-urlencoded",
                "application/json; charset=latin1")) {
            Map<String, String> headers = new HashMap<>();
            headers.put("authorization", "Bearer " + tokens.accessToken(SUB));
            headers.put("content-type", contentType);
            assertStatus(handler.handleRequest(Events.event("POST", Events.RECRUITER_PATH, headers, Events.VALID_BODY), null),
                    415, "UNSUPPORTED_MEDIA_TYPE");
        }
        String big = "{\"firstName\":\"" + "a".repeat(RegistrationHandler.MAX_BODY_BYTES) + "\"}";
        assertStatus(handler.handleRequest(Events.recruiterRegister(tokens.accessToken(SUB), big), null), 413,
                "PAYLOAD_TOO_LARGE");
        for (String body : new String[] {null, "", "{", "[]", "not json", "{\"firstName\":\"A\",\"firstName\":\"B\"}"}) {
            assertStatus(handler.handleRequest(Events.recruiterRegister(tokens.accessToken(SUB), body), null), 400,
                    "INVALID_REQUEST");
        }
        assertStatus(handler.handleRequest(Events.recruiterRegister(tokens.accessToken(SUB),
                "{\"firstName\":123,\"acceptedTerms\":\"true\"}"), null), 400, "VALIDATION_FAILED");
        verifyNoInteractions(registration);
    }

    @Test
    void base64EncodedBodiesAreDecoded() {
        when(registration.register(any(), any(), any())).thenReturn(submitted());
        APIGatewayV2HTTPEvent event = Events.recruiterRegister(tokens.accessToken(SUB),
                Base64.getEncoder().encodeToString(Events.VALID_BODY.getBytes(StandardCharsets.UTF_8)));
        event.setIsBase64Encoded(true);
        assertEquals(201, handler.handleRequest(event, null).getStatusCode());
    }

    // ---- authentication on both routes: 401 and nothing else happens ---------------------------------------------

    @Test
    void everyInvalidTokenIs401OnBothRoutesAndNothingIsCalled() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        TestTokens otherPool = new TestTokens();
        String valid = tokens.accessToken(SUB);
        String[] parts = valid.split("\\.");
        String[] other = tokens.accessToken("11111111-2222-3333-4444-555555555555").split("\\.");
        List<String> invalid = List.of(
                "not-a-jwt",
                tokens.sign(tokens.accessClaims(SUB).issueTime(Date.from(past))                         // expired
                        .expirationTime(Date.from(past.plusSeconds(3600))).build()),
                tokens.sign(tokens.accessClaims(SUB)                                                      // wrong issuer
                        .issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_OtherPool1").build()),
                otherPool.accessToken(SUB),                                                               // foreign key
                tokens.sign(tokens.accessClaims(SUB).claim("client_id", "wrongclient00000000000000").build()), // client
                tokens.sign(tokens.idClaims(SUB, "recruiter@gmail.com").build()),                        // ID token
                TestTokens.unsigned(tokens.accessClaims(SUB).build()),                                   // alg none
                tokens.hmacWithPublicKey(tokens.accessClaims(SUB).build()),                               // HS256 forgery
                parts[0] + "." + other[1] + "." + parts[2]);                                              // tampered
        for (String token : invalid) {
            assertUnauthorized(Events.recruiterMe(token));
            assertUnauthorized(Events.recruiterRegister(token, Events.VALID_BODY));
        }
        assertUnauthorized(Events.recruiterMe(null));
        assertUnauthorized(Events.recruiterRegister(null, Events.VALID_BODY));
        Map<String, String> basic = new HashMap<>(Map.of("authorization", "Basic dXNlcjpwYXNz"));
        assertUnauthorized(Events.event("GET", Events.RECRUITER_ME_PATH, basic, null));
        verifyNoInteractions(lookup, registration);
    }

    @Test
    void authorizerClaimsForAnotherSubjectAre401() throws Exception {
        APIGatewayV2HTTPEvent event = Events.recruiterRegister(tokens.accessToken(SUB), Events.VALID_BODY);
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = new APIGatewayV2HTTPEvent.RequestContext.Authorizer();
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt = new APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT();
        jwt.setClaims(Map.of("sub", "forged-subject"));
        authorizer.setJwt(jwt);
        event.getRequestContext().setAuthorizer(authorizer);

        assertUnauthorized(event);
        verifyNoInteractions(registration);
    }

    // ---- routing -----------------------------------------------------------------------------------------------------

    @Test
    void routesAndMethods() throws Exception {
        APIGatewayV2HTTPResponse postMe = handler.handleRequest(
                Events.event("POST", Events.RECRUITER_ME_PATH, Map.of("authorization", "Bearer x"), "{}"), null);
        assertEquals(405, postMe.getStatusCode());
        assertEquals("GET", postMe.getHeaders().get("Allow"));

        APIGatewayV2HTTPResponse getRegister = handler.handleRequest(
                Events.event("GET", Events.RECRUITER_PATH, Map.of(), null), null);
        assertEquals(405, getRegister.getStatusCode());
        assertEquals("POST", getRegister.getHeaders().get("Allow"));

        assertEquals(404, handler.handleRequest(Events.event("GET", "/api/v1/recruiters/other", Map.of(), null), null)
                .getStatusCode());

        // Trailing slash is the same route.
        when(lookup.lookup(any(), any())).thenReturn(RecruiterAccountLookupResponse.signupRequired());
        APIGatewayV2HTTPEvent slash = Events.event("GET", Events.RECRUITER_ME_PATH + "/",
                Map.of("authorization", "Bearer " + tokens.accessToken(SUB)), null);
        assertEquals(200, handler.handleRequest(slash, null).getStatusCode());
    }

    @Test
    void theStudentRoutesAreUnaffected() {
        when(studentLookup.lookup(any(), any())).thenReturn(org.hmb.dto.AccountLookupResponse.signupRequired());
        assertEquals(200, handler.handleRequest(Events.me(tokens.accessToken(SUB)), null).getStatusCode());
        verifyNoInteractions(lookup, registration);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private void assertLookupError(RegistrationException thrown, int status, String code, String message) throws Exception {
        doThrow(thrown).when(lookup).lookup(any(), any());
        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.recruiterMe(tokens.accessToken(SUB)), null);
        assertEquals(status, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertFalse(body.get("success").asBoolean());
        assertEquals(code, body.get("code").asText());
        assertEquals("req-test-1", body.get("requestId").asText());
        assertFalse(body.has("nextStep"), "a restricted account must never get a nextStep such as RECRUITER_SIGNUP_FORM");
        if (message != null) {
            assertEquals(message, body.get("message").asText());
        }
    }

    private void assertRegisterError(RegistrationException thrown, int status, String code) throws Exception {
        doThrow(thrown).when(registration).register(any(), any(), any());
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.recruiterRegister(tokens.accessToken(SUB), Events.VALID_BODY), null);
        JsonNode body = assertStatus(response, status, code);
        assertEquals(thrown.getMessage(), body.get("message").asText());
        assertFalse(response.getBody().contains("jdbc"));
        assertFalse(response.getBody().contains("password"));
    }

    private static JsonNode assertStatus(APIGatewayV2HTTPResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatusCode(), response.getBody());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode body = JSON.readTree(response.getBody());
        assertFalse(body.get("success").asBoolean());
        assertEquals(code, body.get("code").asText());
        assertTrue(body.hasNonNull("requestId"));
        return body;
    }

    private void assertUnauthorized(APIGatewayV2HTTPEvent event) throws Exception {
        JsonNode body = assertStatus(handler.handleRequest(event, null), 401, "UNAUTHORIZED");
        assertEquals("Authentication is required.", body.get("message").asText());
    }
}
