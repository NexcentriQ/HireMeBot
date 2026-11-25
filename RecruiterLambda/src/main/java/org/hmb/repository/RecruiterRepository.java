package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads recruiters. Stateless; the caller owns the connection. */
public class RecruiterRepository {

    /**
     * An <em>active</em> recruiter: not soft-deleted and {@code account_status = 'ACTIVE'}. New recruiters register as
     * {@code PENDING} and only become ACTIVE when the operator approves them in MySQL, so a pending or suspended
     * recruiter must not pass this check.
     */
    private static final String ACTIVE_RECRUITER_SQL = """
            SELECT r.recruiter_id
            FROM recruiters r
            JOIN users u ON u.user_id = r.recruiter_id
            WHERE r.recruiter_id = ?
              AND u.is_deleted = FALSE
              AND u.account_status = 'ACTIVE'
            """;

    /** @return true if the recruiter exists, is not soft-deleted and has been approved ({@code ACTIVE}) */
    public boolean isActiveRecruiter(Connection connection, long recruiterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_RECRUITER_SQL)) {
            statement.setLong(1, recruiterId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
