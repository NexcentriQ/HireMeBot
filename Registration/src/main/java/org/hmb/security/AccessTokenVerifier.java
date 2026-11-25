package org.hmb.security;

import org.hmb.exception.RegistrationException;

/** Verifies a bearer token locally (no trust in API Gateway or the caller-supplied request context). */
public interface AccessTokenVerifier {

    /**
     * @throws RegistrationException 401 {@code UNAUTHORIZED} for any invalid token, 503 if the signing keys cannot be
     *                               fetched
     */
    VerifiedAccessToken verify(BearerToken token);
}
