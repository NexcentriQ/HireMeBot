package org.hmb.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the database credentials for every Lambda. When {@code DB_SECRET_ARN} is set (the deployed path) they come
 * from AWS Secrets Manager; otherwise the environment is used as is ({@code DB_USERNAME}/{@code DB_PASSWORD}, for local
 * runs and tests).
 *
 * <p>The secret must be JSON with {@code username} and {@code password} (the standard RDS secret format). Its optional
 * {@code host}, {@code port} and {@code dbname} are used only when {@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME}
 * are not set. The secret is read once per container and never logged. A rotated password is picked up on the next
 * cold start.
 */
public final class DatabaseSecretResolver {

    public static final String SECRET_ARN_VAR = "DB_SECRET_ARN";

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseSecretResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reads one secret string. Separated so tests never need AWS. */
    @FunctionalInterface
    public interface SecretReader {
        String read(String secretId);
    }

    private DatabaseSecretResolver() {
    }

    /** Resolves the credentials in {@code env}, reading the secret from Secrets Manager when one is configured. */
    public static Map<String, String> resolve(Map<String, String> env) {
        return resolve(env, DatabaseSecretResolver::readFromSecretsManager);
    }

    /** Same as {@link #resolve(Map)} with a custom reader, so tests never need AWS. */
    public static Map<String, String> resolve(Map<String, String> env, SecretReader reader) {
        String secretArn = env.get(SECRET_ARN_VAR);
        if (secretArn == null || secretArn.isBlank()) {
            if (env.get("AWS_LAMBDA_FUNCTION_NAME") != null && !"true".equalsIgnoreCase(env.get("AWS_SAM_LOCAL"))) {
                LOG.warn("{} is not set; using DB_USERNAME/DB_PASSWORD from the environment", SECRET_ARN_VAR);
            }
            return env;
        }

        String raw;
        try {
            raw = reader.read(secretArn.trim());
        } catch (RuntimeException e) {
            // SDK errors (AccessDenied, not found, network) never carry the secret value, so keep the cause.
            throw new IllegalStateException("Could not read the database secret from Secrets Manager", e);
        }
        JsonNode secret;
        try {
            secret = raw == null ? null : MAPPER.readTree(raw);
        } catch (IOException e) {
            // No cause: a JSON parser message can quote part of the secret.
            throw new IllegalStateException("The database secret is not valid JSON");
        }
        if (secret == null || !secret.isObject()) {
            throw new IllegalStateException("The database secret must be a JSON object");
        }
        Map<String, String> resolved = new HashMap<>(env);
        resolved.put("DB_USERNAME", requiredText(secret, "username"));
        resolved.put("DB_PASSWORD", requiredText(secret, "password"));
        fillIfMissing(resolved, "DB_HOST", secret, "host");
        fillIfMissing(resolved, "DB_PORT", secret, "port");
        fillIfMissing(resolved, "DB_NAME", secret, "dbname");
        LOG.info("Database credentials loaded from Secrets Manager");
        return resolved;
    }

    private static String readFromSecretsManager(String secretId) {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(5))
                        .build())
                .build()) {
            return client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build()).secretString();
        }
    }

    private static String requiredText(JsonNode secret, String field) {
        JsonNode node = secret == null ? null : secret.get(field);
        if (node == null || !node.isValueNode() || node.asText().isEmpty()) {
            throw new IllegalStateException("The database secret has no '" + field + "'");
        }
        return node.asText();
    }

    private static void fillIfMissing(Map<String, String> env, String key, JsonNode secret, String field) {
        String current = env.get(key);
        JsonNode node = secret.get(field);
        if ((current == null || current.isBlank()) && node != null && node.isValueNode() && !node.asText().isBlank()) {
            env.put(key, node.asText());
        }
    }
}
