package org.hmb.repository;

import org.hmb.dto.RecruiterRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Inserts rows into {@code application_questions}. Stateless; the caller owns the connection and transaction. */
public class QuestionRepository {

    private static final String INSERT_SQL =
            "INSERT INTO application_questions (application_id, question, answer) VALUES (?, ?, ?)";

    /** Inserts all questions for the application in one batch. Does nothing for an empty list. */
    public void insertAll(Connection connection, long applicationId, List<RecruiterRequest.Question> questions)
            throws SQLException {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (RecruiterRequest.Question q : questions) {
                statement.setLong(1, applicationId);
                statement.setString(2, q.question());
                statement.setString(3, q.answer());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
