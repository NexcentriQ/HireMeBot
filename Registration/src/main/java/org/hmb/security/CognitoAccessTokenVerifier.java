package org.hmb.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.hmb.config.RegistrationConfig;
import org.hmb.exception.ApiException;
import org.hmb.exception.RegistrationException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Registration's view of the shared {@link JwtAccessTokenVerifier} (in {@code common}, which also serves the recruiter
 * and document APIs): the same verification, with its failures expressed in this module's error envelope.
 *
 * <p>A token is accepted only if it is an RS256 JWS signed by the pool's JWKS with the exact issuer, {@code token_use}
 * {@code access}, the exact {@code client_id}, a valid lifetime, a well-formed {@code sub}/{@code username} and the
 * {@code openid} scope. Every rejection is one generic 401 {@code UNAUTHORIZED}; unreachable signing keys are a 503.
 */
public final class CognitoAccessTokenVerifier implements AccessTokenVerifier {

    /** Cognito tokens are around 1 KB; anything far larger is refused before parsing. */
    static final int MAX_TOKEN_LENGTH = JwtAccessTokenVerifier.MAX_TOKEN_LENGTH;
    static final Duration CLOCK_SKEW = JwtAccessTokenVerifier.CLOCK_SKEW;

    private final JwtAccessTokenVerifier core;

    public CognitoAccessTokenVerifier(String issuer, String clientId, JWKSource<SecurityContext> keys, Clock clock) {
        this.core = new JwtAccessTokenVerifier(issuer, clientId, keys, clock);
    }

    /** The production verifier: keys fetched from the pool's JWKS URL (cached, rate-limited, with timeouts). */
    public static CognitoAccessTokenVerifier forConfig(RegistrationConfig config, Clock clock) {
        return new CognitoAccessTokenVerifier(config.issuer(), config.clientId(), remoteKeys(config.jwksUri()), clock);
    }

    /** Remote JWKS with 3 s connect / 5 s read timeouts and a 64 KB size limit. */
    public static JWKSource<SecurityContext> remoteKeys(URI jwksUri) {
        return JwtAccessTokenVerifier.remoteKeys(jwksUri);
    }

    @Override
    public VerifiedAccessToken verify(BearerToken token) {
        try {
            return core.verify(token);
        } catch (ApiException e) {
            if (e.getStatusCode() == 503) {
                throw RegistrationException.serviceUnavailable(e.getCause());
            }
            throw RegistrationException.unauthorized();
        }
    }
}
