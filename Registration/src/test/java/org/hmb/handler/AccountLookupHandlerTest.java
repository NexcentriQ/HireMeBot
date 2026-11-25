package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.dto.AccountLookupResponse;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NextStep;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.AccountLookupService;
import org.hmb.service.StudentRegistrationService;
import org.hmb.support.Events;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
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

/** {@code GET /api/v1/auth/me} through the real handler and the real JWT verifier (lookup service mocked). */
class AccountLookupHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";

    private final TestTokens tokens = new TestTokens();
    private final CognitoAccessTokenVerifier verifier =
            new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID, tokens.jwkSource(), Clock.systemUTC());
    private final StudentRegistrationService registration = mock(StudentRegistrationService.class);
    private final AccountLookupService lookup = mock(AccountLookupService.class);
    private final RegistrationHandler handler = new RegistrationHandler(verifier, registration, lookup);

    private static AccountProfile active() {
        return new AccountProfile(1_000_000_000L, "Alex", "Smith", "alex@gmail.com", "+17135550101", true, false,
                AccountStatus.ACTIVE, false, true, IdentityProvider.GOOGLE);
    }

    @Test
    void anExistingActiveStudentGetsDashboardAndTheirProfile() throws Exception {
        when(lookup.lookup(any(), any())).thenReturn(AccountLookupResponse.registered(NextStep.DASHBOARD, active()));

        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.me(tokens.accessToken(SUB)), null);

        assertEquals(200, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        JsonNode body = JSON.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertTrue(body.get("registered").asBoolean());
        assertEquals("DASHBOARD", body.get("nextStep").asText());
        assertFalse(body.has("message"));
        JsonNode user = body.get("user");
        assertEquals(1_000_000_000L, user.get("userId").asLong());
        assertEquals(1_000_000_000L, user.get("studentId").asLong());
        assertEquals("Alex", user.get("firstName").asText());
        assertEquals("Smith", user.get("lastName").asText());
        assertEquals("alex@gmail.com", user.get("email").asText());
        assertEquals("+17135550101", user.get("phoneNumber").asText());
        assertTrue(user.get("emailVerified").asBoolean());
        assertFalse(user.get("phoneVerified").asBoolean());
        assertEquals("ACTIVE", user.get("accountStatus").asText());
        assertEquals("STUDENT", user.get("accountType").asText());
        // Never echo credentials or the Cognito subject.
        assertFalse(response.getBody().contains(SUB));
        assertFalse(response.getBody().toLowerCase().contains("token"));
    }

    @Test
    void aNewGoogleUserGetsSignupFormWithoutUserData() throws Exception {
        when(lookup.lookup(any(), any())).thenReturn(AccountLookupResponse.signupRequired());

        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.me(tokens.accessToken(SUB)), null);

        assertEquals(200, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertTrue(body.get("success").asBoolean());
        assertFalse(body.get("registered").asBoolean());
        assertEquals("SIGNUP_FORM", body.get("nextStep").asText());
        assertEquals("Complete your HireMeBot student registration.", body.get("message").asText());
        assertFalse(body.has("user"));
    }

    @Test
    void aPendingStudentGetsAccountPending() throws Exception {
        AccountProfile pending = new AccountProfile(1_000_000_000L, "Alex", "Smith", "alex@gmail.com",
                "+17135550101", true, false, AccountStatus.PENDING, false, true, IdentityProvider.GOOGLE);
        when(lookup.lookup(any(), any())).thenReturn(AccountLookupResponse.registered(NextStep.ACCOUNT_PENDING, pending));

        JsonNode body = JSON.readTree(handler.handleRequest(Events.me(tokens.accessToken(SUB)), null).getBody());

        assertTrue(body.get("registered").asBoolean());
        assertEquals("ACCOUNT_PENDING", body.get("nextStep").asText());
        assertEquals("PENDING", body.get("user").get("accountStatus").asText());
    }

    @Test
    void restrictedAccountsAreErrorsInTheStandardShape() throws Exception {
        assertError(RegistrationException.accountSuspended(), 403, "ACCOUNT_SUSPENDED", "Your account is currently suspended.");
        assertError(RegistrationException.accountDeleted(), 403, "ACCOUNT_DELETED", "This account is unavailable.");
        assertError(RegistrationException.accountTypeMismatch(), 403, "ACCOUNT_TYPE_MISMATCH", null);
        assertError(RegistrationException.identityConflict(), 409, "ACCOUNT_IDENTITY_CONFLICT",
                "An existing account requires a different sign-in or account recovery method.");
        assertError(RegistrationException.googleAccountRequired(), 403, "GOOGLE_ACCOUNT_REQUIRED", null);
        assertError(RegistrationException.emailNotVerified(), 403, "EMAIL_NOT_VERIFIED", null);
        assertError(RegistrationException.serviceUnavailable(null), 503, "SERVICE_UNAVAILABLE", null);
    }

    @Test
    void theLookupUsesOnlyTheVerifiedTokenAndIgnoresQueryParameters() {
        when(lookup.lookup(any(), any())).thenReturn(AccountLookupResponse.signupRequired());
        APIGatewayV2HTTPEvent event = Events.me(tokens.accessToken(SUB));
        event.setQueryStringParameters(Map.of("email", "someone@else.com", "userId", "1", "sub", "forged"));
        event.setRawQueryString("email=someone@else.com&userId=1&sub=forged");

        assertEquals(200, handler.handleRequest(event, null).getStatusCode());

        ArgumentCaptor<VerifiedAccessToken> token = ArgumentCaptor.forClass(VerifiedAccessToken.class);
        verify(lookup).lookup(token.capture(), any());
        assertEquals(SUB, token.getValue().subject());
    }

    @Test
    void everyInvalidTokenIs401AndNothingIsLookedUp() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        TestTokens otherPool = new TestTokens();
        String valid = tokens.accessToken(SUB);
        String[] parts = valid.split("\\.");
        String[] other = tokens.accessToken("11111111-2222-3333-4444-555555555555").split("\\.");
        List<String> invalid = List.of(
                "not-a-jwt",
                tokens.sign(tokens.accessClaims(SUB).issueTime(Date.from(past))                       // expired
                        .expirationTime(Date.from(past.plusSeconds(3600))).build()),
                tokens.sign(tokens.accessClaims(SUB)                                                     // wrong issuer
                        .issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_OtherPool1").build()),
                otherPool.accessToken(SUB),                                                              // foreign key
                tokens.sign(tokens.accessClaims(SUB).claim("client_id", "wrongclient00000000000000").build()),
                tokens.sign(tokens.idClaims(SUB, "alex@gmail.com").build()),                            // ID token
                TestTokens.unsigned(tokens.accessClaims(SUB).build()),                                  // alg none
                tokens.hmacWithPublicKey(tokens.accessClaims(SUB).build()),                              // HS256 forgery
                parts[0] + "." + other[1] + "." + parts[2]);                                             // tampered
        for (String token : invalid) {
            assertUnauthorized(Events.me(token));
        }
        assertUnauthorized(Events.me(null));
        Map<String, String> basic = new HashMap<>(Map.of("authorization", "Basic dXNlcjpwYXNz"));
        assertUnauthorized(Events.event("GET", Events.ME_PATH, basic, null));
        verifyNoInteractions(lookup, registration);
    }

    @Test
    void routesAndMethods() throws Exception {
        APIGatewayV2HTTPResponse postMe = handler.handleRequest(
                Events.event("POST", Events.ME_PATH, Map.of("authorization", "Bearer x"), "{}"), null);
        assertEquals(405, postMe.getStatusCode());
        assertEquals("GET", postMe.getHeaders().get("Allow"));

        APIGatewayV2HTTPResponse getRegister = handler.handleRequest(
                Events.event("GET", Events.PATH, Map.of(), null), null);
        assertEquals(405, getRegister.getStatusCode());
        assertEquals("POST", getRegister.getHeaders().get("Allow"));

        assertEquals(404, handler.handleRequest(Events.event("GET", "/api/v1/auth/other", Map.of(), null), null)
                .getStatusCode());

        // Trailing slash is the same route; no Content-Type is needed for GET.
        when(lookup.lookup(any(), any())).thenReturn(AccountLookupResponse.signupRequired());
        APIGatewayV2HTTPEvent slash = Events.event("GET", Events.ME_PATH + "/",
                Map.of("authorization", "Bearer " + tokens.accessToken(SUB)), null);
        assertEquals(200, handler.handleRequest(slash, null).getStatusCode());
        verifyNoInteractions(registration);
    }

    private void assertError(RegistrationException thrown, int status, String code, String message) throws Exception {
        doThrow(thrown).when(lookup).lookup(any(), any());
        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.me(tokens.accessToken(SUB)), null);
        assertEquals(status, response.getStatusCode());
        JsonNode body = JSON.readTree(response.getBody());
        assertFalse(body.get("success").asBoolean());
        assertEquals(code, body.get("code").asText());
        assertEquals("req-test-1", body.get("requestId").asText());
        assertFalse(body.has("nextStep"), "a restricted account must never get a nextStep such as SIGNUP_FORM");
        if (message != null) {
            assertEquals(message, body.get("message").asText());
        }
    }

    private void assertUnauthorized(APIGatewayV2HTTPEvent event) throws Exception {
        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);
        assertEquals(401, response.getStatusCode());
        assertEquals("UNAUTHORIZED", JSON.readTree(response.getBody()).get("code").asText());
    }
}
