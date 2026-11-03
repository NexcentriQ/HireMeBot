package org.hmb.service;

import org.hmb.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

/**
 * Turns infrastructure failures into API errors. Callers never see SQL, S3 or stack-trace detail: connectivity or
 * throttling problems become a retryable 503, everything else a generic 500, and the technical cause is only logged.
 */
final class FailureMapper {

    private static final Logger LOG = LoggerFactory.getLogger(FailureMapper.class);

    private FailureMapper() {
    }

    static ApiException fromSql(SQLException e) {
        if (isConnectivityProblem(e)) {
            LOG.error("Database unavailable: {}", e.toString());
            return ApiException.serviceUnavailable(e);
        }
        LOG.error("Database error (SQLState={}, code={})", e.getSQLState(), e.getErrorCode(), e);
        return ApiException.internal(e);
    }

    static ApiException fromS3(SdkException e) {
        if (e instanceof SdkClientException) {
            LOG.error("S3 unreachable: {}", e.toString());
            return ApiException.serviceUnavailable(e);
        }
        if (e instanceof S3Exception s3 && (s3.statusCode() == 503 || s3.statusCode() == 500 || s3.statusCode() == 429)) {
            LOG.error("S3 temporarily unavailable (status {})", s3.statusCode());
            return ApiException.serviceUnavailable(e);
        }
        LOG.error("S3 error", e);
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
