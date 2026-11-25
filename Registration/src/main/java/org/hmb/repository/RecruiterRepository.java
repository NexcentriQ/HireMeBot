package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * {@code recruiters} insert. {@code recruiter_id} is the same value as {@code users.user_id}.
 *
 * <p>A new recruiter has {@code manager_id = NULL}: manager assignment is not part of registration, and nothing here
 * ever sets it.
 */
public class RecruiterRepository {

    static final String INSERT_SQL = """
            INSERT INTO recruiters (
                recruiter_id,
                manager_id
            )
            VALUES (?, NULL)
            """;

    public void insert(Connection connection, long recruiterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setLong(1, recruiterId);
            UserRepository.expectOneRow(statement.executeUpdate(), "recruiters");
        }
    }
}
