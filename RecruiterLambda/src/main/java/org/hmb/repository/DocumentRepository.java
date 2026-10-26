package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Looks up {@code object_store_entries} and links them to an application via {@code application_documents}.
 * Stateless; the caller owns the connection and transaction.
 */
public class DocumentRepository {

    private static final String EXISTS_SQL = "SELECT 1 FROM object_store_entries WHERE object_id = ?";

    private static final String INSERT_SQL = """
            INSERT INTO application_documents (application_id, resume_object_id, cover_letter_object_id)
            VALUES (?, ?, ?)
            """;

    public boolean objectExists(Connection connection, long objectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXISTS_SQL)) {
            statement.setLong(1, objectId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** @param coverLetterObjectId may be null */
    public void insert(Connection connection, long applicationId, long resumeObjectId, Long coverLetterObjectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setLong(1, applicationId);
            statement.setLong(2, resumeObjectId);
            if (coverLetterObjectId == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, coverLetterObjectId);
            }
            statement.executeUpdate();
        }
    }
}
