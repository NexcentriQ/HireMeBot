package org.hmb.repository;

import org.hmb.dto.AssignedStudentDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
     * Only the columns the response needs (no phone or verification flags). Filtered by recruiter, so another
     * recruiter's students can never appear. Not paginated: a recruiter's student list is small.
     */
    private static final String ASSIGNED_STUDENTS_SQL = """
            SELECT
                s.student_id,
                u.first_name,
                u.last_name,
                u.email,
                rsa.assigned_at
            FROM recruiter_student_assignments rsa
            JOIN students s ON s.student_id = rsa.student_id
            JOIN users u ON u.user_id = s.student_id
            WHERE rsa.recruiter_id = ?
              AND u.is_deleted = FALSE
            ORDER BY
                u.first_name ASC,
                u.last_name ASC,
                s.student_id ASC
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

    /**
     * Lists the recruiter's assigned students whose user is not soft-deleted, in the database's alphabetical order
     * (first name, last name, then id). The recruiter itself is not validated here; see
     * {@link RecruiterRepository#isActiveRecruiter}.
     *
     * @return the students, or an empty list when none are assigned
     */
    public List<AssignedStudentDto> findActiveStudents(Connection connection, long recruiterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ASSIGNED_STUDENTS_SQL)) {
            statement.setLong(1, recruiterId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AssignedStudentDto> students = new ArrayList<>();
                while (rs.next()) {
                    students.add(AssignedStudentDto.of(
                            rs.getLong("student_id"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getString("email"),
                            rs.getObject("assigned_at", LocalDateTime.class)));
                }
                return students;
            }
        }
    }
}
