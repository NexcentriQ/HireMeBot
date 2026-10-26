package org.hmb.repository;

import org.hmb.TestData;
import org.hmb.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationRepositoryTest {

    @Mock Connection connection;
    @Mock PreparedStatement statement;

    /** The unique (student_id, job_link_hash) violation must surface as a 409. */
    @Test
    void duplicateKeyBecomes409() throws SQLException {
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
        when(statement.executeUpdate())
                .thenThrow(new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062));

        ApiException e = assertThrows(ApiException.class,
                () -> new ApplicationRepository().insert(connection, TestData.RECRUITER_ID, TestData.validRequest()));

        assertEquals(409, e.getStatusCode());
    }
}
