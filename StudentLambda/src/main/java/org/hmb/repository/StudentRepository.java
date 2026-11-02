package org.hmb.repository;

import org.hmb.dto.StudentProfileDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Reads students. Stateless; the caller owns the connection. */
public class StudentRepository {

    /**
     * Validates the student and loads the profile in one round trip: a row exists only if the student exists and
     * the underlying user is not soft-deleted. Filtered by the validated student id, so another student's row can
     * never be returned. Only the profile columns are selected; no secrets (OTP hashes live in another table anyway).
     */
    private static final String ACTIVE_STUDENT_PROFILE_SQL = """
            SELECT
                u.first_name,
                u.last_name,
                u.email,
                u.phone,
                u.phone_verified
            FROM students s
            JOIN users u ON u.user_id = s.student_id
            WHERE s.student_id = ?
              AND u.is_deleted = FALSE
            """;

    /**
     * @return the student's profile, or empty if the student does not exist or is soft-deleted
     */
    public Optional<StudentProfileDto> findActiveProfile(Connection connection, long studentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_STUDENT_PROFILE_SQL)) {
            statement.setLong(1, studentId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(StudentProfileDto.of(
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        // The column is nullable (DEFAULT FALSE); a NULL reads as false, never as verified.
                        rs.getBoolean("phone_verified")));
            }
        }
    }
}
