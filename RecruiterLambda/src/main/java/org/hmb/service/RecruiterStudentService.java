package org.hmb.service;

import org.hmb.dto.AssignedStudentDto;
import org.hmb.dto.AssignedStudentsResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.RecruiterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

/**
 * Lists the active students assigned to a recruiter, for the Apply Job form's student dropdown.
 *
 * <p>Read-only: no transaction is needed. The recruiter check and the students query share one connection.
 * Student names and emails are returned to the caller but never logged.
 */
public class RecruiterStudentService {

    private static final Logger LOG = LoggerFactory.getLogger(RecruiterStudentService.class);

    private final DataSource dataSource;
    private final RecruiterRepository recruiters;
    private final AssignmentRepository assignments;

    public RecruiterStudentService(DataSource dataSource, RecruiterRepository recruiters,
                                   AssignmentRepository assignments) {
        this.dataSource = dataSource;
        this.recruiters = recruiters;
        this.assignments = assignments;
    }

    /**
     * @param recruiterId the recruiter acting; currently supplied by the temporary dev header
     * @throws ApiException 403 recruiter does not exist or is soft-deleted, 503 database unavailable, 500 anything
     *                      else
     */
    public AssignedStudentsResponse getAssignedStudents(long recruiterId) {
        List<AssignedStudentDto> students;
        try (Connection connection = dataSource.getConnection()) {
            if (!recruiters.isActiveRecruiter(connection, recruiterId)) {
                throw ApiException.forbidden("Recruiter does not exist or is not active");
            }
            students = assignments.findActiveStudents(connection, recruiterId);
        } catch (ApiException e) {
            LOG.warn("Rejected assigned-students request for recruiter {}: {} {}",
                    recruiterId, e.getStatusCode(), e.getMessage());
            throw e;
        } catch (SQLException e) {
            throw ApplicationService.translate(e);
        } catch (RuntimeException e) {
            LOG.error("Unexpected failure listing assigned students", e);
            throw ApiException.internal(e);
        }

        LOG.info("Recruiter {} has {} active assigned student(s)", recruiterId, students.size());
        return AssignedStudentsResponse.of(recruiterId, students);
    }
}
