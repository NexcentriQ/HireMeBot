package org.hmb.diagnostics;

import org.hmb.config.DatabaseConfig;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only database connectivity probe for {@code sam local invoke}; it is not wired to any route or to the deployed
 * template. It opens a connection the same way the real handlers do (the Registration module's
 * {@code RegistrationDataSource} when present, otherwise {@link DatabaseConfig}), runs {@code SELECT 1} and
 * {@code SELECT DATABASE()}, and reports whether the session is encrypted. It never writes, and its output contains
 * neither the host, nor the user name, nor the password (they are masked even inside driver error messages).
 *
 * <p>Handler: {@code org.hmb.diagnostics.DbConnectivityCheck::handleRequest}; see {@code scripts/dbcheck}.
 */
public final class DbConnectivityCheck {

    public Map<String, Object> handleRequest(Map<String, Object> ignored) {
        Map<String, String> env = System.getenv();
        String trustStore = env.get("DB_TRUSTSTORE_URL");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("function", env.getOrDefault("AWS_LAMBDA_FUNCTION_NAME", "unknown"));
        result.put("expectedDatabase", env.get("DB_NAME"));
        result.put("sslMode", env.getOrDefault("DB_SSL_MODE", "VERIFY_IDENTITY"));
        result.put("trustStoreConfigured", trustStore != null && !trustStore.isBlank());
        long started = System.nanoTime();
        try (Connection connection = dataSource(env).getConnection()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                result.put("select1", scalar(statement, "SELECT 1"));
                result.put("selectDatabase", scalar(statement, "SELECT DATABASE()"));
                result.put("tlsVersion", status(statement, "Ssl_version"));
                result.put("tlsCipher", status(statement, "Ssl_cipher"));
            }
            result.put("connected", true);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("errorType", causeChain(e));
            if (e instanceof SQLException sql) {
                result.put("sqlState", sql.getSQLState());
                result.put("errorCode", sql.getErrorCode());
            }
            result.put("errorMessage", mask(env, rootMessage(e)));
        }
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private static DataSource dataSource(Map<String, String> env) throws Exception {
        Class<?> registration;
        try {
            registration = Class.forName("org.hmb.config.RegistrationDataSource");
        } catch (ClassNotFoundException notRegistrationModule) {
            return DatabaseConfig.getDataSource();
        }
        Method get = registration.getMethod("get", Map.class);
        return (DataSource) get.invoke(null, env);
    }

    private static Object scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private static Object status(Statement statement, String variable) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SHOW SESSION STATUS LIKE '" + variable + "'")) {
            return rs.next() ? rs.getString(2) : null;
        }
    }

    private static String causeChain(Throwable t) {
        StringBuilder chain = new StringBuilder(t.getClass().getSimpleName());
        Throwable cause = t.getCause();
        for (int depth = 0; cause != null && depth < 6; depth++) {
            chain.append(" <- ").append(cause.getClass().getSimpleName());
            cause = cause.getCause();
        }
        return chain.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        for (int depth = 0; root.getCause() != null && depth < 6; depth++) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : t.getMessage();
    }

    static String mask(Map<String, String> env, String message) {
        if (message == null) {
            return null;
        }
        String masked = message;
        for (String key : new String[] {"DB_PASSWORD", "DB_USERNAME", "DB_HOST", "DB_TRUSTSTORE_PASSWORD"}) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                masked = masked.replace(value, "***");
            }
        }
        return masked.length() > 400 ? masked.substring(0, 400) + "..." : masked;
    }
}
