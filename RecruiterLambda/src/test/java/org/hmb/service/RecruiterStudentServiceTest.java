package org.hmb.service;

import org.hmb.dto.AssignedStudentDto;
import org.hmb.dto.AssignedStudentsResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.RecruiterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecruiterStudentServiceTest {

    private static final long RECRUITER_ID = 1000000001L;

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock RecruiterRepository recruiters;
    @Mock AssignmentRepository assignments;

    private RecruiterStudentService service;

    @BeforeEach
    void setUp() {
        service = new RecruiterStudentService(dataSource, recruiters, assignments);
    }

    private static AssignedStudentDto student(long id, String first, String last) {
        return AssignedStudentDto.of(id, first, last, first.toLowerCase() + "@example.test",
                LocalDateTime.parse("2026-10-20T09:30:00"));
    }

    private void activeRecruiter() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(recruiters.isActiveRecruiter(connection, RECRUITER_ID)).thenReturn(true);
    }

    @Test
    void activeRecruiterWithThreeStudentsGetsThreeAndTheCountMatches() throws SQLException {
        activeRecruiter();
        List<AssignedStudentDto> three = List.of(
                student(1000000004L, "Alice", "Adams"),
                student(1000000002L, "Bob", "Baker"),
                student(1000000003L, "Carol", "Clark"));
        when(assignments.findActiveStudents(connection, RECRUITER_ID)).thenReturn(three);

        AssignedStudentsResponse.Data data = service.getAssignedStudents(RECRUITER_ID).data();

        assertEquals(RECRUITER_ID, data.recruiterId());
        assertEquals(3, data.totalStudents());
        assertEquals(data.students().size(), data.totalStudents());
        assertEquals(three, data.students());
        verify(connection).close();
    }

    @Test
    void repositoryOrderIsPreservedNotResorted() throws SQLException {
        activeRecruiter();
        // The database applies ORDER BY; the service must not reorder what it is given.
        List<AssignedStudentDto> inDbOrder = List.of(
                student(3L, "Amy", "Zimmer"), student(1L, "Bob", "Adams"), student(2L, "Cara", "Baker"));
        when(assignments.findActiveStudents(connection, RECRUITER_ID)).thenReturn(inDbOrder);

        List<Long> ids = service.getAssignedStudents(RECRUITER_ID).data().students().stream()
                .map(AssignedStudentDto::studentId).toList();

        assertEquals(List.of(3L, 1L, 2L), ids);
    }

    @Test
    void recruiterWithNoAssignmentsGetsAnEmptyList() throws SQLException {
        activeRecruiter();
        when(assignments.findActiveStudents(connection, RECRUITER_ID)).thenReturn(List.of());

        AssignedStudentsResponse response = service.getAssignedStudents(RECRUITER_ID);

        assertTrue(response.success());
        assertEquals(0, response.data().totalStudents());
        assertTrue(response.data().students().isEmpty());
    }

    /** Nonexistent and soft-deleted recruiters are both "not active" to the repository. */
    @Test
    void inactiveOrUnknownRecruiterIs403AndNoStudentsAreRead() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(recruiters.isActiveRecruiter(connection, RECRUITER_ID)).thenReturn(false);

        ApiException e = assertThrows(ApiException.class, () -> service.getAssignedStudents(RECRUITER_ID));

        assertEquals(403, e.getStatusCode());
        assertEquals("FORBIDDEN", e.getErrorCode());
        verify(assignments, never()).findActiveStudents(connection, RECRUITER_ID);
        verify(connection).close();
    }

    @Test
    void databaseUnavailableIs503() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool timeout", "08001"));

        assertEquals(503, assertThrows(ApiException.class,
                () -> service.getAssignedStudents(RECRUITER_ID)).getStatusCode());
        verifyNoInteractions(recruiters, assignments);
    }

    @Test
    void sqlErrorIs500AndDoesNotLeakDetails() throws SQLException {
        activeRecruiter();
        when(assignments.findActiveStudents(connection, RECRUITER_ID))
                .thenThrow(new SQLException("Unknown column 'secret_col' in 'field list'", "42S22", 1054));

        ApiException e = assertThrows(ApiException.class, () -> service.getAssignedStudents(RECRUITER_ID));

        assertEquals(500, e.getStatusCode());
        assertTrue(!e.getMessage().contains("secret_col"));
    }

    // ---- DTO mapping ----------------------------------------------------------------------------------------

    @Test
    void fullNameIsTheTrimmedFirstAndLastNameJoinedByOneSpace() {
        AssignedStudentDto dto = AssignedStudentDto.of(1L, "  John ", " Smith  ", "j@example.test", null);

        assertEquals("John", dto.firstName());
        assertEquals("Smith", dto.lastName());
        assertEquals("John Smith", dto.fullName());
    }

    @Test
    void assignedAtIsAUtcInstantAndNullStaysNull() {
        assertEquals("2026-10-20T09:30:00Z",
                AssignedStudentDto.of(1L, "A", "B", "e", LocalDateTime.parse("2026-10-20T09:30:00")).assignedAt());
        assertEquals(null, AssignedStudentDto.of(1L, "A", "B", "e", null).assignedAt());
    }
}
