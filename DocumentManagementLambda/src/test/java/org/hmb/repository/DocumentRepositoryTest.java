package org.hmb.repository;

import org.hmb.Fixtures;
import org.hmb.dto.DocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JDBC mapping and query shape without a database. The real SQL is exercised by the integration test. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentRepositoryTest {

    @Mock Connection connection;
    @Mock PreparedStatement statement;
    @Mock ResultSet rs;

    private final DocumentRepository repository = new DocumentRepository();

    private void stubRow(long id, long student, String type, String key, String hash) throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("object_id")).thenReturn(id);
        when(rs.getLong("student_id")).thenReturn(student);
        when(rs.getString("document_type")).thenReturn(type);
        when(rs.getString("object_key")).thenReturn(key);
        when(rs.getString("hashed_value")).thenReturn(hash);
    }

    private String lastSql() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue().replaceAll("\\s+", " ");
    }

    @Test
    void findByChecksumMapsTheRowAndBindsTheChecksum() throws SQLException {
        stubRow(1000000001L, Fixtures.STUDENT_ID, "RESUME", "students/5/resumes/a.pdf", Fixtures.SHA256_HEX);

        Optional<StoredDocument> found = repository.findByChecksum(connection, Fixtures.SHA256_HEX);

        assertEquals(Optional.of(new StoredDocument(1000000001L, Fixtures.STUDENT_ID, DocumentType.RESUME,
                "students/5/resumes/a.pdf", Fixtures.SHA256_HEX)), found);
        verify(statement).setString(1, Fixtures.SHA256_HEX);
        String sql = lastSql();
        assertTrue(sql.contains("WHERE hashed_value = ?"), sql);
        assertTrue(sql.contains("document_type"), sql);
        assertFalse(sql.contains("*"), "no SELECT *");
    }

    @Test
    void findByChecksumReturnsEmptyWhenThereIsNoRow() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertEquals(Optional.empty(), repository.findByChecksum(connection, Fixtures.SHA256_HEX));
    }

    @Test
    void findByObjectKeyBindsTheKey() throws SQLException {
        stubRow(2L, 6L, "COVER_LETTER", "students/6/cover-letters/b.pdf", Fixtures.OTHER_SHA256_HEX);

        StoredDocument found = repository.findByObjectKey(connection, "students/6/cover-letters/b.pdf").orElseThrow();

        assertEquals(DocumentType.COVER_LETTER, found.documentType());
        verify(statement).setString(1, "students/6/cover-letters/b.pdf");
        assertTrue(lastSql().contains("WHERE object_key = ?"));
    }

    @Test
    void insertWritesExactlyTheFourDocumentedColumnsAndReturnsTheGeneratedId() throws SQLException {
        ResultSet keys = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true);
        when(keys.getLong(1)).thenReturn(1000000042L);

        long id = repository.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, "students/5/resumes/a.pdf",
                Fixtures.SHA256_HEX);

        assertEquals(1000000042L, id);
        verify(statement).setLong(1, Fixtures.STUDENT_ID);
        verify(statement).setString(2, "RESUME");
        verify(statement).setString(3, "students/5/resumes/a.pdf");
        verify(statement).setString(4, Fixtures.SHA256_HEX);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture(), eq(Statement.RETURN_GENERATED_KEYS));
        String normalized = sql.getValue().replaceAll("\\s+", " ").trim();
        assertEquals("INSERT INTO object_store_entries (student_id, document_type, object_key, hashed_value) VALUES (?, ?, ?, ?)",
                normalized);
    }

    @Test
    void aDuplicateKeyViolationSurfacesUnchangedSoTheServiceCanResolveTheRace() throws SQLException {
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
        SQLIntegrityConstraintViolationException duplicate =
                new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062);
        when(statement.executeUpdate()).thenThrow(duplicate);

        SQLIntegrityConstraintViolationException thrown = assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> repository.insert(connection, 1L, DocumentType.RESUME, "k", Fixtures.SHA256_HEX));

        assertEquals(1062, thrown.getErrorCode());
    }

    @Test
    void theReconcilerQueryIsAKeysetScanOfRecentRowsOrderedById() throws SQLException {
        stubRow(7L, 5L, "RESUME", "k", Fixtures.SHA256_HEX);
        LocalDateTime since = LocalDateTime.of(2026, 10, 29, 12, 0);

        List<StoredDocument> rows = repository.findRecentAfter(connection, since, 6L, 500);

        assertEquals(1, rows.size());
        verify(statement).setObject(1, since);
        verify(statement).setLong(2, 6L);
        verify(statement).setInt(3, 500);
        String sql = lastSql();
        assertTrue(sql.contains("created_at >= ?"), sql);
        assertTrue(sql.contains("object_id > ?"), sql);
        assertTrue(sql.contains("ORDER BY object_id LIMIT ?"), sql);
    }

    @Test
    void thereAreNoUpdateOrDeleteStatementsAnywhereInTheRepository() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/org/hmb/repository/DocumentRepository.java"));

        assertFalse(source.toUpperCase().contains("\"UPDATE "), "the module never updates a row");
        assertFalse(source.toUpperCase().contains("DELETE FROM"), "the module never deletes a row");
    }
}
