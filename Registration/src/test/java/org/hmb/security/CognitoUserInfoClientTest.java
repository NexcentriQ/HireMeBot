package org.hmb.security;

import com.sun.net.httpserver.HttpServer;
import org.hmb.exception.RegistrationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoUserInfoClientTest {

    private HttpServer server;
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private CognitoUserInfoClient serve(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/oauth2/userInfo", ex -> {
            receivedAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // client gave up (timeout test)
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/userInfo");
        return new CognitoUserInfoClient(uri, Duration.ofSeconds(2), Duration.ofMillis(800));
    }

    @Test
    void readsSubjectEmailAndStringEmailVerifiedAndSendsTheBearerToken() throws IOException {
        CognitoUserInfoClient client = serve(200,
                "{\"sub\":\"s-1\",\"email\":\"Alex@Gmail.com\",\"email_verified\":\"true\",\"username\":\"google_1\"}", 0);

        CognitoUserInfo info = client.fetch(new BearerToken("abc.def.ghi"));

        assertEquals("s-1", info.subject());
        assertEquals("Alex@Gmail.com", info.email());
        assertTrue(info.emailVerified());
        assertEquals("Bearer abc.def.ghi", receivedAuthorization.get());
    }

    @Test
    void acceptsBooleanEmailVerified() throws IOException {
        assertTrue(serve(200, "{\"sub\":\"s\",\"email\":\"a@b.co\",\"email_verified\":true}", 0)
                .fetch(new BearerToken("t")).emailVerified());
    }

    @Test
    void falseOrMissingEmailVerifiedMeansNotVerified() throws IOException {
        assertFalse(serve(200, "{\"sub\":\"s\",\"email\":\"a@b.co\",\"email_verified\":\"false\"}", 0)
                .fetch(new BearerToken("t")).emailVerified());
        stop();
        assertFalse(serve(200, "{\"sub\":\"s\",\"email\":\"a@b.co\"}", 0).fetch(new BearerToken("t")).emailVerified());
        stop();
        assertFalse(serve(200, "{\"sub\":\"s\",\"email\":\"a@b.co\",\"email_verified\":1}", 0)
                .fetch(new BearerToken("t")).emailVerified());
        stop();
        CognitoUserInfo noEmail = serve(200, "{\"sub\":\"s\"}", 0).fetch(new BearerToken("t"));
        assertNull(noEmail.email());
    }

    @Test
    void aRefusedTokenIsUnauthorized() throws IOException {
        assertStatus(serve(401, "{\"error\":\"invalid_token\"}", 0), 401);
        stop();
        assertStatus(serve(400, "{\"error\":\"invalid_request\"}", 0), 401);
        stop();
        assertStatus(serve(403, "{}", 0), 401);
    }

    @Test
    void cognitoErrorsTimeoutsAndBadResponsesAreServiceUnavailable() throws IOException {
        assertStatus(serve(500, "{}", 0), 503);
        stop();
        assertStatus(serve(429, "{}", 0), 503);
        stop();
        assertStatus(serve(200, "not json", 0), 503);
        stop();
        assertStatus(serve(200, "[1,2]", 0), 503);
        stop();
        assertStatus(serve(200, "{\"sub\":\"" + "x".repeat(CognitoUserInfoClient.MAX_RESPONSE_BYTES) + "\"}", 0), 503);
        stop();
        assertStatus(serve(200, "{\"sub\":\"s\"}", 2_000), 503); // slower than the 800 ms request timeout
    }

    @Test
    void anUnreachableEndpointIsServiceUnavailable() {
        CognitoUserInfoClient client = new CognitoUserInfoClient(URI.create("http://127.0.0.1:1/oauth2/userInfo"),
                Duration.ofMillis(500), Duration.ofMillis(800));
        assertStatus(client, 503);
    }

    private static void assertStatus(CognitoUserInfoClient client, int status) {
        RegistrationException e = assertThrows(RegistrationException.class, () -> client.fetch(new BearerToken("t")));
        assertEquals(status, e.status());
    }
}
