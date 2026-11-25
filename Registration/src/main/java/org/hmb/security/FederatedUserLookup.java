package org.hmb.security;

import org.hmb.exception.RegistrationException;

/** Reads trusted, server-side Cognito metadata about a user (which identity provider created it). */
public interface FederatedUserLookup {

    /**
     * @param username the {@code username} claim of a verified access token
     * @throws RegistrationException 401 if the user no longer exists, 503 if Cognito is unavailable, 500 on a
     *                               configuration problem such as a missing IAM permission
     */
    CognitoUserRecord lookup(String username);
}
