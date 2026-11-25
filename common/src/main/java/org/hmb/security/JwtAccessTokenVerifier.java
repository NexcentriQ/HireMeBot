package org.hmb.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.ExpiredJWTException;
import org.hmb.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Full local verification of a Cognito <b>access</b> token, shared by every Lambda that authenticates callers (the
 * Registration module wraps it for its own error envelope). This does not rely on API Gateway: the JWT authorizer is a
 * first gate, but a direct invocation, a forged request context, or {@code sam local} (which does not run authorizers)
 * must not get past this class.
 *
 * <p>A token is accepted only if all of these hold:
 * <ul>
 *   <li>it is a compact JWS signed with <b>RS256</b> by a key in the user pool's JWKS (no {@code alg=none}, no HMAC,
 *       no unknown {@code kid}, no encrypted or unsigned tokens);</li>
 *   <li>{@code iss} is exactly this user pool;</li>
 *   <li>{@code token_use} is exactly {@code access} (an ID token is refused even though it is validly signed);</li>
 *   <li>{@code client_id} is exactly the configured app client (access tokens carry no {@code aud});</li>
 *   <li>{@code exp} is in the future and {@code iat} is not in the future (30 s clock skew allowed);</li>
 *   <li>{@code sub} and {@code username} are present and well-formed, and {@code scope} includes {@code openid}.</li>
 * </ul>
 * Rejections are one generic 401; the reason category is logged without any token content.
 */
public final class JwtAccessTokenVerifier implements TokenVerifier {

    /** Cognito tokens are around 1 KB; anything far larger is refused before parsing. */
    public static final int MAX_TOKEN_LENGTH = 8 * 1024;
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(JwtAccessTokenVerifier.class);
    /** Cognito subjects are UUIDs; this also bounds the value to {@code user_identities.cognito_sub VARCHAR(128)}. */
    private static final Pattern SUBJECT = Pattern.compile("[0-9A-Za-z-]{1,128}");
    /** AdminGetUser accepts user names of at most 128 characters; no whitespace or control characters. */
    private static final Pattern USERNAME = Pattern.compile("[\\p{L}\\p{M}\\p{S}\\p{N}\\p{P}]{1,128}");

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final Clock clock;

    public JwtAccessTokenVerifier(String issuer, String clientId, JWKSource<SecurityContext> keys, Clock clock) {
        this.clock = clock;
        DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        // Only RS256 keys from the pool's JWKS can verify a token; this rules out alg=none and HS256 key confusion.
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                null, // access tokens have no "aud"; the client is checked through client_id below
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .claim("token_use", "access")
                        .claim("client_id", clientId)
                        .build(),
                Set.of("sub", "iss", "exp", "iat", "token_use", "client_id", "username"),
                null);
        claims.setMaxClockSkew((int) CLOCK_SKEW.toSeconds());
        jwtProcessor.setJWTClaimsSetVerifier(claims);
        this.processor = jwtProcessor;
    }

    /** The production verifier: keys fetched from the pool's JWKS URL (cached, rate-limited, with timeouts). */
    public static JwtAccessTokenVerifier forConfig(CognitoAuthConfig config, Clock clock) {
        return new JwtAccessTokenVerifier(config.issuer(), config.clientId(), remoteKeys(config.jwksUri()), clock);
    }

    /** Remote JWKS with 3 s connect / 5 s read timeouts and a 64 KB size limit. */
    public static JWKSource<SecurityContext> remoteKeys(URI jwksUri) {
        try {
            return JWKSourceBuilder.<SecurityContext>create(jwksUri.toURL(),
                            new DefaultResourceRetriever(3_000, 5_000, 64 * 1024))
                    .retrying(true)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid JWKS URL", e);
        }
    }

    @Override
    public VerifiedAccessToken verify(BearerToken token) {
        String raw = token == null ? null : token.value();
        if (raw == null || raw.isEmpty()) {
            throw reject("missing");
        }
        if (raw.length() > MAX_TOKEN_LENGTH) {
            throw reject("oversized");
        }

        JWTClaimsSet claims;
        try {
            claims = processor.process(raw, null);
        } catch (ParseException e) {
            throw reject("malformed");
        } catch (ExpiredJWTException e) {
            throw reject("expired");
        } catch (BadJWSException e) {
            throw reject("signature or key");
        } catch (BadJWTException e) {
            throw reject("claims (issuer, token_use, client_id or required claim)");
        } catch (BadJOSEException e) {
            throw reject("no matching key, unsupported or unsigned token");
        } catch (RateLimitReachedException e) {
            // Unknown kid while JWKS refreshes are rate-limited: the token is not signed by a known key. Known keys are
            // still served from the cache, so legitimate tokens keep working.
            throw reject("unknown key id");
        } catch (KeySourceException e) {
            // The pool's keys could not be fetched: not the caller's fault, and retryable.
            LOG.error("Could not fetch the Cognito signing keys: {}", e.getClass().getSimpleName());
            throw ApiException.serviceUnavailable(e);
        } catch (JOSEException e) {
            throw reject("verification error");
        }

        Instant now = clock.instant();
        Date issuedAt = claims.getIssueTime();
        if (issuedAt == null || issuedAt.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
            throw reject("issued in the future");
        }
        String subject = claims.getSubject();
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw reject("invalid sub");
        }
        String username = stringClaim(claims, "username");
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw reject("invalid username");
        }
        Set<String> scopes = scopes(claims);
        if (!scopes.contains("openid")) {
            throw reject("missing openid scope");
        }
        return new VerifiedAccessToken(subject, username, stringClaim(claims, "client_id"), scopes,
                claims.getExpirationTime().toInstant());
    }

    private static Set<String> scopes(JWTClaimsSet claims) {
        String scope = stringClaim(claims, "scope");
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            return null; // present but not a string
        }
    }

    private static ApiException reject(String reason) {
        LOG.info("Rejected access token: {}", reason);
        return ApiException.unauthorized();
    }
}
