package org.hmb.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * A local stand-in for the Cognito user pool's key endpoint: it serves a real JWKS over HTTP and signs Cognito-shaped
 * access/ID tokens with the matching private key, so the production verifier ({@link JwtAccessTokenVerifier} with its
 * remote JWKS source) is exercised end to end with genuine signatures. A second instance acts as "another pool".
 */
public final class LocalJwks implements AutoCloseable {

    public static final String POOL_ID = "us-east-2_F582IgoSY";
    public static final String CLIENT_ID = "5rgjoisv64qhe70oulfla17id9";
    public static final String ISSUER = "https://cognito-idp.us-east-2.amazonaws.com/" + POOL_ID;

    private final RSAKey key;
    private final HttpServer server;

    public LocalJwks() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("it-" + UUID.randomUUID()).generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jwks);
            }
        });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json");
    }

    /** The production verifier, pointed at this server's keys. */
    public JwtAccessTokenVerifier verifier() {
        return new JwtAccessTokenVerifier(ISSUER, CLIENT_ID, JwtAccessTokenVerifier.remoteKeys(uri()), Clock.systemUTC());
    }

    public JWTClaimsSet.Builder accessClaims(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .claim("client_id", CLIENT_ID)
                .claim("token_use", "access")
                .claim("scope", "openid email")
                .claim("version", 2)
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString())
                .claim("username", "google_" + Math.abs(sub.hashCode()));
    }

    public JWTClaimsSet.Builder idClaims(String sub) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .claim("token_use", "id")
                .claim("email", "someone@gmail.com")
                .claim("email_verified", true)
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .jwtID(UUID.randomUUID().toString());
    }

    public String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String accessToken(String sub) {
        return sign(accessClaims(sub).build());
    }

    public List<String> badTokensFor(String sub) {
        Instant past = Instant.now().minusSeconds(7200);
        return List.of(
                "garbage",
                sign(accessClaims(sub).issueTime(Date.from(past.minusSeconds(3600))).expirationTime(Date.from(past)).build()),
                sign(accessClaims(sub).issuer("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_OtherPool1").build()),
                sign(accessClaims(sub).claim("client_id", "wrongclient00000000000000").build()),
                sign(idClaims(sub).build()),
                sign(accessClaims(sub).claim("scope", "email").build()));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
