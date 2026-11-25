package org.hmb.config;

import org.hmb.model.AccountStatus;
import org.hmb.model.NextStep;
import org.hmb.service.AccountRules;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationConfigTest {

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("COGNITO_REGION", "us-east-2");
        env.put("COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY");
        env.put("COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9");
        env.put("COGNITO_DOMAIN", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com");
        env.put("TERMS_VERSION", "2026-11-01");
        return env;
    }

    @Test
    void buildsTheCognitoEndpoints() {
        RegistrationConfig config = RegistrationConfig.fromEnv(env());

        assertEquals("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_F582IgoSY", config.issuer());
        assertEquals("https://cognito-idp.us-east-2.amazonaws.com/us-east-2_F582IgoSY/.well-known/jwks.json",
                config.jwksUri().toString());
        assertEquals("https://us-east-2f582igosy.auth.us-east-2.amazoncognito.com/oauth2/userInfo",
                config.userInfoUri().toString());
    }

    @Test
    void phoneVerificationIsRequiredByDefaultSoNewAccountsArePending() {
        RegistrationConfig config = RegistrationConfig.fromEnv(env());
        assertTrue(config.phoneVerificationRequired());
        assertEquals(AccountStatus.PENDING, config.initialAccountStatus());
        assertEquals(NextStep.ACCOUNT_PENDING, AccountRules.nextStepFor(config.initialAccountStatus()));
    }

    @Test
    void activeAccountsOnlyWithAnExplicitFalse() {
        Map<String, String> env = env();
        env.put("PHONE_VERIFICATION_REQUIRED", "FALSE");
        RegistrationConfig config = RegistrationConfig.fromEnv(env);
        assertFalse(config.phoneVerificationRequired());
        assertEquals(AccountStatus.ACTIVE, config.initialAccountStatus());
        assertEquals(NextStep.DASHBOARD, AccountRules.nextStepFor(config.initialAccountStatus()));

        env.put("PHONE_VERIFICATION_REQUIRED", " true ");
        assertTrue(RegistrationConfig.fromEnv(env).phoneVerificationRequired());

        env.put("PHONE_VERIFICATION_REQUIRED", "no");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
    }

    @Test
    void termsVersionIsRequiredAndFitsTheColumn() {
        Map<String, String> env = env();
        env.remove("TERMS_VERSION");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
        env.put("TERMS_VERSION", "v".repeat(31));
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
        env.put("TERMS_VERSION", "v1; DROP");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
        env.put("TERMS_VERSION", "v".repeat(30));
        assertEquals(30, RegistrationConfig.fromEnv(env).termsVersion().length());
    }

    @Test
    void rejectsInconsistentOrMissingCognitoSettings() {
        for (String key : new String[] {"COGNITO_REGION", "COGNITO_USER_POOL_ID", "COGNITO_CLIENT_ID", "COGNITO_DOMAIN"}) {
            Map<String, String> env = env();
            env.remove(key);
            assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env), key);
        }
        Map<String, String> env = env();
        env.put("COGNITO_REGION", "us-west-2");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
        env.put("COGNITO_REGION", "us-east-2");
        env.put("COGNITO_CLIENT_ID", "bad-client!");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
    }

    @Test
    void normalizesTheDomainButRefusesPlainHttp() {
        Map<String, String> env = env();
        env.put("COGNITO_DOMAIN", "https://US-EAST-2F582IGOSY.auth.us-east-2.amazoncognito.com/");
        assertEquals("us-east-2f582igosy.auth.us-east-2.amazoncognito.com",
                RegistrationConfig.fromEnv(env).cognitoDomain());
        env.put("COGNITO_DOMAIN", "http://us-east-2f582igosy.auth.us-east-2.amazoncognito.com");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
        env.put("COGNITO_DOMAIN", "evil.example.com/path?x=1");
        assertThrows(IllegalStateException.class, () -> RegistrationConfig.fromEnv(env));
    }
}
