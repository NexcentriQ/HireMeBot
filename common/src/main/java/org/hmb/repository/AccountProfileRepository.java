package org.hmb.repository;

import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * The one read that turns a verified Cognito subject into an internal account, used by the Registration lookups and by
 * {@link org.hmb.security.RecruiterAuthenticator} (so the sign-in endpoints and the recruiter APIs can never disagree
 * about who an identity is). Read-only; the caller owns the connection.
 *
 * <p>The query is driven by {@code uq_cognito_sub} (at most one row), joins {@code users} on its primary key, and LEFT
 * JOINs {@code students}, {@code recruiters} and {@code managers} on their primary keys, so an identity of any role is
 * found and reported with its role flags instead of being mistaken for an unregistered user. It deliberately has no
 * {@code provider} or role filter: the caller decides what an identity of another role or provider means.
 */
public class AccountProfileRepository {

    static final String FIND_BY_COGNITO_SUB_SQL = """
            SELECT
                u.user_id,
                u.first_name,
                u.last_name,
                u.email,
                u.phone,
                u.email_verified,
                u.phone_verified,
                u.account_status,
                u.is_deleted,
                s.student_id,
                r.recruiter_id,
                m.manager_id,
                ui.provider
            FROM user_identities ui
            INNER JOIN users u
                ON u.user_id = ui.user_id
            LEFT JOIN students s
                ON s.student_id = u.user_id
            LEFT JOIN recruiters r
                ON r.recruiter_id = u.user_id
            LEFT JOIN managers m
                ON m.manager_id = u.user_id
            WHERE ui.cognito_sub = ?
            """;

    public Optional<AccountProfile> findByCognitoSub(Connection connection, String cognitoSub) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_COGNITO_SUB_SQL)) {
            statement.setString(1, cognitoSub);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String status = rs.getString("account_status");
                return Optional.of(new AccountProfile(
                        rs.getLong("user_id"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getBoolean("email_verified"),
                        rs.getBoolean("phone_verified"),
                        status == null ? AccountStatus.PENDING : AccountStatus.valueOf(status),
                        rs.getBoolean("is_deleted"),
                        rs.getObject("student_id") != null,
                        rs.getObject("recruiter_id") != null,
                        rs.getObject("manager_id") != null,
                        IdentityProvider.valueOf(rs.getString("provider"))));
            }
        }
    }
}
