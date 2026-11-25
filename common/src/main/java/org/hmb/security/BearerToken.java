package org.hmb.security;

/**
 * The raw bearer token from the {@code Authorization} header. It is a credential: {@link #toString()} is redacted so
 * it can never reach a log line by accident. Holding one proves nothing; see {@link AccessTokenVerifier}.
 */
public record BearerToken(String value) {

    @Override
    public String toString() {
        return "BearerToken[redacted]";
    }
}
