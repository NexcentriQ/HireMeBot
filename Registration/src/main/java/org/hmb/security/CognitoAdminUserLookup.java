package org.hmb.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.exception.RegistrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Looks the authenticated user up with Cognito {@code AdminGetUser} to prove it is a Google-federated user. This is
 * trusted metadata: {@code UserStatus} and the {@code identities} attribute are maintained by Cognito and cannot be
 * set by the user, unlike an email domain or a user-name prefix.
 *
 * <p>Requires exactly one IAM permission on the Lambda role: {@code cognito-idp:AdminGetUser} on this user pool's ARN.
 * It never calls {@code AdminLinkProviderForUser} or changes the user in any way.
 */
public final class CognitoAdminUserLookup implements FederatedUserLookup {

    private static final Logger LOG = LoggerFactory.getLogger(CognitoAdminUserLookup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoIdentityProviderClient client;
    private final String userPoolId;

    public CognitoAdminUserLookup(CognitoIdentityProviderClient client, String userPoolId) {
        this.client = client;
        this.userPoolId = userPoolId;
    }

    /** The production client: URL-connection HTTP client, 3 s connect / 5 s socket, 8 s per call including retries. */
    public static CognitoAdminUserLookup create(String region, String userPoolId) {
        CognitoIdentityProviderClient client = CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(5))
                        .build())
                .overrideConfiguration(c -> c
                        .apiCallAttemptTimeout(Duration.ofSeconds(4))
                        .apiCallTimeout(Duration.ofSeconds(8)))
                .build();
        return new CognitoAdminUserLookup(client, userPoolId);
    }

    @Override
    public CognitoUserRecord lookup(String username) {
        AdminGetUserResponse response;
        try {
            response = client.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build());
        } catch (UserNotFoundException e) {
            LOG.info("AdminGetUser: the token's user no longer exists in the pool");
            throw RegistrationException.unauthorized();
        } catch (TooManyRequestsException e) {
            LOG.error("AdminGetUser throttled");
            throw RegistrationException.serviceUnavailable(e);
        } catch (CognitoIdentityProviderException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            if (e.statusCode() >= 500) {
                LOG.error("AdminGetUser failed: HTTP {} {}", e.statusCode(), code);
                throw RegistrationException.serviceUnavailable(e);
            }
            if ("AccessDeniedException".equals(code)) {
                LOG.error("AdminGetUser denied: the Lambda role needs cognito-idp:AdminGetUser on the user pool");
            } else {
                LOG.error("AdminGetUser failed: HTTP {} {}", e.statusCode(), code);
            }
            throw RegistrationException.internal(e);
        } catch (SdkClientException e) {
            LOG.error("AdminGetUser unreachable: {}", e.getClass().getSimpleName());
            throw RegistrationException.serviceUnavailable(e);
        } catch (SdkException e) {
            LOG.error("AdminGetUser failed: {}", e.getClass().getSimpleName());
            throw RegistrationException.serviceUnavailable(e);
        }

        String subject = null;
        String identities = null;
        if (response.hasUserAttributes()) {
            for (AttributeType attribute : response.userAttributes()) {
                if ("sub".equals(attribute.name())) {
                    subject = attribute.value();
                } else if ("identities".equals(attribute.name())) {
                    identities = attribute.value();
                }
            }
        }
        return new CognitoUserRecord(subject, Boolean.TRUE.equals(response.enabled()), response.userStatusAsString(),
                providerTypes(identities));
    }

    /**
     * Parses the {@code identities} attribute, a JSON array such as
     * {@code [{"providerName":"Google","providerType":"Google","userId":"...",...}]}, into its provider types.
     * Malformed or missing values yield an empty set (treated as "not federated").
     */
    static Set<String> providerTypes(String identities) {
        Set<String> types = new HashSet<>();
        if (identities == null || identities.isBlank()) {
            return types;
        }
        try {
            JsonNode array = MAPPER.readTree(identities);
            if (array != null && array.isArray()) {
                for (JsonNode identity : array) {
                    JsonNode type = identity.get("providerType");
                    if (type != null && type.isTextual()) {
                        types.add(type.textValue());
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Cognito identities attribute is not valid JSON");
        }
        return types;
    }
}
