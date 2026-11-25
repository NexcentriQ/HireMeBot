package org.hmb.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoAuthConfigTest {

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("COGNITO_REGION", "us-east-2");
        env.put("COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY");
        env.put("COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9");
        return env;
    }

    @Test
    void buildsTheIssuerAndJwksUrl() {
        CognitoAuthConfig config = CognitoAuthConfig.fromEnv(env());

        assertEquals("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_F582IgoSY", config.issuer());
        assertEquals("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_F582IgoSY/.well-known/jwks.json",
                config.jwksUri().toString());
        assertEquals("5rgjoisv64qhe70oulfla17id9", config.clientId());
    }

    @Test
    void aMissingOrInconsistentSettingFailsClosed() {
        for (String key : new String[] {"COGNITO_REGION", "COGNITO_USER_POOL_ID", "COGNITO_CLIENT_ID"}) {
            Map<String, String> env = env();
            env.remove(key);
            assertThrows(IllegalStateException.class, () -> CognitoAuthConfig.fromEnv(env), key);
            env.put(key, "   ");
            assertThrows(IllegalStateException.class, () -> CognitoAuthConfig.fromEnv(env), key);
        }
        Map<String, String> env = env();
        env.put("COGNITO_REGION", "us-west-2"); // the pool belongs to us-east-2
        assertThrows(IllegalStateException.class, () -> CognitoAuthConfig.fromEnv(env));
        env.put("COGNITO_REGION", "us-east-2");
        env.put("COGNITO_CLIENT_ID", "bad-client!");
        assertThrows(IllegalStateException.class, () -> CognitoAuthConfig.fromEnv(env));
    }
}
