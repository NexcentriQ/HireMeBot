package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** {@code students} insert. {@code student_id} is the same value as {@code users.user_id}. */
public class StudentRepository {

    static final String INSERT_SQL = """
            INSERT INTO students (
                student_id
            )
            VALUES (?)
            """;

    public void insert(Connection connection, long studentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setLong(1, studentId);
            UserRepository.expectOneRow(statement.executeUpdate(), "students");
        }
    }
}
