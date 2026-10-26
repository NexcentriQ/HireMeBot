package org.hmb.service;

import org.hmb.dto.RecruiterRequest;
import org.hmb.dto.RecruiterResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.ApplicationRepository;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import javax.sql.DataSource;

/**
 * Records an application a recruiter made externally on behalf of an assigned student.
 *
 * <p>All database work runs in a single transaction: assignment check, document existence check, application
 * insert, document link insert and question/answer inserts. Any failure rolls the whole thing back.
 */
public class ApplicationService {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationService.class);

    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MAX_ATTEMPTS = 3;

    private final DataSource dataSource;
    private final AssignmentRepository assignments;
    private final ApplicationRepository applications;
    private final DocumentRepository documents;
    private final QuestionRepository questions;

    public ApplicationService(DataSource dataSource,
                              AssignmentRepository assignments,
                              ApplicationRepository applications,
                              DocumentRepository documents,
                              QuestionRepository questions) {
        this.dataSource = dataSource;
        this.assignments = assignments;
        this.applications = applications;
        this.documents = documents;
        this.questions = questions;
    }

    /**
     * @param recruiterId the recruiter acting; currently supplied by the temporary dev header
     * @param request     the raw request; validated here
     * @throws ApiException 400 invalid request, 403 not assigned or unknown document, 409 duplicate,
     *                      503 database unavailable, 500 anything else
     */
    public RecruiterResponse createApplication(long recruiterId, RecruiterRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Request body is required");
        }
        RecruiterRequest valid = request.validated();

        try {
            return executeWithDeadlockRetry(recruiterId, valid);
        } catch (ApiException e) {
            LOG.warn("Rejected application for student {} by recruiter {}: {} {}",
                    valid.studentId(), recruiterId, e.getStatusCode(), e.getMessage());
            throw e;
        } catch (SQLException e) {
            throw translate(e);
        } catch (RuntimeException e) {
            LOG.error("Unexpected failure creating application", e);
            throw ApiException.internal(e);
        }
    }

    /**
     * Concurrent inserts of the same unique key can make InnoDB abort one transaction with a deadlock (1213).
     * The whole transaction is safe to repeat; on retry the loser then sees the committed row and gets a 409.
     */
    private RecruiterResponse executeWithDeadlockRetry(long recruiterId, RecruiterRequest request)
            throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return executeOnce(recruiterId, request);
            } catch (SQLException e) {
                if (!isDeadlock(e) || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                LOG.warn("Deadlock on attempt {}/{}; retrying transaction", attempt, MAX_ATTEMPTS);
            }
        }
    }

    private RecruiterResponse executeOnce(long recruiterId, RecruiterRequest request) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long applicationId = createInTransaction(connection, recruiterId, request);
                connection.commit();
                LOG.info("Recorded application {} for student {} by recruiter {}",
                        applicationId, request.studentId(), recruiterId);
                return RecruiterResponse.created(applicationId);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            }
        }
    }

    private static boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == MYSQL_DEADLOCK || "40001".equals(e.getSQLState());
    }

    private long createInTransaction(Connection connection, long recruiterId, RecruiterRequest request)
            throws SQLException {
        long studentId = request.studentId();

        if (!assignments.isActiveAssignment(connection, recruiterId, studentId)) {
            throw ApiException.forbidden("Recruiter is not assigned to this student");
        }

        RecruiterRequest.Documents docs = request.documents();
        if (!documents.objectExists(connection, docs.resumeObjectId())) {
            throw ApiException.forbidden("Resume object does not exist");
        }
        Long coverLetterId = docs.coverLetterObjectId();
        if (coverLetterId != null && !documents.objectExists(connection, coverLetterId)) {
            throw ApiException.forbidden("Cover letter object does not exist");
        }

        long applicationId = applications.insert(connection, recruiterId, request);
        documents.insert(connection, applicationId, docs.resumeObjectId(), coverLetterId);
        questions.insertAll(connection, applicationId, request.questions());
        return applicationId;
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            LOG.error("Rollback failed after: {}", cause.toString(), rollbackFailure);
        }
    }

    /** Maps JDBC failures to HTTP errors: connectivity problems become 503, everything else 500. */
    static ApiException translate(SQLException e) {
        if (isConnectivityProblem(e)) {
            LOG.error("Database unavailable: {}", e.toString());
            return ApiException.serviceUnavailable(e);
        }
        LOG.error("Database error (SQLState={}, code={})", e.getSQLState(), e.getErrorCode(), e);
        return ApiException.internal(e);
    }

    private static boolean isConnectivityProblem(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
