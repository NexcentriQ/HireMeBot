package org.hmb.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import org.hmb.exception.RegistrationException;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import static org.hmb.support.TestTokens.CLIENT_ID;
import static org.hmb.support.TestTokens.ISSUER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoAccessTokenVerifierTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";

    private final TestTokens tokens = new TestTokens();
    private final CognitoAccessTokenVerifier verifier =
            new CognitoAccessTokenVerifier(ISSUER, CLIENT_ID, tokens.jwkSource(), Clock.systemUTC());

    @Test
    void acceptsAValidAccessToken() {
        VerifiedAccessToken verified = verifier.verify(tokens.bearer(SUB));

        assertEquals(SUB, verified.subject());
        assertEquals(TestTokens.googleUsername(SUB), verified.username());
        assertEquals(CLIENT_ID, verified.clientId());
        assertTrue(verified.scopes().contains("openid"));
        assertTrue(verified.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void rejectsMissingAndGarbageTokens() {
        assertUnauthorized(null);
        assertUnauthorized("");
        assertUnauthorized("not-a-jwt");
        assertUnauthorized("aaa.bbb.ccc");
        assertUnauthorized("x".repeat(CognitoAccessTokenVerifier.MAX_TOKEN_LENGTH + 1));
    }

    @Test
    void rejectsAnExpiredToken() {
        Instant past = Instant.now().minusSeconds(3600);
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB)
                .issueTime(Date.from(past.minusSeconds(3600)))
                .expirationTime(Date.from(past))
                .build()));
    }

    @Test
    void rejectsATokenFromAnotherUserPool() {
        // Signed by a different pool's key and naming a different issuer.
        TestTokens otherPool = new TestTokens();
        assertUnauthorized(otherPool.sign(otherPool.accessClaims(SUB)
                .issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_OtherPool1")
                .build()));
        // Right key, wrong issuer: still refused.
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB)
                .issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_OtherPool1")
                .build()));
    }

    @Test
    void rejectsATokenForAnotherAppClient() {
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("client_id", "someotherclientid000000000").build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("client_id", null).build()));
    }

    @Test
    void rejectsAnIdTokenEvenThoughItIsValidlySigned() {
        assertUnauthorized(tokens.sign(tokens.idClaims(SUB, "alex@gmail.com").build()));
        // An ID token with client_id and username added is still refused on token_use.
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("token_use", "id").build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("token_use", null).build()));
    }

    @Test
    void rejectsABadSignatureAndAnUnknownKey() {
        String valid = tokens.accessToken(SUB);
        String[] parts = valid.split("\\.");
        // Payload swapped for another subject's payload: signature no longer matches.
        String[] other = tokens.accessToken("11111111-2222-3333-4444-555555555555").split("\\.");
        assertUnauthorized(parts[0] + "." + other[1] + "." + parts[2]);
        // Signed by a key that is not in the JWKS.
        assertUnauthorized(new TestTokens().accessToken(SUB));
    }

    @Test
    void rejectsUnsignedAndHmacTokens() {
        JWTClaimsSet claims = tokens.accessClaims(SUB).build();
        assertUnauthorized(TestTokens.unsigned(claims));
        assertUnauthorized(tokens.hmacWithPublicKey(claims));
    }

    @Test
    void rejectsMissingOrMalformedRequiredClaims() {
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).subject(null).build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims("bad sub with spaces").build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims("a".repeat(129)).build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("username", null).build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("username", "has space").build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).issueTime(null).build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).expirationTime(null).build()));
    }

    @Test
    void rejectsATokenIssuedInTheFuture() {
        Instant future = Instant.now().plusSeconds(600);
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB)
                .issueTime(Date.from(future))
                .expirationTime(Date.from(future.plusSeconds(3600)))
                .build()));
    }

    @Test
    void requiresTheOpenidScopeForUserInfo() {
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("scope", "email").build()));
        assertUnauthorized(tokens.sign(tokens.accessClaims(SUB).claim("scope", null).build()));
    }

    @Test
    void unreachableSigningKeysAreAServiceProblemNotA401() {
        JWKSource<SecurityContext> broken = (selector, context) -> {
            throw new KeySourceException("JWKS unreachable");
        };
        CognitoAccessTokenVerifier offline = new CognitoAccessTokenVerifier(ISSUER, CLIENT_ID, broken, Clock.systemUTC());

        RegistrationException e = assertThrows(RegistrationException.class, () -> offline.verify(tokens.bearer(SUB)));
        assertEquals(503, e.status());
        assertEquals("SERVICE_UNAVAILABLE", e.code());
    }

    private void assertUnauthorized(String token) {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> verifier.verify(token == null ? null : new BearerToken(token)));
        assertEquals(401, e.status());
        assertEquals("UNAUTHORIZED", e.code());
        assertEquals("Authentication is required.", e.getMessage());
    }
}
