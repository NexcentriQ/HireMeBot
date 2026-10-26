package org.hmb.service;

import org.hmb.config.DatabaseConfig;
import org.hmb.dto.RecruiterRequest;
import org.hmb.exception.ApiException;
import org.hmb.repository.ApplicationRepository;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.QuestionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real service against a real MySQL that already contains hiremebot_db_V1.sql.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and the {@code DB_*} variables point at a database that is
 * safe to write to. Every test creates its own users and deletes them afterwards.
 */
class ApplicationFlowIntegrationTest {

    private record Fixture(long managerId, long recruiterId, long studentId, long resumeId, long coverLetterId) {
    }

    private static DataSource dataSource;
    private static ApplicationService service;

    private final List<Fixture> created = new ArrayList<>();

    @BeforeAll
    static void connect() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS")),
                "Set RUN_DB_TESTS=true and the DB_* variables to run database integration tests");
        dataSource = DatabaseConfig.getDataSource();
        service = new ApplicationService(dataSource, new AssignmentRepository(),
                new ApplicationRepository(), new DocumentRepository(), new QuestionRepository());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (Fixture f : created) {
                execute(c, "DELETE FROM applications WHERE student_id = ?", f.studentId());
                execute(c, "DELETE FROM recruiter_student_assignments WHERE student_id = ?", f.studentId());
                // Separate statements, in dependency order: recruiters reference managers (ON DELETE RESTRICT).
                execute(c, "DELETE FROM users WHERE user_id = ?", f.studentId());
                execute(c, "DELETE FROM users WHERE user_id = ?", f.recruiterId());
                execute(c, "DELETE FROM users WHERE user_id = ?", f.managerId());
            }
        }
        created.clear();
    }

    @Test
    void assignedRecruiterRecordsApplicationAndDocuments() throws SQLException {
        Fixture f = fixture(true);
        var request = requestFor(f, uniqueLink());

        var response = service.createApplication(f.recruiterId(), request);

        assertTrue(response.success());
        assertTrue(response.applicationId() >= 1000000000L, "ids start at 1000000000");
        try (Connection c = dataSource.getConnection()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM applications WHERE application_id = ? AND is_viewed = FALSE AND applied_at IS NOT NULL",
                    response.applicationId()));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM application_documents WHERE application_id = ? AND resume_object_id = " + f.resumeId(),
                    response.applicationId()));
            assertEquals(2, count(c, "SELECT COUNT(*) FROM application_questions WHERE application_id = ?",
                    response.applicationId()));
        }
    }

    @Test
    void unassignedRecruiterIsForbidden() throws SQLException {
        Fixture f = fixture(false);

        ApiException e = assertThrows(ApiException.class,
                () -> service.createApplication(f.recruiterId(), requestFor(f, uniqueLink())));

        assertEquals(403, e.getStatusCode());
        assertEquals(0, applicationCount(f));
    }

    @Test
    void softDeletedStudentIsForbidden() throws SQLException {
        Fixture f = fixture(true);
        try (Connection c = dataSource.getConnection()) {
            execute(c, "UPDATE users SET is_deleted = TRUE WHERE user_id = ?", f.studentId());
        }

        ApiException e = assertThrows(ApiException.class,
                () -> service.createApplication(f.recruiterId(), requestFor(f, uniqueLink())));

        assertEquals(403, e.getStatusCode());
        assertEquals(0, applicationCount(f));
    }

    @Test
    void duplicateJobLinkIsConflict() throws SQLException {
        Fixture f = fixture(true);
        String link = uniqueLink();
        service.createApplication(f.recruiterId(), requestFor(f, link));

        ApiException e = assertThrows(ApiException.class,
                () -> service.createApplication(f.recruiterId(), requestFor(f, link)));

        assertEquals(409, e.getStatusCode());
        assertEquals(1, applicationCount(f));
    }

    @Test
    void concurrentDuplicateSubmissionsCreateExactlyOneApplication() throws Exception {
        Fixture f = fixture(true);
        var request = requestFor(f, uniqueLink());
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> task = () -> {
                    start.await();
                    try {
                        service.createApplication(f.recruiterId(), request);
                        return 201;
                    } catch (ApiException e) {
                        return e.getStatusCode();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            int createdCount = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                if (status == 201) {
                    createdCount++;
                } else {
                    assertEquals(409, status, "every losing request must be a clean 409");
                }
            }
            assertEquals(1, createdCount);
            assertEquals(1, applicationCount(f));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- fixtures and helpers -------------------------------------------------------------------------------

    private Fixture fixture(boolean assigned) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long manager = insertUser(c);
            execute(c, "INSERT INTO managers (manager_id) VALUES (?)", manager);
            long recruiter = insertUser(c);
            execute(c, "INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", recruiter, manager);
            long student = insertUser(c);
            execute(c, "INSERT INTO students (student_id) VALUES (?)", student);
            if (assigned) {
                execute(c, "INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                        recruiter, student, manager);
            }
            long resume = insertObject(c, student);
            long cover = insertObject(c, student);
            Fixture f = new Fixture(manager, recruiter, student, resume, cover);
            created.add(f);
            return f;
        }
    }

    private static RecruiterRequest requestFor(Fixture f, String jobLink) {
        return new RecruiterRequest(
                f.studentId(),
                new RecruiterRequest.Application("Integration Test Engineer", "Test Co", "Remote", jobLink, "IT"),
                new RecruiterRequest.Documents(f.resumeId(), f.coverLetterId()),
                List.of(new RecruiterRequest.Question("Are you willing to relocate?", "Yes"),
                        new RecruiterRequest.Question("Expected salary?", "$110,000")));
    }

    private static String uniqueLink() {
        return "https://example.test/jobs/" + UUID.randomUUID();
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
            return generatedKey(ps);
        }
    }

    private static long insertObject(Connection c, long studentId) throws SQLException {
        String key = "it/" + UUID.randomUUID();
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO object_store_entries (student_id, object_key, hashed_value) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, studentId);
            ps.setString(2, key);
            ps.setString(3, hash);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            assertTrue(keys.next());
            return keys.getLong(1);
        }
    }

    private static int applicationCount(Fixture f) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return count(c, "SELECT COUNT(*) FROM applications WHERE student_id = ?", f.studentId());
        }
    }

    private static int count(Connection c, String sql, long param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
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
