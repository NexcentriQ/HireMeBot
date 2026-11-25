package org.hmb.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.hmb.security.CognitoAdminUserLookup;
import org.hmb.security.CognitoUserInfoClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for the three Cognito endpoints the Lambda calls, so the real HTTP clients run end to end:
 * <ul>
 *   <li>{@code GET /pool/.well-known/jwks.json}: the test signing key (used by the real Nimbus remote JWKS source);</li>
 *   <li>{@code GET /oauth2/userInfo}: per-subject JSON for a Bearer token (used by {@link CognitoUserInfoClient});</li>
 *   <li>{@code POST /} with {@code X-Amz-Target: ...AdminGetUser}: per-username JSON (used by the real AWS SDK client
 *       through an endpoint override, i.e. {@link CognitoAdminUserLookup}).</li>
 * </ul>
 */
public final class FakeCognito implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final TestTokens tokens;
    private final Map<String, String> userInfoBySubject = new ConcurrentHashMap<>();
    private final Map<String, String> adminUserByUsername = new ConcurrentHashMap<>();
    public final AtomicInteger userInfoCalls = new AtomicInteger();
    public final AtomicInteger adminGetUserCalls = new AtomicInteger();

    public FakeCognito(TestTokens tokens) throws IOException {
        this.tokens = tokens;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/pool/.well-known/jwks.json", ex -> respond(ex, 200, tokens.publicJwksJson(), null));
        server.createContext("/oauth2/userInfo", this::userInfo);
        server.createContext("/", this::adminGetUser);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI jwksUri() {
        return baseUri().resolve("/pool/.well-known/jwks.json");
    }

    public URI userInfoUri() {
        return baseUri().resolve("/oauth2/userInfo");
    }

    /** A real SDK client pointed at this server (dummy credentials, request signing still happens). */
    public CognitoIdentityProviderClient sdkClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(TestTokens.REGION))
                .endpointOverride(baseUri())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    /** A Google-federated user as Cognito stores it. {@code emailVerified} is the raw JSON value (or null). */
    public void addGoogleUser(String sub, String email, Object emailVerified) {
        addUser(sub, email, emailVerified, "EXTERNAL_PROVIDER", true);
    }

    /** A Cognito-native (username/password) user, even if the address is a Gmail address. */
    public void addNativeUser(String sub, String email) {
        addUser(sub, email, "true", "CONFIRMED", false);
    }

    private void addUser(String sub, String email, Object emailVerified, String status, boolean google) {
        ObjectNode info = MAPPER.createObjectNode();
        info.put("sub", sub);
        if (email != null) {
            info.put("email", email);
        }
        if (emailVerified instanceof Boolean b) {
            info.put("email_verified", b);
        } else if (emailVerified != null) {
            info.put("email_verified", emailVerified.toString());
        }
        info.put("username", TestTokens.googleUsername(sub));
        userInfoBySubject.put(sub, info.toString());

        ObjectNode user = MAPPER.createObjectNode();
        user.put("Username", TestTokens.googleUsername(sub));
        user.put("UserStatus", status);
        user.put("Enabled", true);
        ArrayNode attributes = user.putArray("UserAttributes");
        attributes.addObject().put("Name", "sub").put("Value", sub);
        if (email != null) {
            attributes.addObject().put("Name", "email").put("Value", email);
        }
        if (google) {
            ArrayNode identities = MAPPER.createArrayNode();
            identities.addObject().put("userId", "1234567890").put("providerName", "Google")
                    .put("providerType", "Google").putNull("issuer").put("primary", "true")
                    .put("dateCreated", "1730000000000");
            attributes.addObject().put("Name", "identities").put("Value", identities.toString());
        }
        adminUserByUsername.put(TestTokens.googleUsername(sub), user.toString());
    }

    private void userInfo(HttpExchange ex) throws IOException {
        userInfoCalls.incrementAndGet();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String sub = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                sub = SignedJWT.parse(auth.substring(7)).getJWTClaimsSet().getSubject();
            } catch (java.text.ParseException ignored) {
                // falls through to 401
            }
        }
        String body = sub == null ? null : userInfoBySubject.get(sub);
        if (body == null) {
            respond(ex, 401, "{\"error\":\"invalid_token\"}", null);
        } else {
            respond(ex, 200, body, null);
        }
    }

    private void adminGetUser(HttpExchange ex) throws IOException {
        String target = ex.getRequestHeaders().getFirst("X-Amz-Target");
        if (!"POST".equals(ex.getRequestMethod()) || target == null || !target.endsWith(".AdminGetUser")) {
            respond(ex, 404, "{}", null);
            return;
        }
        adminGetUserCalls.incrementAndGet();
        JsonNode request = MAPPER.readTree(ex.getRequestBody());
        String user = adminUserByUsername.get(request.path("Username").asText());
        if (user == null || !TestTokens.POOL_ID.equals(request.path("UserPoolId").asText())) {
            respond(ex, 400, "{\"__type\":\"UserNotFoundException\",\"message\":\"User does not exist.\"}",
                    "UserNotFoundException");
        } else {
            respond(ex, 200, user, null);
        }
    }

    private static void respond(HttpExchange ex, int status, String body, String amzErrorType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.1");
        if (amzErrorType != null) {
            ex.getResponseHeaders().set("x-amzn-ErrorType", amzErrorType);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public TestTokens tokens() {
        return tokens;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
