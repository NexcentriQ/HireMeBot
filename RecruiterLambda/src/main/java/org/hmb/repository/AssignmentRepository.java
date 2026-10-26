package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads recruiter-student assignments. Stateless; the caller owns the connection and transaction. */
public class AssignmentRepository {

    private static final String ACTIVE_ASSIGNMENT_SQL = """
            SELECT 1
            FROM recruiter_student_assignments rsa
            JOIN recruiters r ON r.recruiter_id = rsa.recruiter_id
            JOIN students s ON s.student_id = rsa.student_id
            JOIN users ru ON ru.user_id = r.recruiter_id
            JOIN users su ON su.user_id = s.student_id
            WHERE rsa.recruiter_id = ?
              AND rsa.student_id = ?
              AND ru.is_deleted = FALSE
              AND su.is_deleted = FALSE
            """;

    /**
     * @return true if the recruiter is assigned to the student and neither user is soft-deleted
     */
    public boolean isActiveAssignment(Connection connection, long recruiterId, long studentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_ASSIGNMENT_SQL)) {
            statement.setLong(1, recruiterId);
            statement.setLong(2, studentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
