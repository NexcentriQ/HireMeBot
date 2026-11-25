package org.hmb.support;

import com.zaxxer.hikari.HikariDataSource;
import org.hmb.config.DatabaseConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * A throwaway MySQL schema for integration tests: {@code hmb_reg_it_<random>} is created on the server named by the
 * {@code DB_*} variables, loaded with the V1 registration DDL ({@code db/registration-schema-v1.sql}) and dropped
 * afterwards. {@code DB_NAME} is ignored, so the tests can never write to {@code hiremebot_db_v1}. The DB user needs
 * CREATE and DROP privileges.
 */
public final class ScratchSchema implements AutoCloseable {

    private static final String PREFIX = "hmb_reg_it_";

    private final Map<String, String> env;
    private final String name;
    private final HikariDataSource dataSource;

    private ScratchSchema(Map<String, String> env, String name, HikariDataSource dataSource) {
        this.env = env;
        this.name = name;
        this.dataSource = dataSource;
    }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS"));
    }

    /** The V1 schema exactly as in hiremebot_db_V1.sql (and the live local database). */
    public static ScratchSchema create() throws SQLException, IOException {
        return create(false);
    }

    /**
     * @param withFullNameIndex also add {@code UNIQUE (first_name, last_name)} the way the owner's DDL declares it (an
     *                          unnamed index, which MySQL names {@code first_name})
     */
    public static ScratchSchema create(boolean withFullNameIndex) throws SQLException, IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        String name = PREFIX + HexFormat.of().formatHex(new SecureRandom().generateSeed(5));
        try (Connection server = serverConnection(env); Statement statement = server.createStatement()) {
            statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            statement.execute("USE `" + name + "`");
            for (String ddl : ddlStatements()) {
                statement.execute(ddl);
            }
            if (withFullNameIndex) {
                statement.execute("ALTER TABLE users ADD UNIQUE (first_name, last_name)");
            }
        }
        env.put("DB_NAME", name);
        return new ScratchSchema(env, name, DatabaseConfig.create(env));
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public String name() {
        return name;
    }

    public long count(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void execute(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    /** Removes every row (the tables stay); AUTO_INCREMENT keeps counting up, which the tests do not rely on. */
    public void clear() throws SQLException {
        execute("DELETE FROM user_identities");
        execute("DELETE FROM students");
        execute("DELETE FROM recruiters");
        execute("DELETE FROM managers");
        execute("DELETE FROM users");
    }

    @Override
    public void close() throws SQLException {
        dataSource.close();
        if (!name.startsWith(PREFIX)) {
            throw new IllegalStateException("Refusing to drop a schema not created by these tests: " + name);
        }
        try (Connection server = serverConnection(env); Statement statement = server.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    private static Connection serverConnection(Map<String, String> env) throws SQLException {
        String url = "jdbc:mysql://" + required(env, "DB_HOST") + ":" + env.getOrDefault("DB_PORT", "3306")
                + "/?sslMode=" + URLEncoder.encode(env.getOrDefault("DB_SSL_MODE", "VERIFY_IDENTITY"),
                StandardCharsets.UTF_8) + "&connectTimeout=5000";
        return DriverManager.getConnection(url, required(env, "DB_USERNAME"), required(env, "DB_PASSWORD"));
    }

    private static String[] ddlStatements() throws IOException {
        try (InputStream in = ScratchSchema.class.getResourceAsStream("/db/registration-schema-v1.sql")) {
            if (in == null) {
                throw new IOException("db/registration-schema-v1.sql not found");
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^\\s*--.*$", "");
            return java.util.Arrays.stream(script.split(";\\s*(\\r?\\n|$)"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + key + " to run the database integration tests");
        }
        return value.trim();
    }
}
