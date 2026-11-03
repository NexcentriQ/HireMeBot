package org.hmb.service;

import org.hmb.Fixtures;
import org.hmb.config.DocumentUploadConfig;
import org.hmb.dto.DocumentType;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.StoredDocument;
import org.hmb.service.DocumentReconciliationService.Result;
import org.hmb.storage.ObjectMissingException;
import org.hmb.storage.S3DocumentStorageService;
import org.hmb.storage.S3DocumentStorageService.ObjectState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.exception.SdkClientException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentReconciliationServiceTest {

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock DocumentRepository documents;
    @Mock S3DocumentStorageService storage;

    private final Clock clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(documents.findRecentAfter(any(), any(), anyLong(), anyInt())).thenReturn(List.of());
    }

    private DocumentReconciliationService service(Map<String, String> env) {
        return new DocumentReconciliationService(dataSource, DocumentUploadConfig.fromEnv(env), documents, storage, clock);
    }

    private static StoredDocument doc(long id) {
        return new StoredDocument(id, Fixtures.STUDENT_ID, DocumentType.RESUME,
                "students/1000000005/resumes/" + id + ".pdf", Fixtures.SHA256_HEX);
    }

    private static String key(long id) {
        return doc(id).objectKey();
    }

    private void firstBatch(StoredDocument... docs) throws SQLException {
        when(documents.findRecentAfter(eq(connection), any(), eq(0L), anyInt())).thenReturn(List.of(docs));
    }

    private static long plenty() {
        return 600_000;
    }

    @Test
    void aRowWhoseObjectIsStillPendingIsRetaggedRegistered() throws SQLException {
        firstBatch(doc(1));
        when(storage.state(key(1))).thenReturn(ObjectState.PENDING);

        Result result = service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        assertEquals(new Result(1, 1, 0, 0, true), result);
        verify(storage).markRegistered(key(1));
        assertFalse(result.needsAttention(), "healing is the normal job, not a problem to alert on");
    }

    @Test
    void alreadyRegisteredAndUntaggedObjectsAreLeftAlone() throws SQLException {
        firstBatch(doc(1), doc(2));
        when(storage.state(key(1))).thenReturn(ObjectState.REGISTERED);
        when(storage.state(key(2))).thenReturn(ObjectState.UNTAGGED);

        Result result = service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        assertEquals(new Result(2, 0, 0, 0, true), result);
        verify(storage, never()).markRegistered(anyString());
    }

    @Test
    void aRowWhoseObjectIsMissingIsReportedAndNotRepaired() throws SQLException {
        firstBatch(doc(1), doc(2));
        when(storage.state(key(1))).thenReturn(ObjectState.MISSING);
        when(storage.state(key(2))).thenReturn(ObjectState.PENDING);
        doThrow(new ObjectMissingException()).when(storage).markRegistered(key(2));   // vanished between the two calls

        Result result = service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        assertEquals(2, result.missing());
        assertTrue(result.needsAttention());
    }

    @Test
    void aFailureOnOneRowIsCountedAndDoesNotStopTheRest() throws SQLException {
        firstBatch(doc(1), doc(2), doc(3));
        when(storage.state(key(1))).thenThrow(SdkClientException.create("throttled"));
        when(storage.state(key(2))).thenReturn(ObjectState.PENDING);
        when(storage.state(key(3))).thenReturn(ObjectState.PENDING);

        Result result = service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        assertEquals(new Result(3, 2, 0, 1, true), result);
        assertTrue(result.needsAttention());
        verify(storage).markRegistered(key(2));
        verify(storage).markRegistered(key(3));
    }

    @Test
    void itOnlyEverReadsTagsAndWritesTheRegisteredTagAndNeverDeletesAnything() throws SQLException {
        firstBatch(doc(1), doc(2));
        when(storage.state(key(1))).thenReturn(ObjectState.PENDING);
        when(storage.state(key(2))).thenReturn(ObjectState.MISSING);

        service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        // The storage service has no delete operation at all; assert the complete set of interactions.
        verify(storage).state(key(1));
        verify(storage).markRegistered(key(1));
        verify(storage).state(key(2));
        verifyNoMoreInteractions(storage);
        // And MySQL is only ever read.
        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void anEmptyTableIsACleanRun() {
        Result result = service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        assertEquals(new Result(0, 0, 0, 0, true), result);
        assertFalse(result.needsAttention());
    }

    @Test
    void rowsAreReadInKeysetBatchesUntilAShortBatch() throws SQLException {
        when(documents.findRecentAfter(eq(connection), any(), eq(0L), eq(2))).thenReturn(List.of(doc(1), doc(2)));
        when(documents.findRecentAfter(eq(connection), any(), eq(2L), eq(2))).thenReturn(List.of(doc(3), doc(4)));
        when(documents.findRecentAfter(eq(connection), any(), eq(4L), eq(2))).thenReturn(List.of(doc(5)));
        for (long id = 1; id <= 5; id++) {
            when(storage.state(key(id))).thenReturn(ObjectState.REGISTERED);
        }

        Result result = service(Map.of("RECONCILE_BATCH_SIZE", "2")).reconcile(DocumentReconciliationServiceTest::plenty);

        assertEquals(5, result.checked());
        assertTrue(result.complete());
        verify(documents).findRecentAfter(eq(connection), any(), eq(4L), eq(2));
        verify(documents, never()).findRecentAfter(any(), any(), eq(5L), anyInt());   // stopped after the short batch
    }

    @Test
    void theLookbackIsShorterThanTheSevenDayExpiryOfPendingObjects() throws SQLException {
        service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty);

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documents).findRecentAfter(eq(connection), since.capture(), eq(0L), eq(500));
        assertEquals(LocalDateTime.of(2026, 10, 29, 12, 0), since.getValue(), "5 days before the clock, as a UTC value");
    }

    @Test
    void itStopsEarlyAndSaysSoWhenTheLambdaIsNearItsDeadline() throws SQLException {
        firstBatch(doc(1), doc(2), doc(3));
        when(storage.state(anyString())).thenReturn(ObjectState.REGISTERED);
        long[] remaining = {600_000, 600_000, 1_000};   // plenty for two rows, then under the 30 s margin

        int[] calls = {0};
        Result result = service(Map.of()).reconcile(() -> remaining[Math.min(calls[0]++, remaining.length - 1)]);

        assertFalse(result.complete());
        assertTrue(result.checked() < 3);
        assertTrue(result.needsAttention(), "an incomplete pass must be visible");
    }

    @Test
    void theDatabaseBeingDownFailsTheRunSoItIsRetriedAndAlarmed() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("down", "08001"));

        assertThrows(RuntimeException.class, () -> service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty));
    }

    @Test
    void theSummaryNeverContainsKeysOrUrls() throws SQLException {
        firstBatch(doc(1));
        when(storage.state(key(1))).thenReturn(ObjectState.PENDING);

        String summary = service(Map.of()).reconcile(DocumentReconciliationServiceTest::plenty).summary();

        assertEquals("reconciliation checked=1 healed=1 missing=0 errors=0 complete=true", summary);
    }
}
