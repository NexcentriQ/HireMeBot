package org.hmb.repository;

import org.hmb.dto.StudentApplicationDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a student's applications. Stateless; the caller owns the connection.
 *
 * <p>Pagination is intentionally NOT done in SQL: the whole day is returned and split in Java.
 */
public class StudentApplicationRepository {

    /**
     * Half-open range on the raw column so the optimizer can use an index (no {@code DATE(applied_at)}).
     * Sorted newest first; the application id breaks ties between identical timestamps deterministically.
     */
    private static final String APPLICATIONS_SQL = """
            SELECT
                application_id,
                student_id,
                recruiter_id,
                job_title,
                company_name,
                job_location,
                job_link,
                job_source,
                applied_at
            FROM applications
            WHERE student_id = ?
              AND applied_at >= ?
              AND applied_at < ?
            ORDER BY
                applied_at DESC,
                application_id DESC
            """;

    /**
     * @param startUtcInclusive start of the range, as a UTC wall-clock value (matches how applied_at is stored)
     * @param endUtcExclusive   end of the range, exclusive, also UTC
     * @return all matching applications, newest first
     */
    public List<StudentApplicationDto> findByStudentAndRange(Connection connection, long studentId,
                                                             LocalDateTime startUtcInclusive,
                                                             LocalDateTime endUtcExclusive) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(APPLICATIONS_SQL)) {
            statement.setLong(1, studentId);
            // LocalDateTime is sent as-is (no JVM time zone conversion), exactly like the stored UTC values.
            statement.setObject(2, startUtcInclusive);
            statement.setObject(3, endUtcExclusive);
            try (ResultSet rs = statement.executeQuery()) {
                List<StudentApplicationDto> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new StudentApplicationDto(
                            rs.getLong("application_id"),
                            rs.getString("job_title"),
                            rs.getString("company_name"),
                            rs.getString("job_location"),
                            rs.getString("job_link"),
                            rs.getString("job_source"),
                            StudentApplicationDto.formatUtc(rs.getObject("applied_at", LocalDateTime.class))));
                }
                return result;
            }
        }
    }
}
