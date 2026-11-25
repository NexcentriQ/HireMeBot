package org.hmb.security;

import java.time.Instant;
import java.util.Set;

/**
 * Claims of a Cognito access token whose signature, issuer, client, token type and lifetime were all verified.
 *
 * @param subject  the Cognito {@code sub}: the user's stable identifier, stored in {@code user_identities.cognito_sub}
 * @param username the Cognito user name (for a Google user {@code google_<id>}); used only to look the user up
 *                 server-side, never as a security decision on its own
 */
public record VerifiedAccessToken(String subject, String username, String clientId, Set<String> scopes,
                                  Instant expiresAt) {

    public VerifiedAccessToken {
        scopes = Set.copyOf(scopes);
    }
}
