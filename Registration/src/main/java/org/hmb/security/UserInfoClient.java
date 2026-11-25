package org.hmb.security;

import org.hmb.exception.RegistrationException;

/** Reads the authenticated user's attributes from Cognito using the caller's own access token. */
public interface UserInfoClient {

    /**
     * @throws RegistrationException 401 if Cognito rejects the token (e.g. revoked), 503 if Cognito is unreachable,
     *                               slow or answers unexpectedly
     */
    CognitoUserInfo fetch(BearerToken token);
}
