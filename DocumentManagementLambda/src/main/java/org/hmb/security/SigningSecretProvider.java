package org.hmb.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Supplies the secret that signs upload-intent tokens. The secret is never hardcoded, never logged and never
 * returned to a caller.
 *
 * <p>Sources, in order:
 * <ol>
 *   <li>{@code UPLOAD_SIGNING_SECRET_ID}: an AWS Secrets Manager secret (the deployed path), fetched once per
 *       container and cached.</li>
 *   <li>{@code UPLOAD_TOKEN_SECRET_DEV}: a plain environment value, honoured <b>only</b> when
 *       {@link DevAuth#permitted(Map)} (i.e. local {@code sam local} testing).</li>
 * </ol>
 * With neither, the first use fails with an {@link IllegalStateException} (a generic 500 for the caller).
 */
public interface SigningSecretProvider {

    String SECRET_ID_VAR = "UPLOAD_SIGNING_SECRET_ID";
    String DEV_SECRET_VAR = "UPLOAD_TOKEN_SECRET_DEV";
    int MIN_SECRET_CHARS = 32;

    /** @return the secret bytes (at least {@link #MIN_SECRET_CHARS} characters) */
    byte[] secret();

    static SigningSecretProvider fromEnvironment(Map<String, String> env) {
        String secretId = env.get(SECRET_ID_VAR);
        if (secretId != null && !secretId.isBlank()) {
            return new SecretsManagerProvider(secretId.trim());
        }
        String dev = env.get(DEV_SECRET_VAR);
        if (DevAuth.permitted(env) && dev != null && !dev.isBlank()) {
            return new FixedProvider(dev);
        }
        return () -> {
            throw new IllegalStateException("Upload signing secret is not configured");
        };
    }

    /** Validates length so a weak secret can never sign a token. */
    static byte[] checked(String secret) {
        if (secret == null || secret.length() < MIN_SECRET_CHARS) {
            throw new IllegalStateException("Upload signing secret must be at least " + MIN_SECRET_CHARS + " characters");
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    /** A fixed secret, for local development and tests. */
    final class FixedProvider implements SigningSecretProvider {
        private final byte[] secret;

        public FixedProvider(String secret) {
            this.secret = checked(secret);
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    /** Reads the secret from AWS Secrets Manager on first use and keeps it for the life of the container. */
    final class SecretsManagerProvider implements SigningSecretProvider {
        private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerProvider.class);

        private final String secretId;
        private volatile byte[] cached;

        public SecretsManagerProvider(String secretId) {
            this.secretId = secretId;
        }

        @Override
        public byte[] secret() {
            byte[] local = cached;
            if (local == null) {
                synchronized (this) {
                    local = cached;
                    if (local == null) {
                        try (SecretsManagerClient client = SecretsManagerClient.builder()
                                .httpClient(UrlConnectionHttpClient.create())
                                .build()) {
                            String value = client.getSecretValue(
                                    GetSecretValueRequest.builder().secretId(secretId).build()).secretString();
                            local = checked(value);
                        }
                        cached = local;
                        LOG.info("Loaded the upload signing secret from Secrets Manager");
                    }
                }
            }
            return local.clone();
        }
    }
}
