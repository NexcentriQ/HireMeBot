package org.hmb.support;

import com.zaxxer.hikari.HikariDataSource;
import org.hmb.config.DatabaseConfig;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * A throwaway MySQL schema for integration tests: {@code hmb_stu_it_<random>} is created on the server named by the
 * {@code DB_*} variables, loaded with the owner's V1 DDL ({@code hiremebot_db_V1.sql} at the repository root, minus its
 * {@code CREATE DATABASE}/{@code USE}) and dropped afterwards. {@code DB_NAME} is ignored, so these tests can never read
 * or write {@code hiremebot_db_v1}. The DB user needs CREATE and DROP privileges.
 */
public final class ScratchSchema implements AutoCloseable {

    private static final String PREFIX = "hmb_stu_it_";
    /** Maven runs the tests with the module directory as the working directory. */
    private static final Path DDL = Path.of("..", "hiremebot_db_V1.sql");

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

    public static ScratchSchema create() throws SQLException, IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        String name = PREFIX + HexFormat.of().formatHex(new SecureRandom().generateSeed(5));
        try (Connection server = serverConnection(env); Statement statement = server.createStatement()) {
            statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            statement.execute("USE `" + name + "`");
            for (String ddl : ddlStatements()) {
                statement.execute(ddl);
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
        String script = Files.readString(DDL, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*(--|#).*$", "")
                .replaceAll("(?im)^\\s*(CREATE DATABASE|USE)\\b[^;]*;", "");
        return Arrays.stream(script.split(";\\s*(\\r?\\n|$)"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + key + " to run the database integration tests");
        }
        return value.trim();
    }
}
