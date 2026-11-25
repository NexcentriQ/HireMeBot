package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.repository.UniqueKeyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.regex.Pattern;

/**
 * SQL failure classification. Clients never see SQL or driver detail: connectivity problems become a retryable 503,
 * everything else a generic 500. Integrity-violation messages quote the offending values (names, emails), so for those
 * only the SQLState, error code and key name are logged. For connectivity problems the <em>root cause</em> is logged
 * (a pool timeout alone says nothing about why, e.g. a wrong password, a refused connection or MySQL's "Public Key
 * Retrieval is not allowed"); its message is redacted and truncated first.
 */
final class DatabaseFailures {

    static final int MYSQL_DUPLICATE_ENTRY = 1062;
    static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    static final int MYSQL_DEADLOCK = 1213;

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseFailures.class);
    private static final int MAX_CAUSE_MESSAGE_CHARS = 200;
    /** A JDBC URL can carry a truststore password in its query string; a "password=" fragment can carry any secret. */
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:[a-z0-9]+:\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PARAM = Pattern.compile("(?i)(password|pwd|secret|token)\\s*[=:]\\s*\\S+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DatabaseFailures() {
    }

    static RegistrationException map(SQLException e) {
        if (isConnectivityProblem(e)) {
            LOG.error("Database unavailable: {} (SQLState={}); cause: {}", e.getClass().getSimpleName(), e.getSQLState(),
                    rootCause(e));
            return RegistrationException.serviceUnavailable(e);
        }
        if (isIntegrityViolation(e)) {
            LOG.error("Database integrity error (SQLState={}, code={}, key={})", e.getSQLState(), e.getErrorCode(),
                    duplicateKeyName(e));
        } else {
            LOG.error("Database error (SQLState={}, code={})", e.getSQLState(), e.getErrorCode(), e);
        }
        return RegistrationException.internal(e);
    }

    /**
     * The innermost cause as {@code ClassName[ SQLState=..][: message]}, safe to log: the message is whitespace-collapsed,
     * stripped of JDBC URLs and password-like fragments, and cut to {@value #MAX_CAUSE_MESSAGE_CHARS} characters.
     */
    static String rootCause(Throwable e) {
        Throwable root = e;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 20; depth++) {
            root = root.getCause();
        }
        StringBuilder out = new StringBuilder(root.getClass().getSimpleName());
        if (root instanceof SQLException sql && sql.getSQLState() != null) {
            out.append(" SQLState=").append(sql.getSQLState());
        }
        String message = root.getMessage();
        if (message != null && !message.isBlank()) {
            message = WHITESPACE.matcher(message).replaceAll(" ").strip();
            message = JDBC_URL.matcher(message).replaceAll("jdbc:<redacted>");
            message = SECRET_PARAM.matcher(message).replaceAll("$1=<redacted>");
            if (message.length() > MAX_CAUSE_MESSAGE_CHARS) {
                message = message.substring(0, MAX_CAUSE_MESSAGE_CHARS) + "...";
            }
            out.append(": ").append(message);
        }
        return out.toString();
    }

    static boolean isDuplicateKey(SQLException e) {
        return hasErrorCode(e, MYSQL_DUPLICATE_ENTRY);
    }

    static boolean isLockConflict(SQLException e) {
        return hasErrorCode(e, MYSQL_DEADLOCK) || hasErrorCode(e, MYSQL_LOCK_WAIT_TIMEOUT);
    }

    /**
     * The unique key named at the <b>end</b> of a duplicate-entry message (e.g. {@code users.email}), never the
     * duplicated value, which may itself contain text like {@code for key '...'}.
     */
    static String duplicateKeyName(SQLException e) {
        return UniqueKeyCatalog.violatedKey(e)
                .map(k -> k.table() == null ? k.index() : k.table() + "." + k.index())
                .orElse("unknown");
    }

    private static boolean isIntegrityViolation(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }

    private static boolean hasErrorCode(SQLException e, int code) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == code) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
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
