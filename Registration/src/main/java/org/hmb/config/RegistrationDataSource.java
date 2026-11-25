package org.hmb.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The registration Lambda's connection pool. Credentials come from AWS Secrets Manager when {@code DB_SECRET_ARN} is
 * set (the deployed path); otherwise from {@code DB_USERNAME}/{@code DB_PASSWORD} like the other Lambdas (local runs
 * and tests). The pool itself is built by the shared {@link DatabaseConfig}.
 *
 * <p>The secret must be JSON with {@code username} and {@code password} (the standard RDS secret format). Its optional
 * {@code host}, {@code port} and {@code dbname} are used only when {@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME}
 * are not set. The secret is read once per container and never logged. A rotated password is picked up on the next
 * cold start.
 */
public final class RegistrationDataSource {

    public static final String SECRET_ARN_VAR = "DB_SECRET_ARN";

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationDataSource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile DataSource instance;

    /** Reads one secret string. Separated so tests never need AWS. */
    @FunctionalInterface
    public interface SecretReader {
        String read(String secretId);
    }

    private RegistrationDataSource() {
    }

    /** Returns the shared pool, creating it on first use (warm invocations reuse it). */
    public static DataSource get(Map<String, String> env) {
        DataSource local = instance;
        if (local == null) {
            synchronized (RegistrationDataSource.class) {
                local = instance;
                if (local == null) {
                    local = DatabaseConfig.create(resolve(env, RegistrationDataSource::readFromSecretsManager));
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Returns the environment with the database credentials resolved. Visible for testing. */
    static Map<String, String> resolve(Map<String, String> env, SecretReader reader) {
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
