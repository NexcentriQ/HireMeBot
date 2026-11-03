package org.hmb.service;

import org.hmb.config.DatabaseConfig;
import org.hmb.dto.AssignedStudentDto;
import org.hmb.dto.AssignedStudentsResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.RecruiterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the assigned-students flow against a real MySQL that already contains hiremebot_db_V1.sql.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and the {@code DB_*} variables point at a database that is safe to
 * write to. Every test creates its own users and deletes them afterwards; nothing else is touched.
 */
class RecruiterStudentsIntegrationTest {

    private static DataSource dataSource;
    private static RecruiterStudentService service;

    private final List<Long> managers = new ArrayList<>();
    private final List<Long> recruiters = new ArrayList<>();
    private final List<Long> students = new ArrayList<>();

    @BeforeAll
    static void connect() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS")),
                "Set RUN_DB_TESTS=true and the DB_* variables to run database integration tests");
        dataSource = DatabaseConfig.getDataSource();
        service = new RecruiterStudentService(dataSource, new RecruiterRepository(), new AssignmentRepository());
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (long recruiter : recruiters) {
                execute(c, "DELETE FROM applications WHERE recruiter_id = ?", recruiter);
                execute(c, "DELETE FROM recruiter_student_assignments WHERE recruiter_id = ?", recruiter);
            }
            for (long student : students) {
                execute(c, "DELETE FROM users WHERE user_id = ?", student);
            }
            // Recruiters reference managers (ON DELETE RESTRICT), so recruiters first.
            for (long recruiter : recruiters) {
                execute(c, "DELETE FROM users WHERE user_id = ?", recruiter);
            }
            for (long manager : managers) {
                execute(c, "DELETE FROM users WHERE user_id = ?", manager);
            }
        }
        managers.clear();
        recruiters.clear();
        students.clear();
    }

    // ---- mapping and ordering -------------------------------------------------------------------------------

    @Test
    void returnsTheRecruitersAssignedStudentsAlphabeticallyWithTheDocumentedFields() throws SQLException {
        long manager = newManager();
        long recruiter = newRecruiter(manager);
        String run = suffix();
        // Inserted out of order; first names differ by their leading letter so the order is deterministic.
        long carol = newStudent("c" + run, "clark");
        long alice = newStudent("a" + run, "adams");
        long bob = newStudent("b" + run, "baker");
        assign(recruiter, carol, manager);
        assign(recruiter, alice, manager);
        assign(recruiter, bob, manager);

        AssignedStudentsResponse.Data data = service.getAssignedStudents(recruiter).data();

        assertEquals(recruiter, data.recruiterId());
        assertEquals(3, data.totalStudents());
        assertEquals(List.of(alice, bob, carol), ids(data));
        AssignedStudentDto first = data.students().get(0);
        assertEquals("a" + run, first.firstName());
        assertEquals("adams", first.lastName());
        assertEquals("a" + run + " adams", first.fullName());
        assertTrue(first.email().endsWith("@example.test"));
    }

    @Test
    void sortsByLastNameWhenFirstNamesAreEqual() throws SQLException {
        long manager = newManager();
        long recruiter = newRecruiter(manager);
        String same = "same" + suffix();
        long zed = newStudent(same, "zed");
        long adams = newStudent(same, "adams");
        assign(recruiter, zed, manager);
        assign(recruiter, adams, manager);

        assertEquals(List.of(adams, zed), ids(service.getAssignedStudents(recruiter).data()));
    }

    @Test
    void recruiterWithNoAssignmentsGetsAnEmptyList() throws SQLException {
        long recruiter = newRecruiter(newManager());

        AssignedStudentsResponse.Data data = service.getAssignedStudents(recruiter).data();

        assertEquals(0, data.totalStudents());
        assertTrue(data.students().isEmpty());
    }

    // ---- isolation and soft deletes -------------------------------------------------------------------------

    @Test
    void neverLeaksAnotherRecruitersStudents() throws SQLException {
        long manager = newManager();
        long recruiterA = newRecruiter(manager);
        long recruiterB = newRecruiter(manager);
        long mine1 = newStudent("m1" + suffix(), "x");
        long mine2 = newStudent("m2" + suffix(), "x");
        long theirs = newStudent("t1" + suffix(), "x");
        assign(recruiterA, mine1, manager);
        assign(recruiterA, mine2, manager);
        assign(recruiterB, theirs, manager);

        assertEquals(Set.of(mine1, mine2), Set.copyOf(ids(service.getAssignedStudents(recruiterA).data())));
        assertEquals(List.of(theirs), ids(service.getAssignedStudents(recruiterB).data()));
    }

    @Test
    void excludesSoftDeletedStudents() throws SQLException {
        long manager = newManager();
        long recruiter = newRecruiter(manager);
        long active = newStudent("act" + suffix(), "x");
        long deleted = newStudent("del" + suffix(), "x");
        assign(recruiter, active, manager);
        assign(recruiter, deleted, manager);
        softDelete(deleted);

        AssignedStudentsResponse.Data data = service.getAssignedStudents(recruiter).data();

        assertEquals(List.of(active), ids(data));
        assertEquals(1, data.totalStudents());
    }

    @Test
    void softDeletedRecruiterIs403() throws SQLException {
        long manager = newManager();
        long recruiter = newRecruiter(manager);
        long student = newStudent("s" + suffix(), "x");
        assign(recruiter, student, manager);
        softDelete(recruiter);

        ApiException e = assertThrows(ApiException.class, () -> service.getAssignedStudents(recruiter));

        assertEquals(403, e.getStatusCode());
    }

    @Test
    void nonexistentRecruiterIs403() {
        ApiException e = assertThrows(ApiException.class, () -> service.getAssignedStudents(Long.MAX_VALUE));

        assertEquals(403, e.getStatusCode());
    }

    @Test
    void aUserWhoIsNotARecruiterIs403() throws SQLException {
        long manager = newManager();   // a manager is a user but not a recruiter

        assertEquals(403, assertThrows(ApiException.class, () -> service.getAssignedStudents(manager)).getStatusCode());
    }

    // ---- schema constraints and timestamps ------------------------------------------------------------------

    /** The schema allows one recruiter per student; the endpoint relies on that, so confirm it still holds. */
    @Test
    void aStudentCannotBeAssignedToTwoRecruiters() throws SQLException {
        long manager = newManager();
        long recruiterA = newRecruiter(manager);
        long recruiterB = newRecruiter(manager);
        long student = newStudent("u" + suffix(), "x");
        assign(recruiterA, student, manager);

        assertThrows(SQLIntegrityConstraintViolationException.class, () -> assign(recruiterB, student, manager));
        assertEquals(List.of(student), ids(service.getAssignedStudents(recruiterA).data()));
        assertTrue(service.getAssignedStudents(recruiterB).data().students().isEmpty());
    }

    /**
     * assigned_at defaults to CURRENT_TIMESTAMP. The DTO labels it UTC ("Z"), so a row created just now must come
     * back as "now" in UTC, proving the connection's session time zone really is UTC.
     */
    @Test
    void assignedAtDefaultsToTheCurrentUtcInstant() throws SQLException {
        long manager = newManager();
        long recruiter = newRecruiter(manager);
        long student = newStudent("t" + suffix(), "x");
        assign(recruiter, student, manager);
        Instant after = Instant.now();

        String assignedAt = service.getAssignedStudents(recruiter).data().students().get(0).assignedAt();

        assertTrue(assignedAt.endsWith("Z"), assignedAt);
        Duration age = Duration.between(Instant.parse(assignedAt), after);
        assertTrue(age.abs().compareTo(Duration.ofMinutes(2)) < 0,
                "assignedAt " + assignedAt + " should be within two minutes of now (UTC), was " + age);
        assertFalse(assignedAt.contains("+"), assignedAt);
    }

    // ---- fixtures and helpers -------------------------------------------------------------------------------

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static List<Long> ids(AssignedStudentsResponse.Data data) {
        return data.students().stream().map(AssignedStudentDto::studentId).toList();
    }

    private long newManager() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long id = insertUser(c, "mgr" + suffix(), "user");
            managers.add(id);
            execute(c, "INSERT INTO managers (manager_id) VALUES (?)", id);
            return id;
        }
    }

    private long newRecruiter(long manager) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long id = insertUser(c, "rec" + suffix(), "user");
            recruiters.add(id);
            execute(c, "INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", id, manager);
            return id;
        }
    }

    private long newStudent(String firstName, String lastName) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long id = insertUser(c, firstName, lastName);
            students.add(id);
            execute(c, "INSERT INTO students (student_id) VALUES (?)", id);
            return id;
        }
    }

    private void assign(long recruiter, long student, long manager) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            execute(c, "INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                    recruiter, student, manager);
        }
    }

    private void softDelete(long userId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            execute(c, "UPDATE users SET is_deleted = TRUE WHERE user_id = ?", userId);
        }
    }

    private static long insertUser(Connection c, String firstName, String lastName) throws SQLException {
        String unique = suffix();
        String phone = "9" + (100000000 + ThreadLocalRandom.current().nextInt(900000000));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (first_name, last_name, email, phone) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, firstName);
            ps.setString(2, lastName);
            ps.setString(3, "it-" + unique + "@example.test");
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
