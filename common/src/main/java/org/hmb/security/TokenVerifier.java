package org.hmb.security;

import org.hmb.exception.ApiException;

/** Verifies a bearer token locally; no trust in API Gateway or in any caller-supplied request context. */
@FunctionalInterface
public interface TokenVerifier {

    /**
     * @throws ApiException 401 {@code UNAUTHORIZED} for any invalid token, 503 if the signing keys cannot be fetched
     */
    VerifiedAccessToken verify(BearerToken token);
}
