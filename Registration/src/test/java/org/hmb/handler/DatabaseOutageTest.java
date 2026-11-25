package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.hmb.config.DatabaseConfig;
import org.hmb.config.RegistrationConfig;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.service.CognitoIdentityService;
import org.hmb.service.RegistrationValidator;
import org.hmb.service.StudentRegistrationService;
import org.hmb.support.Events;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A real connection failure (nothing listens on port 1), through the real handler and service: sanitized 503. */
class DatabaseOutageTest {

    @Test
    void databaseOutageIsASanitized503WithARequestId() throws Exception {
        String sub = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
        TestTokens tokens = new TestTokens();
        CognitoIdentityService identity = mock(CognitoIdentityService.class);
        when(identity.resolve(any(), any()))
                .thenReturn(new AuthenticatedIdentity(sub, "alex@gmail.com", IdentityProvider.GOOGLE));
        RegistrationConfig config = new RegistrationConfig("us-east-2", TestTokens.POOL_ID, TestTokens.CLIENT_ID,
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", true);

        try (HikariDataSource unreachable = DatabaseConfig.create(Map.of("DB_HOST", "127.0.0.1", "DB_PORT", "1",
                "DB_NAME", "hiremebot_db_v1", "DB_USERNAME", "registration", "DB_PASSWORD", "not-a-real-password",
                "DB_SSL_MODE", "DISABLED"))) {
            RegistrationHandler handler = new RegistrationHandler(
                    new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID, tokens.jwkSource(),
                            Clock.systemUTC()),
                    new StudentRegistrationService(unreachable, new RegistrationValidator(), identity,
                            new UserRepository(), new StudentRepository(), new UserIdentityRepository(), config,
                            Clock.systemUTC()));

            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(Events.register(tokens.accessToken(sub), Events.VALID_BODY), null);

            assertEquals(503, response.getStatusCode());
            JsonNode body = new ObjectMapper().readTree(response.getBody());
            assertEquals("SERVICE_UNAVAILABLE", body.get("code").asText());
            assertEquals("req-test-1", body.get("requestId").asText());
            for (String leak : new String[] {"127.0.0.1", "jdbc", "registration", "not-a-real-password", "hikari",
                    "Exception", "SQL"}) {
                assertFalse(response.getBody().toLowerCase().contains(leak.toLowerCase()), leak);
            }
        }
    }
}
