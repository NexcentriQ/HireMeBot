package org.hmb.security;

import org.hmb.Fixtures;
import org.hmb.dto.DocumentType;
import org.hmb.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The local-only auth rule, the signing-secret sources, and the signed upload-intent token. */
class SecurityTest {

    private static final Clock CLOCK = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC);

    private static UploadTokenService tokens(String secret, Clock clock) {
        return new UploadTokenService(new SigningSecretProvider.FixedProvider(secret), clock);
    }

    // ---- dev auth is local-only ---------------------------------------------------------------------------------

    @Test
    void devAuthNeedsBothTheFlagAndSamLocal() {
        assertTrue(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "true")));
        // Flag set in a real deployed Lambda (no AWS_SAM_LOCAL): still refused.
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true")));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "false")));
        // Running under sam local but the flag is off: refused.
        assertFalse(DevAuth.permitted(Map.of("AWS_SAM_LOCAL", "true")));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "false", "AWS_SAM_LOCAL", "true")));
        assertFalse(DevAuth.permitted(Map.of()));
    }

    // ---- signing secret -----------------------------------------------------------------------------------------

    @Test
    void theDevSecretIsUsedOnlyWhenDevAuthIsPermitted() {
        Map<String, String> local = Map.of("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "true",
                "UPLOAD_TOKEN_SECRET_DEV", Fixtures.SECRET);
        assertArrayEquals(Fixtures.SECRET.getBytes(StandardCharsets.UTF_8),
                SigningSecretProvider.fromEnvironment(local).secret());

        // The same secret, but not under sam local (i.e. a deployed Lambda): never honoured.
        Map<String, String> deployed = Map.of("ALLOW_DEV_RECRUITER_HEADER", "true",
                "UPLOAD_TOKEN_SECRET_DEV", Fixtures.SECRET);
        assertThrows(IllegalStateException.class, () -> SigningSecretProvider.fromEnvironment(deployed).secret());
    }

    @Test
    void noSecretSourceFailsClosedWithoutRevealingAnything() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SigningSecretProvider.fromEnvironment(Map.of()).secret());

        assertFalse(e.getMessage().contains(Fixtures.SECRET));
    }

    @Test
    void aWeakSecretCanNeverSignAToken() {
        assertThrows(IllegalStateException.class, () -> new SigningSecretProvider.FixedProvider("too-short"));
    }

    // ---- token --------------------------------------------------------------------------------------------------

    @Test
    void aTokenRoundTripsEveryClaim() {
        UploadTokenService service = tokens(Fixtures.SECRET, CLOCK);

        UploadIntent verified = service.verify(service.sign(Fixtures.intent()));

        assertEquals(Fixtures.intent(), verified);
        assertEquals(DocumentType.RESUME, verified.documentType());
    }

    @Test
    void theTokenDoesNotContainTheSecret() {
        String token = tokens(Fixtures.SECRET, CLOCK).sign(Fixtures.intent());

        assertFalse(token.contains(Fixtures.SECRET));
        assertEquals(2, token.split("\\.").length);
    }

    @Test
    void anAlteredPayloadIsRejected() {
        UploadTokenService service = tokens(Fixtures.SECRET, CLOCK);
        String token = service.sign(Fixtures.intent());
        String[] parts = token.split("\\.");
        // Re-encode the payload with a different student id but keep the original signature.
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                .replace("\"s\":1000000005", "\"s\":1000000099");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[1];

        ApiException e = assertThrows(ApiException.class, () -> service.verify(forged));

        assertEquals(403, e.getStatusCode());
        assertEquals("UPLOAD_TOKEN_INVALID", e.getErrorCode());
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String token = tokens("another-secret-another-secret-123456", CLOCK).sign(Fixtures.intent());

        ApiException e = assertThrows(ApiException.class, () -> tokens(Fixtures.SECRET, CLOCK).verify(token));

        assertEquals("UPLOAD_TOKEN_INVALID", e.getErrorCode());
    }

    @Test
    void anAlteredSignatureIsRejected() {
        UploadTokenService service = tokens(Fixtures.SECRET, CLOCK);
        String token = service.sign(Fixtures.intent());
        String flipped = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertEquals("UPLOAD_TOKEN_INVALID", assertThrows(ApiException.class, () -> service.verify(flipped)).getErrorCode());
    }

    @Test
    void anExpiredTokenIsDistinguishedSoTheClientCanRequestAFreshUrl() {
        String token = tokens(Fixtures.SECRET, CLOCK).sign(Fixtures.intent());
        Clock later = Clock.offset(CLOCK, Duration.ofSeconds(1801));

        ApiException e = assertThrows(ApiException.class, () -> tokens(Fixtures.SECRET, later).verify(token));

        assertEquals(400, e.getStatusCode());
        assertEquals("UPLOAD_TOKEN_EXPIRED", e.getErrorCode());
    }

    @Test
    void aTokenIsValidUntilItsExpiryInstant() {
        UploadTokenService service = tokens(Fixtures.SECRET, CLOCK);
        String token = service.sign(Fixtures.intent());

        // one second before expiry: fine
        assertEquals(Fixtures.intent(),
                tokens(Fixtures.SECRET, Clock.offset(CLOCK, Duration.ofSeconds(1799))).verify(token));
        // at the expiry instant: refused
        assertEquals("UPLOAD_TOKEN_EXPIRED", assertThrows(ApiException.class,
                () -> tokens(Fixtures.SECRET, Clock.offset(CLOCK, Duration.ofSeconds(1800))).verify(token)).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "nodots", "a.b.c", ".", "abc.", ".abc", "!!!.@@@", "e30.e30"})
    void malformedTokensAreRejectedAsInvalid(String token) {
        ApiException e = assertThrows(ApiException.class, () -> tokens(Fixtures.SECRET, CLOCK).verify(token));

        assertEquals("UPLOAD_TOKEN_INVALID", e.getErrorCode());
    }

    @Test
    void aNullOrOversizedTokenIsRejected() {
        UploadTokenService service = tokens(Fixtures.SECRET, CLOCK);

        assertEquals("UPLOAD_TOKEN_INVALID", assertThrows(ApiException.class, () -> service.verify(null)).getErrorCode());
        assertEquals("UPLOAD_TOKEN_INVALID",
                assertThrows(ApiException.class, () -> service.verify("a".repeat(5000))).getErrorCode());
    }
}
