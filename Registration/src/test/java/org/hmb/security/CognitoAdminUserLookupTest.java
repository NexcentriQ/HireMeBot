package org.hmb.security;

import org.hmb.exception.RegistrationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoAdminUserLookupTest {

    private static final String POOL = "us-east-2_F582IgoSY";
    private static final String GOOGLE_IDENTITIES =
            "[{\"dateCreated\":\"1730000000000\",\"userId\":\"1093\",\"providerName\":\"Google\","
                    + "\"providerType\":\"Google\",\"issuer\":null,\"primary\":\"true\"}]";

    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoAdminUserLookup lookup = new CognitoAdminUserLookup(client, POOL);

    @Test
    void recognisesAGoogleFederatedUser() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(user(UserStatusType.EXTERNAL_PROVIDER,
                true, AttributeType.builder().name("sub").value("s-1").build(),
                AttributeType.builder().name("identities").value(GOOGLE_IDENTITIES).build()));

        CognitoUserRecord record = lookup.lookup("google_1093");

        assertEquals("s-1", record.subject());
        assertTrue(record.enabled());
        assertEquals(Set.of("Google"), record.federatedProviders());
        assertTrue(record.isGoogleFederated());
        verify(client).adminGetUser(argThat((AdminGetUserRequest r) ->
                POOL.equals(r.userPoolId()) && "google_1093".equals(r.username())));
    }

    @Test
    void aNativeUserIsNotGoogleFederatedEvenWithAGmailAddress() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(user(UserStatusType.CONFIRMED, true,
                AttributeType.builder().name("sub").value("s-2").build(),
                AttributeType.builder().name("email").value("someone@gmail.com").build()));

        assertFalse(lookup.lookup("someone@gmail.com").isGoogleFederated());
    }

    @Test
    void aNativeUserLinkedToGoogleIsNotTreatedAsAGoogleSignUp() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(user(UserStatusType.CONFIRMED, true,
                AttributeType.builder().name("sub").value("s-3").build(),
                AttributeType.builder().name("identities").value(GOOGLE_IDENTITIES).build()));

        assertFalse(lookup.lookup("native-user").isGoogleFederated());
    }

    @Test
    void malformedIdentitiesMeanNoProviders() {
        assertEquals(Set.of(), CognitoAdminUserLookup.providerTypes("not json"));
        assertEquals(Set.of(), CognitoAdminUserLookup.providerTypes("{\"providerType\":\"Google\"}"));
        assertEquals(Set.of(), CognitoAdminUserLookup.providerTypes(null));
    }

    @Test
    void mapsCognitoFailures() {
        assertFailure(UserNotFoundException.builder().message("User does not exist.").statusCode(400).build(), 401);
        assertFailure(TooManyRequestsException.builder().statusCode(400).build(), 503);
        assertFailure(CognitoIdentityProviderException.builder().statusCode(500)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalErrorException").build()).build(), 503);
        assertFailure(CognitoIdentityProviderException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").build()).build(), 500);
        assertFailure(SdkClientException.create("connection refused"), 503);
    }

    private void assertFailure(RuntimeException thrown, int status) {
        doThrow(thrown).when(client).adminGetUser(any(AdminGetUserRequest.class));
        RegistrationException e = assertThrows(RegistrationException.class, () -> lookup.lookup("google_1"));
        assertEquals(status, e.status());
    }

    private static AdminGetUserResponse user(UserStatusType status, boolean enabled, AttributeType... attributes) {
        return AdminGetUserResponse.builder()
                .username("u")
                .userStatus(status)
                .enabled(enabled)
                .userAttributes(attributes)
                .build();
    }
}
