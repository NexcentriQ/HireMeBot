package org.hmb.config;

import org.hmb.model.AccountStatus;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registration settings, read once from the environment. Every value is validated at start-up so a misconfigured
 * function fails closed (a generic 500) instead of registering students with wrong settings.
 *
 * <table>
 *   <tr><th>Variable</th><th>Required</th><th>Meaning</th></tr>
 *   <tr><td>{@code COGNITO_REGION}</td><td>yes</td><td>e.g. {@code us-east-2}; must match the pool id prefix</td></tr>
 *   <tr><td>{@code COGNITO_USER_POOL_ID}</td><td>yes</td><td>e.g. {@code us-east-2_F582IgoSY}</td></tr>
 *   <tr><td>{@code COGNITO_CLIENT_ID}</td><td>yes</td><td>the app client the access token must be issued to</td></tr>
 *   <tr><td>{@code COGNITO_DOMAIN}</td><td>yes</td><td>hosted domain, e.g.
 *       {@code us-east-2f582igosy.auth.us-east-2.amazoncognito.com}; used for {@code /oauth2/userInfo}</td></tr>
 *   <tr><td>{@code TERMS_VERSION}</td><td>yes</td><td>stored in {@code users.terms_version} (max 30 chars)</td></tr>
 *   <tr><td>{@code PHONE_VERIFICATION_REQUIRED}</td><td>no (missing = {@code true}; V1 deploys {@code false})</td>
 *       <td>account policy, see {@link #initialAccountStatus()}</td></tr>
 * </table>
 */
public record RegistrationConfig(String region, String userPoolId, String clientId, String cognitoDomain,
                                 String termsVersion, boolean phoneVerificationRequired) {

    public static final String REGION_VAR = "COGNITO_REGION";
    public static final String USER_POOL_ID_VAR = "COGNITO_USER_POOL_ID";
    public static final String CLIENT_ID_VAR = "COGNITO_CLIENT_ID";
    public static final String DOMAIN_VAR = "COGNITO_DOMAIN";
    public static final String TERMS_VERSION_VAR = "TERMS_VERSION";
    public static final String PHONE_VERIFICATION_REQUIRED_VAR = "PHONE_VERIFICATION_REQUIRED";

    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d");
    private static final Pattern USER_POOL_ID = Pattern.compile("([a-z]{2}(?:-[a-z]+)+-\\d)_[0-9A-Za-z]+");
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9A-Za-z]{1,128}");
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");
    /** Fits {@code users.terms_version VARCHAR(30)}. */
    private static final Pattern TERMS_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,30}");

    public RegistrationConfig {
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
        if (cognitoDomain == null || !HOSTNAME.matcher(cognitoDomain).matches()) {
            throw new IllegalStateException(DOMAIN_VAR + " must be a host name such as "
                    + "<prefix>.auth.<region>.amazoncognito.com");
        }
        if (termsVersion == null || !TERMS_VERSION.matcher(termsVersion).matches()) {
            throw new IllegalStateException(TERMS_VERSION_VAR
                    + " is required: 1-30 characters of letters, digits, '.', '_' or '-'");
        }
    }

    public static RegistrationConfig fromEnv(Map<String, String> env) {
        return new RegistrationConfig(
                trimmed(env, REGION_VAR),
                trimmed(env, USER_POOL_ID_VAR),
                trimmed(env, CLIENT_ID_VAR),
                domain(trimmed(env, DOMAIN_VAR)),
                trimmed(env, TERMS_VERSION_VAR),
                phoneVerificationRequired(env.get(PHONE_VERIFICATION_REQUIRED_VAR)));
    }

    /** The {@code iss} claim every accepted token must carry. */
    public String issuer() {
        return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
    }

    public URI jwksUri() {
        return URI.create(issuer() + "/.well-known/jwks.json");
    }

    public URI userInfoUri() {
        return URI.create("https://" + cognitoDomain + "/oauth2/userInfo");
    }

    /**
     * The account policy, the one place it is decided. HireMeBot V1 deploys {@code PHONE_VERIFICATION_REQUIRED=false}:
     * Google-verified students are stored {@code ACTIVE} and may use the app with an unverified phone (the mock OTP
     * proves nothing). {@code true} stores {@code PENDING} until real SMS verification exists. If the variable is
     * missing the code fails safe to {@code PENDING}. The phone is never marked verified either way.
     */
    public AccountStatus initialAccountStatus() {
        return phoneVerificationRequired ? AccountStatus.PENDING : AccountStatus.ACTIVE;
    }

    /** Missing means "required" (fail safe); anything other than true/false is a configuration error. */
    static boolean phoneVerificationRequired(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(PHONE_VERIFICATION_REQUIRED_VAR + " must be true or false");
        };
    }

    /** Accepts {@code host}, {@code https://host} or {@code https://host/}; plain http is refused. */
    static String domain(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.startsWith("http://")) {
            throw new IllegalStateException(DOMAIN_VAR + " must use https");
        }
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String trimmed(Map<String, String> env, String key) {
        String value = env.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
