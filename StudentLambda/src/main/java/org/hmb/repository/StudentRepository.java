package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads students. Stateless; the caller owns the connection. */
public class StudentRepository {

    private static final String ACTIVE_STUDENT_SQL = """
            SELECT 1
            FROM students s
            JOIN users u ON u.user_id = s.student_id
            WHERE s.student_id = ?
              AND u.is_deleted = FALSE
            """;

    /** @return true if the student exists and the underlying user is not soft-deleted */
    public boolean isActiveStudent(Connection connection, long studentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_STUDENT_SQL)) {
            statement.setLong(1, studentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
