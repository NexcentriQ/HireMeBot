package org.hmb.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Builds the HikariCP pool from environment variables.
 *
 * <p>Required: {@code DB_HOST}, {@code DB_NAME}, {@code DB_USERNAME}, {@code DB_PASSWORD}.
 * Optional: {@code DB_PORT} (default 3306), {@code DB_SSL_MODE} (default {@code VERIFY_IDENTITY}),
 * {@code DB_TRUSTSTORE_URL}, {@code DB_TRUSTSTORE_PASSWORD}, {@code DB_TRUSTSTORE_TYPE} (default PKCS12) for
 * providers (e.g. Aiven) whose server certificate is signed by a private CA.
 *
 * <p>The pool is created lazily on first use and kept in a static field, so warm Lambda invocations reuse it.
 * Credentials are never logged.
 */
public final class DatabaseConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseConfig.class);

    private static volatile HikariDataSource dataSource;

    private DatabaseConfig() {
    }

    /** Returns the shared pool, creating it from {@link System#getenv()} on first call. */
    public static DataSource getDataSource() {
        HikariDataSource local = dataSource;
        if (local == null) {
            synchronized (DatabaseConfig.class) {
                local = dataSource;
                if (local == null) {
                    local = create(System.getenv());
                    dataSource = local;
                }
            }
        }
        return local;
    }

    /** Builds a new pool from the given environment map. Visible for testing. */
    static HikariDataSource create(Map<String, String> env) {
        String host = required(env, "DB_HOST");
        String name = required(env, "DB_NAME");
        String username = required(env, "DB_USERNAME");
        String password = required(env, "DB_PASSWORD");
        String port = env.getOrDefault("DB_PORT", "3306").trim();
        if (!port.matches("\\d{1,5}")) {
            throw new IllegalStateException("DB_PORT must be a number");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("hiremebot-pool");
        config.setJdbcUrl(buildJdbcUrl(env, host.trim(), port, name.trim()));
        config.setUsername(username);
        config.setPassword(password);

        // Small pool: each Lambda environment handles one request at a time.
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(300_000);
        // Do not fail Lambda init when the database is down; getConnection() reports it per request (-> 503).
        config.setInitializationFailTimeout(-1);

        LOG.info("Creating datasource for {}:{}/{}", host, port, name);
        return new HikariDataSource(config);
    }

    private static String buildJdbcUrl(Map<String, String> env, String host, String port, String name) {
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(host).append(':').append(port).append('/').append(encode(name))
                .append("?sslMode=").append(encode(env.getOrDefault("DB_SSL_MODE", "VERIFY_IDENTITY").trim()))
                .append("&connectTimeout=5000")
                .append("&socketTimeout=15000")
                .append("&characterEncoding=UTF-8")
                .append("&connectionTimeZone=UTC")
                .append("&forceConnectionTimeZoneToSession=true");

        String trustStore = env.get("DB_TRUSTSTORE_URL");
        if (trustStore != null && !trustStore.isBlank()) {
            url.append("&trustCertificateKeyStoreUrl=").append(encode(trustStore.trim()))
                    .append("&trustCertificateKeyStoreType=")
                    .append(encode(env.getOrDefault("DB_TRUSTSTORE_TYPE", "PKCS12").trim()));
            String trustStorePassword = env.get("DB_TRUSTSTORE_PASSWORD");
            if (trustStorePassword != null && !trustStorePassword.isEmpty()) {
                url.append("&trustCertificateKeyStorePassword=").append(encode(trustStorePassword));
            }
        }
        return url.toString();
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
