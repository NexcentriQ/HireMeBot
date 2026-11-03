package org.hmb.service;

import org.hmb.config.DatabaseConfig;
import org.hmb.config.DocumentUploadConfig;
import org.hmb.dto.DocumentType;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.dto.UploadUrlResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.RecruiterAccessRepository;
import org.hmb.security.SigningSecretProvider;
import org.hmb.security.UploadTokenService;
import org.hmb.service.DocumentReconciliationService.Result;
import org.hmb.service.DocumentUploadService.RegisterOutcome;
import org.hmb.storage.S3DocumentStorageService;
import org.hmb.storage.S3DocumentStorageService.ObjectState;
import org.hmb.storage.S3DocumentStorageService.PresignedUpload;
import org.hmb.storage.S3DocumentStorageService.StoredObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The document flow against a real MySQL that already contains the schema, including
 * {@code object_store_entries.document_type}. S3 is mocked (no AWS access is needed or used).
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and the {@code DB_*} variables point at a database that is safe to write
 * to. Every test creates its own users and deletes them afterwards (their documents go with them through the foreign
 * key), and nothing else is touched. The schema is never changed.
 */
class DocumentFlowIntegrationTest {

    private static final String PDF = "application/pdf";
    private static final long SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Actors(long managerId, long recruiterA, long recruiterB, long studentOfA, long studentOfB) {
    }

    private static DataSource dataSource;
    private static DocumentRepository documents;

    private final List<Actors> created = new ArrayList<>();
    private S3DocumentStorageService storage;
    private UploadTokenService tokens;
    private DocumentUploadService service;

    @BeforeAll
    static void connect() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS")),
                "Set RUN_DB_TESTS=true and the DB_* variables to run database integration tests");
        dataSource = DatabaseConfig.getDataSource();
        documents = new DocumentRepository();
    }

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        storage = Mockito.mock(S3DocumentStorageService.class);
        tokens = new UploadTokenService(new SigningSecretProvider.FixedProvider("integration-test-secret-0123456789abcdef"),
                Clock.systemUTC());
        service = serviceWith(DocumentUploadConfig.fromEnv(Map.of()));
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PresignedUpload("https://s3.example.test/upload", Map.of()));
    }

    private DocumentUploadService serviceWith(DocumentUploadConfig config) {
        return new DocumentUploadService(dataSource, config, documents, new RecruiterAccessRepository(), storage, tokens,
                Clock.systemUTC());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (Actors a : created) {
                execute(c, "DELETE FROM recruiter_student_assignments WHERE assigned_by = ?", a.managerId());
                // Deleting a student's user cascades to its students row and its object_store_entries rows.
                execute(c, "DELETE FROM users WHERE user_id = ?", a.studentOfA());
                execute(c, "DELETE FROM users WHERE user_id = ?", a.studentOfB());
                execute(c, "DELETE FROM users WHERE user_id = ?", a.recruiterA());
                execute(c, "DELETE FROM users WHERE user_id = ?", a.recruiterB());
                execute(c, "DELETE FROM users WHERE user_id = ?", a.managerId());
            }
        }
        created.clear();
    }

    // ---- authorization against real rows ------------------------------------------------------------------------

    @Test
    void onlyTheAssignedRecruiterMayUploadForAStudent() throws SQLException {
        Actors a = actors();

        assertEquals("UPLOAD_REQUIRED", service.requestUploadUrl(request(a.studentOfA(), randomSha()), a.recruiterA()).status());

        // Another recruiter, a manager id, a student id and an unknown id are all refused.
        for (long caller : new long[] {a.recruiterB(), a.managerId(), a.studentOfA(), a.studentOfB(), Long.MAX_VALUE}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> service.requestUploadUrl(request(a.studentOfA(), randomSha()), caller));
            assertEquals(403, e.getStatusCode(), "caller " + caller);
        }
    }

    @Test
    void anUnknownStudentIsRefused() throws SQLException {
        Actors a = actors();

        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requestUploadUrl(request(Long.MAX_VALUE, randomSha()), a.recruiterA())).getStatusCode());
    }

    @Test
    void aRecruiterWhoseAssignmentWasRemovedCanNoLongerRegister() throws SQLException {
        Actors a = actors();
        String sha = randomSha();
        RegisterRequest register = prepareUpload(a.studentOfA(), a.recruiterA(), sha);
        try (Connection c = dataSource.getConnection()) {
            execute(c, "DELETE FROM recruiter_student_assignments WHERE student_id = ?", a.studentOfA());
        }

        assertEquals(403, assertThrows(ApiException.class, () -> service.registerDocument(register, a.recruiterA())).getStatusCode());
        assertEquals(0, rowCount(a.studentOfA()));
    }

    // ---- registration and the duplicate rules (UNIQUE(hashed_value) is global) ---------------------------------

    @Test
    void registrationStoresTheDocumentedColumnsAndTheTagIsSetAfterwards() throws SQLException {
        Actors a = actors();
        String sha = randomSha();
        RegisterRequest register = prepareUpload(a.studentOfA(), a.recruiterA(), sha);

        RegisterOutcome outcome = service.registerDocument(register, a.recruiterA());

        assertTrue(outcome.created());
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT student_id, document_type, object_key, hashed_value, created_at FROM object_store_entries WHERE object_id = ?")) {
            ps.setLong(1, outcome.body().objectId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(a.studentOfA(), rs.getLong("student_id"));
                assertEquals("RESUME", rs.getString("document_type"));
                assertEquals(register.objectKey(), rs.getString("object_key"));
                assertEquals(sha, rs.getString("hashed_value"));
                assertEquals(64, rs.getString("hashed_value").length(), "a real SHA-256 in lowercase hex");
                assertTrue(rs.getObject("created_at") != null);
            }
        }
        verify(storage).markRegistered(register.objectKey());
    }

    @Test
    void sameStudentSameBytesReusesTheExistingDocument() throws SQLException {
        Actors a = actors();
        String sha = randomSha();
        long objectId = service.registerDocument(prepareUpload(a.studentOfA(), a.recruiterA(), sha), a.recruiterA())
                .body().objectId();
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(SIZE, PDF, base64(sha))));

        UploadUrlResponse again = service.requestUploadUrl(request(a.studentOfA(), sha), a.recruiterA());

        assertEquals("ALREADY_EXISTS", again.status());
        assertFalse(again.uploadRequired());
        assertEquals(objectId, again.objectId());
        assertEquals(1, rowCount(a.studentOfA()));
    }

    @Test
    void identicalBytesForAnotherStudentAreRejectedGlobally() throws SQLException {
        Actors a = actors();
        String sha = randomSha();
        service.registerDocument(prepareUpload(a.studentOfA(), a.recruiterA(), sha), a.recruiterA());

        ApiException atUploadUrl = assertThrows(ApiException.class,
                () -> service.requestUploadUrl(request(a.studentOfB(), sha), a.recruiterB()));
        assertEquals(409, atUploadUrl.getStatusCode());
        assertEquals("DUPLICATE_DOCUMENT", atUploadUrl.getErrorCode());
        assertFalse(atUploadUrl.getMessage().contains(String.valueOf(a.studentOfA())), "no other student's id");

        // Even if the second student's file was uploaded anyway, registration refuses it and stores nothing.
        RegisterRequest sneaky = prepareUploadIgnoringDuplicates(a.studentOfB(), a.recruiterB(), sha);
        ApiException atRegister = assertThrows(ApiException.class, () -> service.registerDocument(sneaky, a.recruiterB()));
        assertEquals("DUPLICATE_DOCUMENT", atRegister.getErrorCode());
        assertEquals(0, rowCount(a.studentOfB()));
        assertEquals(1, rowCount(a.studentOfA()));
    }

    @Test
    void sameBytesUnderAnotherDocumentTypeAreRejected() throws SQLException {
        Actors a = actors();
        String sha = randomSha();
        insertRow(a.studentOfA(), "COVER_LETTER", "students/" + a.studentOfA() + "/cover-letters/" + UUID.randomUUID() + ".pdf", sha);

        ApiException e = assertThrows(ApiException.class, () -> service.requestUploadUrl(request(a.studentOfA(), sha), a.recruiterA()));

        assertEquals("DUPLICATE_DOCUMENT", e.getErrorCode());
    }

    @Test
    void aStudentCanHaveSeveralDistinctDocuments() throws SQLException {
        Actors a = actors();

        long first = service.registerDocument(prepareUpload(a.studentOfA(), a.recruiterA(), randomSha()), a.recruiterA()).body().objectId();
        long second = service.registerDocument(prepareUpload(a.studentOfA(), a.recruiterA(), randomSha()), a.recruiterA()).body().objectId();

        assertNotEquals(first, second);
        assertEquals(2, rowCount(a.studentOfA()));
    }

    @Test
    void registeringTheSameUploadTwiceCreatesOneRow() throws SQLException {
        Actors a = actors();
        RegisterRequest register = prepareUpload(a.studentOfA(), a.recruiterA(), randomSha());

        RegisterOutcome first = service.registerDocument(register, a.recruiterA());
        RegisterOutcome replay = service.registerDocument(register, a.recruiterA());

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.body().objectId(), replay.body().objectId());
        assertEquals(1, rowCount(a.studentOfA()));
    }

    @Test
    void theDatabaseItselfRefusesADuplicateChecksumOrKey() throws SQLException {
        Actors a = actors();
        String sha = randomSha();
        String key = "students/" + a.studentOfA() + "/resumes/" + UUID.randomUUID() + ".pdf";
        insertRow(a.studentOfA(), "RESUME", key, sha);

        // Same checksum, different student, different key: UNIQUE(hashed_value) is global.
        SQLIntegrityConstraintViolationException byChecksum = assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertRow(a.studentOfB(), "RESUME", "students/" + a.studentOfB() + "/resumes/" + UUID.randomUUID() + ".pdf", sha));
        assertEquals(1062, byChecksum.getErrorCode());
        // Same key, different checksum: UNIQUE(object_key).
        SQLIntegrityConstraintViolationException byKey = assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertRow(a.studentOfA(), "RESUME", key, randomSha()));
        assertEquals(1062, byKey.getErrorCode());
    }

    // ---- concurrency ---------------------------------------------------------------------------------------------

    @Test
    void concurrentRegistrationsOfTheSameUploadCreateExactlyOneRow() throws Exception {
        Actors a = actors();
        RegisterRequest register = prepareUpload(a.studentOfA(), a.recruiterA(), randomSha());

        List<RegisterOutcome> outcomes = runConcurrently(6, () -> service.registerDocument(register, a.recruiterA()));

        assertEquals(1, rowCount(a.studentOfA()));
        assertEquals(1, outcomes.stream().filter(RegisterOutcome::created).count(), "exactly one request inserted the row");
        assertEquals(1, outcomes.stream().map(o -> o.body().objectId()).distinct().count(), "all got the same object id");
    }

    @Test
    void twoStudentsRacingWithIdenticalBytesYieldExactlyOneWinner() throws Exception {
        Actors a = actors();
        String sha = randomSha();
        RegisterRequest forA = prepareUploadIgnoringDuplicates(a.studentOfA(), a.recruiterA(), sha);
        RegisterRequest forB = prepareUploadIgnoringDuplicates(a.studentOfB(), a.recruiterB(), sha);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> resultA = pool.submit(() -> attempt(start, forA, a.recruiterA()));
            Future<Integer> resultB = pool.submit(() -> attempt(start, forB, a.recruiterB()));
            start.countDown();

            List<Integer> statuses = List.of(resultA.get(), resultB.get());
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "one winner: " + statuses);
            assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "one clean conflict: " + statuses);
            assertEquals(1, rowCount(a.studentOfA()) + rowCount(a.studentOfB()), "exactly one row for these bytes");
        } finally {
            pool.shutdownNow();
        }
    }

    private int attempt(CountDownLatch start, RegisterRequest request, long recruiterId) throws InterruptedException {
        start.await();
        try {
            return service.registerDocument(request, recruiterId).created() ? 201 : 200;
        } catch (ApiException e) {
            return e.getStatusCode();
        }
    }

    // ---- reconciliation against real rows -----------------------------------------------------------------------

    @Test
    void theReconcilerRetagsAnObjectWhoseRowExistsButIsStillPending() throws SQLException {
        Actors a = actors();
        String key = "students/" + a.studentOfA() + "/resumes/" + UUID.randomUUID() + ".pdf";
        insertRow(a.studentOfA(), "RESUME", key, randomSha());
        when(storage.state(anyString())).thenReturn(ObjectState.REGISTERED);
        when(storage.state(key)).thenReturn(ObjectState.PENDING);
        DocumentReconciliationService reconciler = new DocumentReconciliationService(dataSource,
                DocumentUploadConfig.fromEnv(Map.of()), documents, storage, Clock.systemUTC());

        Result result = reconciler.reconcile(() -> 600_000);

        assertTrue(result.healed() >= 1);
        verify(storage).markRegistered(key);
        // It only ever heals: no row is created or removed by the reconciler.
        assertEquals(1, rowCount(a.studentOfA()));
    }

    @Test
    void theReconcilerReportsARowWhoseObjectIsMissing() throws SQLException {
        Actors a = actors();
        String key = "students/" + a.studentOfA() + "/resumes/" + UUID.randomUUID() + ".pdf";
        insertRow(a.studentOfA(), "RESUME", key, randomSha());
        when(storage.state(anyString())).thenReturn(ObjectState.REGISTERED);
        when(storage.state(key)).thenReturn(ObjectState.MISSING);
        DocumentReconciliationService reconciler = new DocumentReconciliationService(dataSource,
                DocumentUploadConfig.fromEnv(Map.of()), documents, storage, Clock.systemUTC());

        Result result = reconciler.reconcile(() -> 600_000);

        assertTrue(result.missing() >= 1);
        assertTrue(result.needsAttention());
        verify(storage, never()).markRegistered(key);
        assertEquals(1, rowCount(a.studentOfA()), "nothing is deleted or repaired automatically");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static UploadUrlRequest request(long studentId, String sha) {
        return new UploadUrlRequest(studentId, "RESUME", "Resume.pdf", PDF, SIZE, sha);
    }

    /** Requests an upload URL, then makes the mocked S3 hold exactly the authorized object; returns the register call. */
    private RegisterRequest prepareUpload(long studentId, long recruiterId, String sha) {
        UploadUrlResponse issued = service.requestUploadUrl(request(studentId, sha), recruiterId);
        return stubUploadedObject(studentId, sha, issued.objectKey(), issued.uploadToken());
    }

    /**
     * Builds a valid upload for these bytes without going through the duplicate check, as a second uploader who raced
     * past it would have (the duplicate check at upload-url is not atomic with the registration insert).
     */
    private RegisterRequest prepareUploadIgnoringDuplicates(long studentId, long recruiterId, String sha) {
        String key = "students/" + studentId + "/resumes/" + UUID.randomUUID() + ".pdf";
        String token = tokens.sign(new org.hmb.security.UploadIntent(recruiterId, studentId, DocumentType.RESUME, key, sha,
                SIZE, PDF, Clock.systemUTC().instant().getEpochSecond() + 1800));
        return stubUploadedObject(studentId, sha, key, token);
    }

    private RegisterRequest stubUploadedObject(long studentId, String sha, String key, String token) {
        when(storage.head(key)).thenReturn(Optional.of(new StoredObject(SIZE, PDF, base64(sha))));
        when(storage.readHead(eq(key), anyInt())).thenReturn("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        when(storage.readTail(eq(key), anyInt())).thenReturn("\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
        return new RegisterRequest(studentId, "RESUME", key, sha, token);
    }

    private static String randomSha() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String base64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    /** A manager, two recruiters, and a student assigned to each recruiter. */
    private Actors actors() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long manager = insertUser(c);
            execute(c, "INSERT INTO managers (manager_id) VALUES (?)", manager);
            long recruiterA = insertUser(c);
            long recruiterB = insertUser(c);
            execute(c, "INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", recruiterA, manager);
            execute(c, "INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", recruiterB, manager);
            long studentA = insertUser(c);
            long studentB = insertUser(c);
            execute(c, "INSERT INTO students (student_id) VALUES (?)", studentA);
            execute(c, "INSERT INTO students (student_id) VALUES (?)", studentB);
            execute(c, "INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                    recruiterA, studentA, manager);
            execute(c, "INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                    recruiterB, studentB, manager);
            Actors actors = new Actors(manager, recruiterA, recruiterB, studentA, studentB);
            created.add(actors);
            return actors;
        }
    }

    private static void insertRow(long studentId, String type, String key, String sha) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            documents.insert(c, studentId, DocumentType.valueOf(type), key, sha);
        }
    }

    private static int rowCount(long studentId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM object_store_entries WHERE student_id = ?")) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static long insertUser(Connection c) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String phone = "9" + (100000000 + ThreadLocalRandom.current().nextInt(900000000));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (first_name, last_name, email, phone) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "it" + suffix);
            ps.setString(2, "user" + suffix);
            ps.setString(3, "it-" + suffix + "@example.test");
            ps.setString(4, phone);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void execute(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setLong(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
