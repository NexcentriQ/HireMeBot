package org.hmb.repository;

import org.hmb.dto.StudentProfileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The JDBC mapping and query shape, without a database. The real SQL is exercised by the integration test. */
@ExtendWith(MockitoExtension.class)
class StudentRepositoryTest {

    @Mock Connection connection;
    @Mock PreparedStatement statement;
    @Mock ResultSet rs;

    private final StudentRepository repository = new StudentRepository();

    private void row(String first, String last, String email, String phone, boolean verified) throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("first_name")).thenReturn(first);
        when(rs.getString("last_name")).thenReturn(last);
        when(rs.getString("email")).thenReturn(email);
        when(rs.getString("phone")).thenReturn(phone);
        when(rs.getBoolean("phone_verified")).thenReturn(verified);
    }

    @Test
    void mapsEveryProfileFieldFromTheRow() throws SQLException {
        row("John", "Smith", "john.smith@example.com", "+17135550123", false);

        StudentProfileDto profile = repository.findActiveProfile(connection, 1000000002L).orElseThrow();

        assertEquals("John", profile.firstName());
        assertEquals("Smith", profile.lastName());
        assertEquals("john.smith@example.com", profile.email());
        assertEquals("+17135550123", profile.phoneE164());
        assertEquals(false, profile.phoneVerified());
    }

    @Test
    void phoneVerifiedTrueMapsToTrue() throws SQLException {
        row("A", "B", "a@example.test", "+17135550123", true);

        assertEquals(true, repository.findActiveProfile(connection, 1L).orElseThrow().phoneVerified());
    }

    @Test
    void phoneVerifiedFalseMapsToFalse() throws SQLException {
        row("A", "B", "a@example.test", "+17135550123", false);

        assertEquals(false, repository.findActiveProfile(connection, 1L).orElseThrow().phoneVerified());
    }

    @Test
    void aStoredPhoneInAnyFormatIsReturnedAsStored() throws SQLException {
        row("A", "B", "a@example.test", "7135550123", true);

        assertEquals("7135550123", repository.findActiveProfile(connection, 1L).orElseThrow().phoneE164());
    }

    @Test
    void anEmptyStoredPhoneMapsToNull() throws SQLException {
        row("A", "B", "a@example.test", "", true);

        assertNull(repository.findActiveProfile(connection, 1L).orElseThrow().phoneE164());
    }

    @Test
    void noRowMeansNoProfile() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertEquals(Optional.empty(), repository.findActiveProfile(connection, 1L));
    }

    @Test
    void bindsTheStudentIdAndTheSqlFiltersByItAndExcludesSoftDeletedUsers() throws SQLException {
        row("A", "B", "a@example.test", "+17135550123", false);

        repository.findActiveProfile(connection, 1000000002L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        verify(statement).setLong(1, 1000000002L);
        String normalized = sql.getValue().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE s.student_id = ?"), normalized);
        assertTrue(normalized.contains("u.is_deleted = FALSE"), normalized);
        assertTrue(normalized.contains("u.phone_verified"), normalized);
        // Only the five profile columns, no SELECT *, and nothing secret.
        assertTrue(!normalized.contains("*"), normalized);
        assertTrue(!normalized.toLowerCase().contains("otp"), normalized);
    }

    @Test
    void closesTheStatementAndResultSet() throws SQLException {
        row("A", "B", "a@example.test", "+17135550123", false);

        repository.findActiveProfile(connection, 1L);

        verify(rs).close();
        verify(statement).close();
    }
}
