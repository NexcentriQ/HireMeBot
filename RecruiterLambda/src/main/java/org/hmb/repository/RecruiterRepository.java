package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads recruiters. Stateless; the caller owns the connection. */
public class RecruiterRepository {

    private static final String ACTIVE_RECRUITER_SQL = """
            SELECT r.recruiter_id
            FROM recruiters r
            JOIN users u ON u.user_id = r.recruiter_id
            WHERE r.recruiter_id = ?
              AND u.is_deleted = FALSE
            """;

    /** @return true if the recruiter exists and the underlying user is not soft-deleted */
    public boolean isActiveRecruiter(Connection connection, long recruiterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIVE_RECRUITER_SQL)) {
            statement.setLong(1, recruiterId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
