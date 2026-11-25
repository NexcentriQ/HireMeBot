package org.hmb.repository;

import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Turns a verified Cognito subject into the internal student account. Read-only; the caller owns the connection.
 *
 * <p>The query is driven by {@code uq_cognito_sub} (at most one row), joins {@code users} on its primary key and LEFT
 * JOINs {@code students} on its primary key, so an identity that belongs to another role is found and reported as "not
 * a student" instead of being mistaken for an unregistered user. It reads only {@code user_identities}, {@code users}
 * and {@code students}, and deliberately has no status, deleted or role filter: the caller decides what each means.
 */
public class StudentIdentityRepository {

    /** The account behind a Cognito identity. {@code student} is true when a {@code students} row exists. */
    public record StudentIdentity(long userId, AccountStatus status, boolean deleted, boolean student,
                                  IdentityProvider provider) {
    }

    static final String FIND_BY_COGNITO_SUB_SQL = """
            SELECT
                u.user_id,
                u.account_status,
                u.is_deleted,
                s.student_id,
                ui.provider
            FROM user_identities ui
            INNER JOIN users u
                ON u.user_id = ui.user_id
            LEFT JOIN students s
                ON s.student_id = u.user_id
            WHERE ui.cognito_sub = ?
            """;

    public Optional<StudentIdentity> findByCognitoSub(Connection connection, String cognitoSub) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_COGNITO_SUB_SQL)) {
            statement.setString(1, cognitoSub);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String status = rs.getString("account_status");
                return Optional.of(new StudentIdentity(
                        rs.getLong("user_id"),
                        // The column is NOT NULL DEFAULT 'PENDING'; a NULL could only read as not usable.
                        status == null ? AccountStatus.PENDING : AccountStatus.valueOf(status),
                        rs.getBoolean("is_deleted"),
                        rs.getObject("student_id") != null,
                        IdentityProvider.valueOf(rs.getString("provider"))));
            }
        }
    }
}
