package org.hmb.security;

import org.hmb.model.IdentityProvider;

/**
 * The caller's identity after every check passed: verified access token, matching UserInfo subject, verified email
 * and Google federation confirmed by Cognito. Only this record may supply the email and the Cognito subject that are
 * stored; nothing in the request body can.
 *
 * @param email lower-cased verified email from Cognito
 */
public record AuthenticatedIdentity(String cognitoSubject, String email, IdentityProvider provider) {

    @Override
    public String toString() {
        return "AuthenticatedIdentity[provider=" + provider + ", redacted]";
    }
}
