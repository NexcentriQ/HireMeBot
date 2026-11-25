package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.IdentityProvider;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.CognitoUserInfo;
import org.hmb.security.CognitoUserRecord;
import org.hmb.security.FederatedUserLookup;
import org.hmb.security.UserInfoClient;
import org.hmb.security.VerifiedAccessToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoIdentityServiceTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final String USERNAME = "google_1093";
    private static final VerifiedAccessToken TOKEN = new VerifiedAccessToken(SUB, USERNAME, "client",
            Set.of("openid", "email"), Instant.now().plusSeconds(600));
    private static final BearerToken BEARER = new BearerToken("raw.token.value");

    private final UserInfoClient userInfo = mock(UserInfoClient.class);
    private final FederatedUserLookup users = mock(FederatedUserLookup.class);
    private final CognitoIdentityService service = new CognitoIdentityService(userInfo, users);

    @Test
    void resolvesAVerifiedGoogleUserWithANormalizedEmail() {
        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo(SUB, "  Alex.Smith@Gmail.COM ", true));
        when(users.lookup(USERNAME)).thenReturn(google(SUB, true));

        AuthenticatedIdentity identity = service.resolve(TOKEN, BEARER);

        assertEquals(SUB, identity.cognitoSubject());
        assertEquals("alex.smith@gmail.com", identity.email());
        assertEquals(IdentityProvider.GOOGLE, identity.provider());
    }

    @Test
    void userInfoSubjectMustMatchTheVerifiedToken() {
        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo("someone-else", "alex@gmail.com", true));

        assertCode("UNAUTHORIZED", 401);
        verify(users, never()).lookup(USERNAME);
    }

    @Test
    void unverifiedOrMissingEmailIsRefusedBeforeAnyAdminLookup() {
        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo(SUB, "alex@gmail.com", false));
        assertCode("EMAIL_NOT_VERIFIED", 403);

        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo(SUB, null, true));
        assertCode("EMAIL_NOT_VERIFIED", 403);

        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo(SUB, "not-an-email", true));
        assertCode("EMAIL_NOT_VERIFIED", 403);

        verify(users, never()).lookup(USERNAME);
    }

    @Test
    void aNativeCognitoUserIsRefusedEvenWithAVerifiedGmailAddress() {
        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo(SUB, "someone@gmail.com", true));
        when(users.lookup(USERNAME)).thenReturn(new CognitoUserRecord(SUB, true, "CONFIRMED", Set.of()));

        assertCode("GOOGLE_ACCOUNT_REQUIRED", 403);
    }

    @Test
    void adminLookupMustDescribeTheSameEnabledUser() {
        when(userInfo.fetch(BEARER)).thenReturn(new CognitoUserInfo(SUB, "alex@gmail.com", true));

        when(users.lookup(USERNAME)).thenReturn(google("another-sub", true));
        assertCode("UNAUTHORIZED", 401);

        when(users.lookup(USERNAME)).thenReturn(google(SUB, false));
        assertCode("UNAUTHORIZED", 401);
    }

    @Test
    void emailNormalization() {
        assertEquals("a.b@example.com", CognitoIdentityService.normalizeEmail(" A.B@Example.com "));
        assertNull(CognitoIdentityService.normalizeEmail(""));
        assertNull(CognitoIdentityService.normalizeEmail("a b@example.com"));
        assertNull(CognitoIdentityService.normalizeEmail("a@b"));
        assertNull(CognitoIdentityService.normalizeEmail("a".repeat(250) + "@x.com"));
    }

    private static CognitoUserRecord google(String sub, boolean enabled) {
        return new CognitoUserRecord(sub, enabled, "EXTERNAL_PROVIDER", Set.of("Google"));
    }

    private void assertCode(String code, int status) {
        RegistrationException e = assertThrows(RegistrationException.class, () -> service.resolve(TOKEN, BEARER));
        assertEquals(code, e.code());
        assertEquals(status, e.status());
    }
}
