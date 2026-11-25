package org.hmb.security;

import org.hmb.config.RegistrationConfig;
import org.hmb.exception.RegistrationException;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in checks against the <b>real</b> HireMeBot user pool ({@code RUN_COGNITO_LIVE_TESTS=true}). Nothing is written
 * anywhere and nothing personal is printed.
 *
 * <ul>
 *   <li>Always: the real JWKS is fetched and a token signed by a foreign key is refused (401, not 503).</li>
 *   <li>With {@code COGNITO_TEST_ACCESS_TOKEN} (an access token from your own Google sign-in, passed through the
 *       environment, never committed or logged): the token verifies, UserInfo returns the same subject, and the
 *       email-verified flag and Google federation status are reported as booleans. AdminGetUser uses your local AWS
 *       credentials and needs {@code cognito-idp:AdminGetUser}.</li>
 * </ul>
 * Behind a TLS-inspecting proxy add {@code -DargLine=-Djavax.net.ssl.trustStoreType=Windows-ROOT} (Windows).
 */
class CognitoLiveSmokeTest {

    private static RegistrationConfig config;

    @BeforeAll
    static void enabled() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_COGNITO_LIVE_TESTS")),
                "Set RUN_COGNITO_LIVE_TESTS=true to run the live Cognito smoke test");
        config = RegistrationConfig.fromEnv(Map.of(
                "COGNITO_REGION", "us-east-2",
                "COGNITO_USER_POOL_ID", "us-east-2_F582IgoSY",
                "COGNITO_CLIENT_ID", "5rgjoisv64qhe70oulfla17id9",
                "COGNITO_DOMAIN", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com",
                "TERMS_VERSION", "live-smoke-test"));
    }

    @Test
    void realJwksIsReachableAndForeignTokensAreRefused() {
        CognitoAccessTokenVerifier verifier = CognitoAccessTokenVerifier.forConfig(config, Clock.systemUTC());
        String forged = new TestTokens().accessToken("00000000-0000-0000-0000-000000000000");

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> verifier.verify(new BearerToken(forged)));
        assertEquals(401, e.status(), "503 would mean the JWKS could not be fetched");
    }

    @Test
    void aRealAccessTokenVerifiesAndMatchesUserInfo() {
        String token = System.getenv("COGNITO_TEST_ACCESS_TOKEN");
        assumeTrue(token != null && !token.isBlank(), "Set COGNITO_TEST_ACCESS_TOKEN to check a real token");
        BearerToken bearer = new BearerToken(token.trim());

        VerifiedAccessToken verified = CognitoAccessTokenVerifier.forConfig(config, Clock.systemUTC()).verify(bearer);
        CognitoUserInfo info = new CognitoUserInfoClient(config.userInfoUri()).fetch(bearer);
        assertEquals(verified.subject(), info.subject());
        System.out.println("[live] token verified; userInfo subject matches; email present=" + (info.email() != null)
                + "; email_verified=" + info.emailVerified());

        if ("true".equalsIgnoreCase(System.getenv("RUN_COGNITO_ADMIN_LOOKUP"))) {
            CognitoUserRecord user = CognitoAdminUserLookup.create(config.region(), config.userPoolId())
                    .lookup(verified.username());
            assertEquals(verified.subject(), user.subject());
            System.out.println("[live] AdminGetUser: enabled=" + user.enabled() + "; googleFederated="
                    + user.isGoogleFederated());
        }
    }
}
