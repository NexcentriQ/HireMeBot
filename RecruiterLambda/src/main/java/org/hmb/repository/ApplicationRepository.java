package org.hmb.repository;

import org.hmb.dto.RecruiterRequest;
import org.hmb.exception.ApiException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;

/** Inserts rows into {@code applications}. Stateless; the caller owns the connection and transaction. */
public class ApplicationRepository {

    static final int MYSQL_DUPLICATE_ENTRY = 1062;
    static final int MYSQL_FK_VIOLATION_CHILD = 1452;

    /**
     * {@code applied_at}, {@code created_at}, {@code updated_at} and the generated {@code job_link_hash}
     * are left to the database defaults; a new application is always unviewed.
     */
    private static final String INSERT_SQL = """
            INSERT INTO applications (
                student_id, recruiter_id, job_title, company_name, job_location, job_link, job_source, is_viewed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)
            """;

    /**
     * Inserts the application and returns its generated id.
     *
     * @param request an already validated (trimmed) request
     * @throws ApiException 409 when the student already has this job link (unique constraint on
     *                      {@code (student_id, job_link_hash)}); 403 when the composite assignment
     *                      foreign key rejects the row (assignment removed concurrently)
     */
    public long insert(Connection connection, long recruiterId, RecruiterRequest request) throws SQLException {
        RecruiterRequest.Application app = request.application();
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, request.studentId());
            statement.setLong(2, recruiterId);
            statement.setString(3, app.jobTitle());
            statement.setString(4, app.companyName());
            statement.setString(5, app.jobLocation());
            statement.setString(6, app.jobLink());
            statement.setString(7, app.jobSource());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated application_id returned");
                }
                return keys.getLong(1);
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                throw ApiException.conflict("An application for this student and job link already exists", e);
            }
            if (e.getErrorCode() == MYSQL_FK_VIOLATION_CHILD) {
                throw new ApiException(403, "FORBIDDEN",
                        "Recruiter is not assigned to this student", e);
            }
            throw e;
        }
    }
}
