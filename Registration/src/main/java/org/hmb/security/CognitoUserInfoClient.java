package org.hmb.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.exception.RegistrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

/**
 * Calls {@code GET https://<cognito-domain>/oauth2/userInfo} with the caller's access token. Cognito answers only for
 * a valid, unrevoked token issued with the {@code openid} scope, so a successful call also proves the session is still
 * live (a signed-out user's token is refused here even before it expires).
 *
 * <p>Connect timeout 3 s, whole-request timeout 5 s, response limited to 16 KB, redirects never followed. Neither the
 * token nor the response body is ever logged.
 */
public final class CognitoUserInfoClient implements UserInfoClient {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(CognitoUserInfoClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final Duration requestTimeout;

    public CognitoUserInfoClient(URI endpoint) {
        this(endpoint, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    /** Visible for tests that need short timeouts. */
    CognitoUserInfoClient(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public CognitoUserInfo fetch(BearerToken token) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token.value())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            LOG.error("Cognito UserInfo timed out");
            throw RegistrationException.serviceUnavailable(e);
        } catch (IOException e) {
            LOG.error("Cognito UserInfo unreachable: {}", e.getClass().getSimpleName());
            throw RegistrationException.serviceUnavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RegistrationException.serviceUnavailable(e);
        }

        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 200) {
                return parse(body.readNBytes(MAX_RESPONSE_BYTES + 1));
            }
            if (status == 400 || status == 401 || status == 403) {
                // Cognito refused the token: invalid, revoked (global sign-out) or lacking the openid scope.
                LOG.info("Cognito UserInfo refused the access token (HTTP {})", status);
                throw RegistrationException.unauthorized();
            }
            LOG.error("Cognito UserInfo answered HTTP {}", status);
            throw RegistrationException.serviceUnavailable(null);
        } catch (IOException e) {
            LOG.error("Could not read the Cognito UserInfo response: {}", e.getClass().getSimpleName());
            throw RegistrationException.serviceUnavailable(e);
        }
    }

    private static CognitoUserInfo parse(byte[] body) {
        if (body.length > MAX_RESPONSE_BYTES) {
            LOG.error("Cognito UserInfo response exceeds {} bytes", MAX_RESPONSE_BYTES);
            throw RegistrationException.serviceUnavailable(null);
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (IOException e) {
            LOG.error("Cognito UserInfo response is not valid JSON");
            throw RegistrationException.serviceUnavailable(null);
        }
        if (json == null || !json.isObject()) {
            LOG.error("Cognito UserInfo response is not a JSON object");
            throw RegistrationException.serviceUnavailable(null);
        }
        return new CognitoUserInfo(text(json, "sub"), text(json, "email"), emailVerified(json.get("email_verified")));
    }

    /**
     * Cognito returns {@code "email_verified": "true"} (a string); a JSON boolean is accepted too. Anything else,
     * including absence, means not verified.
     */
    static boolean emailVerified(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.isTextual() && "true".equals(node.textValue().trim().toLowerCase(Locale.ROOT));
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
