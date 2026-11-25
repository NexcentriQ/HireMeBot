package org.hmb.security;

import java.util.Set;

/**
 * Server-side Cognito metadata for a user, from {@code AdminGetUser}.
 *
 * @param subject             the {@code sub} attribute
 * @param enabled             whether the Cognito user is enabled
 * @param userStatus          e.g. {@code EXTERNAL_PROVIDER} for a federated (Google) user, {@code CONFIRMED} for a
 *                            native user
 * @param federatedProviders  the {@code providerType} values of the Cognito-managed {@code identities} attribute,
 *                            e.g. {@code Google}; empty for a native user
 */
public record CognitoUserRecord(String subject, boolean enabled, String userStatus, Set<String> federatedProviders) {

    public static final String STATUS_EXTERNAL_PROVIDER = "EXTERNAL_PROVIDER";
    public static final String PROVIDER_GOOGLE = "Google";

    public CognitoUserRecord {
        federatedProviders = Set.copyOf(federatedProviders);
    }

    /** True only for a user created by Google federation (not a native user, and not a native user linked later). */
    public boolean isGoogleFederated() {
        return STATUS_EXTERNAL_PROVIDER.equals(userStatus) && federatedProviders.contains(PROVIDER_GOOGLE);
    }
}
