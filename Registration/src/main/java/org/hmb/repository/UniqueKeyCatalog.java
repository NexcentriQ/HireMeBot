package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells which unique constraint a MySQL duplicate-key error (1062) violated, by the <b>columns</b> of the violated
 * index as read from {@code information_schema.STATISTICS} of the live database, not by a hard-coded index name. So
 * {@code UNIQUE (first_name, last_name)} is recognised whatever the index is called, and is simply never reported when
 * a database does not have it.
 *
 * <p>The index name comes from the end of the driver message ({@code ... for key 'users.first_name'}); only that
 * trailing token is read, so a duplicated value (for example a name containing {@code for key '...'}) cannot spoof it,
 * and the value itself is never logged or returned. Index columns are cached per container and re-read once when an
 * unknown index name shows up (e.g. an index added after start-up).
 */
public class UniqueKeyCatalog {

    /** The unique constraints registration can violate. */
    public enum UniqueKey {
        /** {@code users.email} */
        EMAIL,
        /** {@code users (first_name, last_name)}: present in some databases by owner decision */
        FULL_NAME,
        /** {@code user_identities.cognito_sub} ({@code uq_cognito_sub}) */
        COGNITO_SUBJECT,
        /** {@code user_identities (user_id, provider)} ({@code uq_user_provider}) */
        USER_PROVIDER,
        /** anything else, or the index could not be identified */
        UNKNOWN
    }

    /** The violated index as named by the driver; {@code table} is null on servers older than MySQL 8.0.19. */
    public record ViolatedKey(String table, String index) {
    }

    static final String UNIQUE_INDEX_COLUMNS_SQL = """
            SELECT INDEX_NAME, COLUMN_NAME
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND NON_UNIQUE = 0
            ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """;

    private static final Pattern KEY_AT_END =
            Pattern.compile("for key '(?:([A-Za-z0-9_$]{1,64})\\.)?([A-Za-z0-9_$]{1,64})'\\s*$");
    private static final List<String> TABLES = List.of("users", "user_identities");

    /** table (lower case) -> index name (lower case) -> columns (lower case, index order) */
    private final Map<String, Map<String, List<String>>> cache = new ConcurrentHashMap<>();

    /** The violated index named at the end of a duplicate-key message, if any. */
    public static Optional<ViolatedKey> violatedKey(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                Matcher m = KEY_AT_END.matcher(t.getMessage());
                if (m.find()) {
                    String table = m.group(1) == null ? null : m.group(1).toLowerCase(Locale.ROOT);
                    return Optional.of(new ViolatedKey(table, m.group(2).toLowerCase(Locale.ROOT)));
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    /** Which constraint a table's unique index enforces, judged by its columns only. */
    public static UniqueKey kindOf(String table, List<String> columns) {
        Set<String> set = new HashSet<>(columns);
        if ("users".equals(table)) {
            if (set.equals(Set.of("email"))) {
                return UniqueKey.EMAIL;
            }
            if (set.equals(Set.of("first_name", "last_name"))) {
                return UniqueKey.FULL_NAME;
            }
        } else if ("user_identities".equals(table)) {
            if (set.equals(Set.of("cognito_sub"))) {
                return UniqueKey.COGNITO_SUBJECT;
            }
            if (set.equals(Set.of("user_id", "provider"))) {
                return UniqueKey.USER_PROVIDER;
            }
        }
        return UniqueKey.UNKNOWN;
    }

    /**
     * Classifies a duplicate-key error. Must be called on a connection to the same database, outside the failed
     * transaction (it only reads {@code information_schema}).
     */
    public UniqueKey classify(Connection connection, SQLException duplicate) throws SQLException {
        Optional<ViolatedKey> key = violatedKey(duplicate);
        if (key.isEmpty()) {
            return UniqueKey.UNKNOWN;
        }
        List<String> candidates = key.get().table() != null ? List.of(key.get().table()) : TABLES;
        for (String table : candidates) {
            List<String> columns = columns(connection, table, key.get().index());
            if (columns != null) {
                return kindOf(table, columns);
            }
        }
        return UniqueKey.UNKNOWN;
    }

    private List<String> columns(Connection connection, String table, String index) throws SQLException {
        Map<String, List<String>> indexes = cache.get(table);
        if (indexes == null || !indexes.containsKey(index)) {
            indexes = load(connection, table);
            cache.put(table, indexes);
        }
        return indexes.get(index);
    }

    private static Map<String, List<String>> load(Connection connection, String table) throws SQLException {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(UNIQUE_INDEX_COLUMNS_SQL)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    indexes.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(rs.getString(2).toLowerCase(Locale.ROOT));
                }
            }
        }
        return indexes;
    }
}
