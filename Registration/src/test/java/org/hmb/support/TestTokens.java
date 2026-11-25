package org.hmb.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.hmb.security.BearerToken;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints Cognito-shaped JWTs signed by a locally generated RSA key, so token verification is tested for real
 * (signatures, claims) without any AWS access. A second instance acts as "another user pool" with different keys.
 */
public final class TestTokens {

    public static final String REGION = "us-east-2";
    public static final String POOL_ID = "us-east-2_F582IgoSY";
    public static final String CLIENT_ID = "5rgjoisv64qhe70oulfla17id9";
    public static final String ISSUER = "https://cognito-idp." + REGION + ".amazonaws.com/" + POOL_ID;

    private final RSAKey key;

    public TestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-" + UUID.randomUUID()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
    }

    public String publicJwksJson() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    public static String googleUsername(String sub) {
        return "google_" + Math.abs(sub.hashCode());
    }

    /** Claims of a valid Cognito access token for a Google-federated user. */
    public JWTClaimsSet.Builder accessClaims(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .claim("cognito:groups", List.of(POOL_ID + "_Google"))
                .claim("version", 2)
                .claim("client_id", CLIENT_ID)
                .claim("origin_jti", UUID.randomUUID().toString())
                .claim("token_use", "access")
                .claim("scope", "openid email")
                .claim("auth_time", now.minusSeconds(10).getEpochSecond())
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString())
                .claim("username", googleUsername(sub));
    }

    /** Claims of a valid Cognito ID token (validly signed, right client, but the wrong token type). */
    public JWTClaimsSet.Builder idClaims(String sub, String email) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .claim("token_use", "id")
                .claim("email", email)
                .claim("email_verified", true)
                .claim("cognito:username", googleUsername(sub))
                .claim("auth_time", now.minusSeconds(10).getEpochSecond())
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

    public BearerToken bearer(String sub) {
        return new BearerToken(accessToken(sub));
    }

    /** Same claims, but HS256 with the public key's bytes as the HMAC secret (the classic key-confusion attack). */
    public String hmacWithPublicKey(JWTClaimsSet claims) {
        try {
            byte[] secret = key.toPublicJWK().toRSAPublicKey().getEncoded();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An unsigned token ({@code alg: none}). */
    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }
}
