package org.hmb.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.hmb.security.JwtAccessTokenVerifier;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints Cognito-shaped JWTs signed by a locally generated RSA key and offers the production
 * {@link JwtAccessTokenVerifier} trusting that key, so token verification is tested for real (signatures and claims)
 * without any AWS access. A second instance acts as "another user pool" with different keys.
 */
public final class TestJwt {

    public static final String POOL_ID = "us-east-2_F582IgoSY";
    public static final String CLIENT_ID = "5rgjoisv64qhe70oulfla17id9";
    public static final String ISSUER = "https://cognito-idp.us-east-2.amazonaws.com/" + POOL_ID;

    private final RSAKey key;

    public TestJwt() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-" + UUID.randomUUID()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The production verifier, trusting only this instance's key. */
    public JwtAccessTokenVerifier verifier() {
        return new JwtAccessTokenVerifier(ISSUER, CLIENT_ID, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())),
                Clock.systemUTC());
    }

    /** Claims of a valid Cognito access token for a Google-federated user. */
    public JWTClaimsSet.Builder accessClaims(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .claim("version", 2)
                .claim("client_id", CLIENT_ID)
                .claim("token_use", "access")
                .claim("scope", "openid email")
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString())
                .claim("username", "google_" + Math.abs(sub.hashCode()));
    }

    /** A validly signed ID token for the right client: the wrong token type. */
    public JWTClaimsSet.Builder idClaims(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .claim("token_use", "id")
                .claim("email", "someone@gmail.com")
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString());
    }

    public String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String accessToken(String sub) {
        return sign(accessClaims(sub).build());
    }

    /** Tokens that must all be refused with 401: garbage, expired, wrong pool, forged, wrong client, ID token, no scope, unsigned. */
    public List<String> badTokensFor(String sub) {
        Instant past = Instant.now().minusSeconds(7200);
        return List.of(
                "not-a-jwt",
                sign(accessClaims(sub).issueTime(Date.from(past.minusSeconds(3600))).expirationTime(Date.from(past))
                        .build()),
                sign(accessClaims(sub).issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_Other1").build()),
                new TestJwt().accessToken(sub),
                sign(accessClaims(sub).claim("client_id", "wrongclient00000000000000").build()),
                sign(idClaims(sub).build()),
                sign(accessClaims(sub).claim("scope", "email").build()),
                new PlainJWT(accessClaims(sub).build()).serialize());
    }
}
