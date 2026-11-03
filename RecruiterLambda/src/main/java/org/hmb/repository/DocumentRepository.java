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

    /** The {@code object_store_entries.document_type} values an application can reference. */
    public enum Kind { RESUME, COVER_LETTER }

    /**
     * An object may be attached to an application only if it belongs to <em>the application's student</em> and has
     * the expected type. A missing object, another student's object and a wrong-type object are indistinguishable to
     * the caller on purpose, so nothing about other students' documents is revealed.
     */
    private static final String BELONGS_TO_STUDENT_SQL = """
            SELECT 1
            FROM object_store_entries
            WHERE object_id = ?
              AND student_id = ?
              AND document_type = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO application_documents (application_id, resume_object_id, cover_letter_object_id)
            VALUES (?, ?, ?)
            """;

    /**
     * @return true if the object exists, belongs to {@code studentId}, and has document type {@code kind}
     */
    public boolean existsForStudent(Connection connection, long objectId, long studentId, Kind kind)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(BELONGS_TO_STUDENT_SQL)) {
            statement.setLong(1, objectId);
            statement.setLong(2, studentId);
            statement.setString(3, kind.name());
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
