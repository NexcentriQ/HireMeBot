package org.hmb.repository;

import org.hmb.dto.DocumentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and inserts {@code object_store_entries}. Stateless; the caller owns the connection.
 *
 * <p>The table is used exactly as it is: no columns or tables are added, and there is no UPDATE or DELETE here.
 * {@code UNIQUE(hashed_value)} (global, across students and document types) and {@code UNIQUE(object_key)} are the
 * authoritative duplicate guards; {@link #insert} lets a violation surface as
 * {@link java.sql.SQLIntegrityConstraintViolationException} (MySQL error 1062) so the service can resolve the race by
 * re-querying, never by assuming an earlier SELECT was atomic with the INSERT.
 */
public class DocumentRepository {

    private static final String COLUMNS = "object_id, student_id, document_type, object_key, hashed_value";

    private static final String BY_CHECKSUM_SQL =
            "SELECT " + COLUMNS + " FROM object_store_entries WHERE hashed_value = ?";

    private static final String BY_KEY_SQL =
            "SELECT " + COLUMNS + " FROM object_store_entries WHERE object_key = ?";

    private static final String INSERT_SQL = """
            INSERT INTO object_store_entries (student_id, document_type, object_key, hashed_value)
            VALUES (?, ?, ?, ?)
            """;

    /** Keyset-paged scan of recent rows for the reconciler (read-only). */
    private static final String RECENT_AFTER_SQL = "SELECT " + COLUMNS
            + " FROM object_store_entries WHERE created_at >= ? AND object_id > ? ORDER BY object_id LIMIT ?";

    /** @param checksum 64 lowercase hex characters */
    public Optional<StoredDocument> findByChecksum(Connection connection, String checksum) throws SQLException {
        return findOne(connection, BY_CHECKSUM_SQL, checksum);
    }

    public Optional<StoredDocument> findByObjectKey(Connection connection, String objectKey) throws SQLException {
        return findOne(connection, BY_KEY_SQL, objectKey);
    }

    /**
     * Inserts the row and returns its generated {@code object_id}.
     *
     * @throws java.sql.SQLIntegrityConstraintViolationException when the checksum or the key already exists
     */
    public long insert(Connection connection, long studentId, DocumentType type, String objectKey, String checksum)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, studentId);
            statement.setString(2, type.name());
            statement.setString(3, objectKey);
            statement.setString(4, checksum);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated object_id returned");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * Rows created at or after {@code createdSinceUtc} with an id greater than {@code afterId}, oldest id first.
     *
     * @param createdSinceUtc UTC wall-clock value (connections run with a UTC session time zone)
     */
    public List<StoredDocument> findRecentAfter(Connection connection, LocalDateTime createdSinceUtc, long afterId,
                                                int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RECENT_AFTER_SQL)) {
            statement.setObject(1, createdSinceUtc);
            statement.setLong(2, afterId);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<StoredDocument> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return rows;
            }
        }
    }

    private Optional<StoredDocument> findOne(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static StoredDocument map(ResultSet rs) throws SQLException {
        return new StoredDocument(
                rs.getLong("object_id"),
                rs.getLong("student_id"),
                DocumentType.valueOf(rs.getString("document_type")),
                rs.getString("object_key"),
                rs.getString("hashed_value"));
    }
}
