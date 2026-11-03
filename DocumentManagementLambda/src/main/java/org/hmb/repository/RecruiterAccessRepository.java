package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The two authorization lookups the document APIs need. Module-local copies of the equivalent RecruiterLambda queries
 * (the Lambdas ship as separate jars); they can move to {@code common} later. Stateless; the caller owns the connection.
 */
public class RecruiterAccessRepository {

    /** The recruiter role: an existing recruiter whose user is not soft-deleted. Student and manager ids fail it. */
    private static final String ACTIVE_RECRUITER_SQL = """
            SELECT r.recruiter_id
            FROM recruiters r
            JOIN users u ON u.user_id = r.recruiter_id
            WHERE r.recruiter_id = ?
              AND u.is_deleted = FALSE
            """;

    /** The recruiter is assigned to the student, and neither user is soft-deleted. */
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

    public boolean isActiveRecruiter(Connection connection, long recruiterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_RECRUITER_SQL)) {
            statement.setLong(1, recruiterId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

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
