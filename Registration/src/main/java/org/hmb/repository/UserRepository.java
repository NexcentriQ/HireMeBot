package org.hmb.repository;

import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.NewStudentUser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

/** {@code users} reads and the registration insert. Stateless; the caller owns the connection and transaction. */
public class UserRepository {

    /**
     * Uses the {@code UNIQUE(email)} index; the LEFT JOINs on the role tables' primary keys add the account type
     * (student, recruiter, manager).
     */
    static final String FIND_BY_EMAIL_SQL = """
            SELECT u.user_id, u.account_status, u.is_deleted, s.student_id, r.recruiter_id, m.manager_id
            FROM users u
            LEFT JOIN students s ON s.student_id = u.user_id
            LEFT JOIN recruiters r ON r.recruiter_id = u.user_id
            LEFT JOIN managers m ON m.manager_id = u.user_id
            WHERE u.email = ?
            """;

    /**
     * {@code email_verified = TRUE}: the backend verified the email through Cognito/Google before getting here.
     * {@code phone_verified = FALSE}: there is no real SMS verification yet (a mock OTP never counts).
     */
    static final String INSERT_SQL = """
            INSERT INTO users (
                first_name,
                last_name,
                email,
                phone,
                email_verified,
                phone_verified,
                account_status,
                terms_accepted_at,
                terms_version,
                is_deleted
            )
            VALUES (?, ?, ?, ?, TRUE, FALSE, ?, ?, ?, FALSE)
            """;

    /** The account that owns this email (deleted or not, since the unique constraint covers deleted rows too). */
    public Optional<ExistingAccount> findByEmail(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_EMAIL_SQL)) {
            statement.setString(1, email);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(existingAccount(rs)) : Optional.empty();
            }
        }
    }

    /** @return the database-generated {@code user_id} */
    public long insert(Connection connection, NewStudentUser user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, user.firstName());
            statement.setString(2, user.lastName());
            statement.setString(3, user.email());
            statement.setString(4, user.phone());
            statement.setString(5, user.status().name());
            // UTC instant; the pool forces the session time zone to UTC, and TIMESTAMP is stored as UTC.
            statement.setTimestamp(6, Timestamp.from(user.termsAcceptedAt()));
            statement.setString(7, user.termsVersion());
            expectOneRow(statement.executeUpdate(), "users");
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("INSERT INTO users returned no generated key");
                }
                long userId = keys.getLong(1);
                if (userId <= 0) {
                    throw new SQLException("INSERT INTO users returned an invalid generated key");
                }
                return userId;
            }
        }
    }

    static ExistingAccount existingAccount(ResultSet rs) throws SQLException {
        return new ExistingAccount(
                rs.getLong("user_id"),
                accountStatus(rs),
                rs.getBoolean("is_deleted"),
                rs.getObject("student_id") != null,
                rs.getObject("recruiter_id") != null,
                rs.getObject("manager_id") != null);
    }

    static AccountStatus accountStatus(ResultSet rs) throws SQLException {
        String status = rs.getString("account_status");
        return status == null ? AccountStatus.PENDING : AccountStatus.valueOf(status);
    }

    static void expectOneRow(int rows, String table) throws SQLException {
        if (rows != 1) {
            throw new SQLException("INSERT INTO " + table + " affected " + rows + " rows, expected 1");
        }
    }
}
