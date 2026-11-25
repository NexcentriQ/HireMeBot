package org.hmb.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import org.hmb.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared verifier itself (Registration's 12 token tests also run through it via its adapter). */
class JwtAccessTokenVerifierTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";

    private final TestJwt jwt = new TestJwt();
    private final JwtAccessTokenVerifier verifier = jwt.verifier(Clock.systemUTC());

    @Test
    void acceptsAValidAccessToken() {
        VerifiedAccessToken verified = verifier.verify(new BearerToken(jwt.accessToken(SUB)));

        assertEquals(SUB, verified.subject());
        assertEquals(TestJwt.CLIENT_ID, verified.clientId());
        assertTrue(verified.scopes().contains("openid"));
        assertTrue(verified.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void rejectsEveryKindOfBadTokenWithTheSameGeneric401() {
        Instant past = Instant.now().minusSeconds(7200);
        TestJwt otherPool = new TestJwt();
        String valid = jwt.accessToken(SUB);
        String[] parts = valid.split("\\.");
        String[] other = jwt.accessToken("11111111-2222-3333-4444-555555555555").split("\\.");
        String[] bad = {
                "", "not-a-jwt", "aaa.bbb.ccc",
                "x".repeat(JwtAccessTokenVerifier.MAX_TOKEN_LENGTH + 1),
                jwt.sign(jwt.accessClaims(SUB).issueTime(Date.from(past.minusSeconds(3600)))
                        .expirationTime(Date.from(past)).build()),                                              // expired
                jwt.sign(jwt.accessClaims(SUB).issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Other1").build()),
                otherPool.accessToken(SUB),                                                                     // foreign key
                jwt.sign(jwt.accessClaims(SUB).claim("client_id", "someotherclientid000000000").build()),       // wrong client
                jwt.sign(jwt.idClaims(SUB, "a@gmail.com").build()),                                            // ID token
                jwt.sign(jwt.accessClaims(SUB).claim("token_use", "id").build()),
                jwt.sign(jwt.accessClaims(SUB).claim("scope", "email").build()),                                // no openid
                jwt.sign(jwt.accessClaims(SUB).subject(null).build()),
                jwt.sign(jwt.accessClaims(SUB).claim("username", null).build()),
                jwt.sign(jwt.accessClaims(SUB).issueTime(Date.from(Instant.now().plusSeconds(600)))
                        .expirationTime(Date.from(Instant.now().plusSeconds(4200))).build()),                   // iat in the future
                TestJwt.unsigned(jwt.accessClaims(SUB).build()),                                                // alg none
                jwt.hmacWithPublicKey(jwt.accessClaims(SUB).build()),                                           // HS256 forgery
                parts[0] + "." + other[1] + "." + parts[2]};                                                    // tampered payload
        for (String token : bad) {
            ApiException e = assertThrows(ApiException.class, () -> verifier.verify(new BearerToken(token)),
                    token.length() > 40 ? token.substring(0, 40) : token);
            assertEquals(401, e.getStatusCode());
            assertEquals("UNAUTHORIZED", e.getErrorCode());
            assertEquals("Authentication is required.", e.getMessage());
        }
        assertEquals(401, assertThrows(ApiException.class, () -> verifier.verify(null)).getStatusCode());
    }

    @Test
    void aClockSkewOfThirtySecondsIsTolerated() {
        JWTClaimsSet justExpired = jwt.accessClaims(SUB)
                .issueTime(Date.from(Instant.now().minusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(10))).build();
        assertEquals(SUB, verifier.verify(new BearerToken(jwt.sign(justExpired))).subject());
    }

    @Test
    void unreachableSigningKeysAreAServiceProblemNotA401() {
        JWKSource<SecurityContext> broken = (selector, context) -> {
            throw new KeySourceException("JWKS unreachable");
        };
        JwtAccessTokenVerifier offline = new JwtAccessTokenVerifier(TestJwt.ISSUER, TestJwt.CLIENT_ID, broken,
                Clock.systemUTC());

        ApiException e = assertThrows(ApiException.class, () -> offline.verify(new BearerToken(jwt.accessToken(SUB))));
        assertEquals(503, e.getStatusCode());
        assertEquals("SERVICE_UNAVAILABLE", e.getErrorCode());
    }
}
