package org.hmb.service;

import org.hmb.TestData;
import org.hmb.dto.RecruiterResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.ApplicationRepository;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    private static final long NEW_APPLICATION_ID = 1000000042L;

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock AssignmentRepository assignments;
    @Mock ApplicationRepository applications;
    @Mock DocumentRepository documents;
    @Mock QuestionRepository questions;

    private ApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationService(dataSource, assignments, applications, documents, questions);
    }

    private void connectionAvailable() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
    }

    private void assignedAndDocumentsExist() throws SQLException {
        when(assignments.isActiveAssignment(connection, TestData.RECRUITER_ID, TestData.STUDENT_ID)).thenReturn(true);
        when(documents.objectExists(any(), anyLong())).thenReturn(true);
    }

    private ApiException failure() {
        return assertThrows(ApiException.class,
                () -> service.createApplication(TestData.RECRUITER_ID, TestData.validRequest()));
    }

    @Test
    void validRequestIsCommittedWithDocumentsAndQuestions() throws SQLException {
        connectionAvailable();
        assignedAndDocumentsExist();
        when(applications.insert(eq(connection), eq(TestData.RECRUITER_ID), any())).thenReturn(NEW_APPLICATION_ID);

        RecruiterResponse response = service.createApplication(TestData.RECRUITER_ID, TestData.validRequest());

        assertEquals(NEW_APPLICATION_ID, response.applicationId());
        verify(documents).insert(connection, NEW_APPLICATION_ID, TestData.RESUME_ID, TestData.COVER_LETTER_ID);
        verify(questions).insertAll(connection, NEW_APPLICATION_ID, TestData.validRequest().questions());
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    /** Soft-deleted users are excluded by the assignment query, so they also surface here as "not assigned". */
    @Test
    void unassignedOrSoftDeletedStudentIs403() throws SQLException {
        connectionAvailable();
        when(assignments.isActiveAssignment(connection, TestData.RECRUITER_ID, TestData.STUDENT_ID)).thenReturn(false);

        assertEquals(403, failure().getStatusCode());

        verify(connection).rollback();
        verifyNoInteractions(applications);
    }

    @Test
    void unknownResumeObjectIs403() throws SQLException {
        connectionAvailable();
        when(assignments.isActiveAssignment(connection, TestData.RECRUITER_ID, TestData.STUDENT_ID)).thenReturn(true);
        when(documents.objectExists(connection, TestData.RESUME_ID)).thenReturn(false);

        assertEquals(403, failure().getStatusCode());
        verifyNoInteractions(applications);
    }

    @Test
    void duplicateApplicationIs409AndRolledBack() throws SQLException {
        connectionAvailable();
        assignedAndDocumentsExist();
        when(applications.insert(eq(connection), eq(TestData.RECRUITER_ID), any()))
                .thenThrow(ApiException.conflict("duplicate", null));

        assertEquals(409, failure().getStatusCode());

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void sqlErrorRollsBackAndDoesNotLeakDetails() throws SQLException {
        connectionAvailable();
        assignedAndDocumentsExist();
        when(applications.insert(eq(connection), eq(TestData.RECRUITER_ID), any()))
                .thenThrow(new SQLException("syntax error near 'secret_table'", "42000", 1064));

        ApiException e = failure();

        assertEquals(500, e.getStatusCode());
        assertTrue(!e.getMessage().contains("secret_table"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void databaseUnavailableIs503() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool timeout", "08001"));

        assertEquals(503, failure().getStatusCode());
    }

    /** Concurrent duplicate submissions can deadlock in InnoDB; the transaction is retried. */
    @Test
    void deadlockIsRetried() throws SQLException {
        connectionAvailable();
        assignedAndDocumentsExist();
        when(applications.insert(eq(connection), eq(TestData.RECRUITER_ID), any()))
                .thenThrow(new SQLException("Deadlock found", "40001", 1213))
                .thenReturn(NEW_APPLICATION_ID);

        assertEquals(NEW_APPLICATION_ID,
                service.createApplication(TestData.RECRUITER_ID, TestData.validRequest()).applicationId());
        verify(dataSource, times(2)).getConnection();
    }
}
