package org.hmb.security;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three settings needed to verify a Cognito access token: {@code COGNITO_REGION}, {@code COGNITO_USER_POOL_ID} and
 * {@code COGNITO_CLIENT_ID}. Validated when read, so a misconfigured function fails closed (a generic 500) instead of
 * accepting tokens from the wrong pool or client. The Registration Lambda has a larger config
 * ({@code RegistrationConfig}) whose issuer and JWKS URL are built the same way.
 */
public record CognitoAuthConfig(String region, String userPoolId, String clientId) {

    public static final String REGION_VAR = "COGNITO_REGION";
    public static final String USER_POOL_ID_VAR = "COGNITO_USER_POOL_ID";
    public static final String CLIENT_ID_VAR = "COGNITO_CLIENT_ID";

    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d");
    private static final Pattern USER_POOL_ID = Pattern.compile("([a-z]{2}(?:-[a-z]+)+-\\d)_[0-9A-Za-z]+");
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9A-Za-z]{1,128}");

    public CognitoAuthConfig {
        if (region == null || !REGION.matcher(region).matches()) {
            throw new IllegalStateException(REGION_VAR + " is not a valid AWS region");
        }
        Matcher pool = userPoolId == null ? null : USER_POOL_ID.matcher(userPoolId);
        if (pool == null || !pool.matches()) {
            throw new IllegalStateException(USER_POOL_ID_VAR + " is not a valid Cognito user pool id");
        }
        if (!pool.group(1).equals(region)) {
            throw new IllegalStateException(USER_POOL_ID_VAR + " does not belong to " + REGION_VAR);
        }
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new IllegalStateException(CLIENT_ID_VAR + " is not a valid Cognito app client id");
        }
    }

    public static CognitoAuthConfig fromEnv(Map<String, String> env) {
        return new CognitoAuthConfig(trimmed(env, REGION_VAR), trimmed(env, USER_POOL_ID_VAR),
                trimmed(env, CLIENT_ID_VAR));
    }

    /** The {@code iss} claim every accepted token must carry. */
    public String issuer() {
        return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
    }

    public URI jwksUri() {
        return URI.create(issuer() + "/.well-known/jwks.json");
    }

    private static String trimmed(Map<String, String> env, String key) {
        String value = env.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
