package org.hmb.security;

/** The fields read from Cognito's {@code /oauth2/userInfo} response. {@code email} may be null if absent. */
public record CognitoUserInfo(String subject, String email, boolean emailVerified) {

    @Override
    public String toString() {
        return "CognitoUserInfo[emailVerified=" + emailVerified + ", redacted]";
    }
}
