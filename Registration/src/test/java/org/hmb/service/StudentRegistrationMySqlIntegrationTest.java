package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NextStep;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.support.ScratchSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * The registration transaction against a real MySQL server loaded with the V1 DDL (see {@link ScratchSchema}). Cognito
 * is not involved here: the identity is supplied as already resolved, so these tests focus on MySQL behaviour (the
 * generated ids, constraints, rollback and concurrency).
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and {@code DB_HOST}, {@code DB_PORT}, {@code DB_USERNAME},
 * {@code DB_PASSWORD} (and {@code DB_SSL_MODE} for a local server without TLS) are set.
 */
class StudentRegistrationMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-06T18:30:15Z");
    private static final String TERMS = "2026-11-01";
    private static ScratchSchema db;

    private final ValidatedRegistration alex = new ValidatedRegistration("Alex", "Smith", "+17135550101");

    @BeforeAll
    static void createSchema() throws Exception {
        assumeTrue(ScratchSchema.enabled(), "Set RUN_DB_TESTS=true and DB_* to run the MySQL integration tests");
        db = ScratchSchema.create();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        db.clear();
    }

    private static RegistrationConfig config(boolean phoneVerificationRequired) {
        return new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY", "5rgjoisv64qhe70oulfla17id9",
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", TERMS, phoneVerificationRequired);
    }

    private static StudentRegistrationService service(UserRepository users, StudentRepository students,
                                                      UserIdentityRepository identities, boolean phoneRequired) {
        return new StudentRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), users, students, identities, config(phoneRequired),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StudentRegistrationService service() {
        return service(new UserRepository(), new StudentRepository(), new UserIdentityRepository(), true);
    }

    private static AuthenticatedIdentity identity(String email) {
        return new AuthenticatedIdentity(UUID.randomUUID().toString(), email, IdentityProvider.GOOGLE);
    }

    // ---- success and integrity --------------------------------------------------------------------------------------

    @Test
    void createsExactlyOneRowInEachTableWithTheSameGeneratedId() throws SQLException {
        AuthenticatedIdentity id = identity("alex.smith@gmail.com");

        StudentRegistrationData data = service().persist(id, alex);

        long userId = data.userId();
        assertTrue(userId >= 1_000_000_000L, "generated id from the V1 AUTO_INCREMENT start, got " + userId);
        assertEquals(userId, data.studentId());
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ?", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM students WHERE student_id = ?", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities WHERE user_id = ?", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM students"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities"));

        try (Connection c = db.dataSource().getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM users WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Alex", rs.getString("first_name"));
                    assertEquals("Smith", rs.getString("last_name"));
                    assertEquals("alex.smith@gmail.com", rs.getString("email")); // from the Cognito identity
                    assertEquals("+17135550101", rs.getString("phone"));
                    assertTrue(rs.getBoolean("email_verified"));
                    assertFalse(rs.getBoolean("phone_verified"));
                    assertEquals("PENDING", rs.getString("account_status"));
                    assertEquals(NOW, rs.getTimestamp("terms_accepted_at").toInstant());
                    assertEquals(TERMS, rs.getString("terms_version"));
                    assertFalse(rs.getBoolean("is_deleted"));
                    assertTrue(rs.getTimestamp("created_at") != null);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM user_identities WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("GOOGLE", rs.getString("provider"));
                    assertEquals(id.cognitoSubject(), rs.getString("cognito_sub"));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void consecutiveRegistrationsGetDistinctGeneratedIds() {
        long first = service().persist(identity("a1@gmail.com"), alex).userId();
        long second = service().persist(identity("a2@gmail.com"), alex).userId();
        assertTrue(second > first);
    }

    @Test
    void activePolicyStoresActive() throws SQLException {
        StudentRegistrationData data = service(new UserRepository(), new StudentRepository(),
                new UserIdentityRepository(), false).persist(identity("active@gmail.com"), alex);

        assertEquals(AccountStatus.ACTIVE, data.accountStatus());
        assertEquals(NextStep.DASHBOARD, data.nextStep());
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND account_status = 'ACTIVE' "
                + "AND phone_verified = FALSE", data.userId()));
    }

    // ---- existing accounts --------------------------------------------------------------------------------------------

    @Test
    void anExistingCognitoIdentityIsNotRegisteredTwice() throws SQLException {
        AuthenticatedIdentity id = identity("twice@gmail.com");
        service().persist(id, alex);

        assertCode(() -> service().persist(id, alex), "ACCOUNT_ALREADY_REGISTERED", 409);
        // Even if Google now reports another email for the same subject.
        assertCode(() -> service().persist(new AuthenticatedIdentity(id.cognitoSubject(), "new@gmail.com",
                IdentityProvider.GOOGLE), alex), "ACCOUNT_ALREADY_REGISTERED", 409);
        assertTotals(1);
    }

    @Test
    void anExistingEmailUnderAnotherIdentityIsAConflictAndIsNotLinked() throws SQLException {
        AuthenticatedIdentity original = identity("shared@gmail.com");
        long userId = service().persist(original, alex).userId();

        AuthenticatedIdentity other = identity("shared@gmail.com");
        assertCode(() -> service().persist(other, alex), "ACCOUNT_IDENTITY_CONFLICT", 409);

        assertTotals(1);
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", other.cognitoSubject()));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities WHERE user_id = ? AND cognito_sub = ?", userId,
                original.cognitoSubject()));
    }

    @Test
    void emailMatchingIsCaseInsensitiveLikeTheColumn() throws SQLException {
        long userId = seedUser("Mixed.Case@Gmail.com", "ACTIVE", false);
        assertTrue(userId > 0);
        assertCode(() -> service().persist(identity("mixed.case@gmail.com"), alex), "ACCOUNT_IDENTITY_CONFLICT", 409);
    }

    @Test
    void aDeletedAccountIsRefusedAndNotReactivated() throws SQLException {
        long userId = seedUser("deleted@gmail.com", "ACTIVE", true);
        String sub = UUID.randomUUID().toString();
        seedIdentity(userId, sub);

        assertCode(() -> service().persist(identity("deleted@gmail.com"), alex), "ACCOUNT_DELETED", 403);
        assertCode(() -> service().persist(new AuthenticatedIdentity(sub, "other@gmail.com", IdentityProvider.GOOGLE),
                alex), "ACCOUNT_DELETED", 403);

        assertTotals(1);
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND is_deleted = TRUE", userId));
    }

    @Test
    void aSuspendedAccountIsRefusedWithoutAReplacement() throws SQLException {
        long userId = seedUser("suspended@gmail.com", "SUSPENDED", false);
        String sub = UUID.randomUUID().toString();
        seedIdentity(userId, sub);

        assertCode(() -> service().persist(identity("suspended@gmail.com"), alex), "ACCOUNT_SUSPENDED", 403);
        assertCode(() -> service().persist(new AuthenticatedIdentity(sub, "x@gmail.com", IdentityProvider.GOOGLE), alex),
                "ACCOUNT_SUSPENDED", 403);
        assertTotals(1);
    }

    // ---- rollback --------------------------------------------------------------------------------------------------------

    @Test
    void failureInsertingStudentsLeavesNoRows() throws SQLException {
        StudentRepository failingStudents = new StudentRepository() {
            @Override
            public void insert(Connection connection, long studentId) throws SQLException {
                super.insert(connection, studentId); // the row really is written inside the transaction...
                throw new SQLException("simulated failure after INSERT students", "HY000", 1105);
            }
        };
        AuthenticatedIdentity id = identity("rollback1@gmail.com");

        assertCode(() -> service(new UserRepository(), failingStudents, new UserIdentityRepository(), true)
                .persist(id, alex), "INTERNAL_ERROR", 500);

        assertTotals(0); // ...and rolled back with the users row
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE email = ?", "rollback1@gmail.com"));
    }

    @Test
    void failureInsertingTheIdentityLeavesNoRows() throws SQLException {
        UserIdentityRepository failingIdentities = new UserIdentityRepository() {
            @Override
            public void insert(Connection connection, long userId, IdentityProvider provider, String cognitoSub)
                    throws SQLException {
                super.insert(connection, userId, provider, cognitoSub);
                throw new SQLException("simulated failure after INSERT user_identities", "HY000", 1105);
            }
        };
        AuthenticatedIdentity id = identity("rollback2@gmail.com");

        assertCode(() -> service(new UserRepository(), new StudentRepository(), failingIdentities, true)
                .persist(id, alex), "INTERNAL_ERROR", 500);

        assertTotals(0);
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", id.cognitoSubject()));
        // The same registration then succeeds: nothing was left behind to collide with.
        assertEquals(1, db.count("SELECT 1"));
        service().persist(id, alex);
        assertTotals(1);
    }

    // ---- concurrency -------------------------------------------------------------------------------------------------------

    @Test
    void racingRequestsForTheSameIdentityCreateExactlyOneAccount() throws Exception {
        // Both requests pass the pre-check before either inserts, so only the unique constraints can stop the second.
        CountDownLatch bothChecked = new CountDownLatch(2);
        UserRepository racingUsers = new UserRepository() {
            @Override
            public Optional<ExistingAccount> findByEmail(Connection connection, String email) throws SQLException {
                Optional<ExistingAccount> result = super.findByEmail(connection, email);
                bothChecked.countDown();
                try {
                    bothChecked.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }
        };
        StudentRegistrationService racing = service(racingUsers, new StudentRepository(), new UserIdentityRepository(),
                true);
        AuthenticatedIdentity id = identity("race@gmail.com");

        List<Object> outcomes = runConcurrently(2, () -> racing.persist(id, alex));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof StudentRegistrationData).count(), outcomes.toString());
        assertEquals(1, outcomes.stream().filter(o -> "ACCOUNT_ALREADY_REGISTERED".equals(o)).count(), outcomes.toString());
        assertTotals(1);
    }

    @Test
    void racingRequestsForTheSameEmailUnderDifferentIdentitiesCreateExactlyOneAccount() throws Exception {
        CountDownLatch bothChecked = new CountDownLatch(2);
        UserRepository racingUsers = new UserRepository() {
            @Override
            public Optional<ExistingAccount> findByEmail(Connection connection, String email) throws SQLException {
                Optional<ExistingAccount> result = super.findByEmail(connection, email);
                bothChecked.countDown();
                try {
                    bothChecked.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }
        };
        StudentRegistrationService racing = service(racingUsers, new StudentRepository(), new UserIdentityRepository(),
                true);

        List<Object> outcomes = runConcurrently(2, () -> racing.persist(identity("same-email@gmail.com"), alex));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof StudentRegistrationData).count(), outcomes.toString());
        assertEquals(1, outcomes.stream().filter(o -> "ACCOUNT_IDENTITY_CONFLICT".equals(o)).count(), outcomes.toString());
        assertTotals(1);
    }

    @Test
    void manyConcurrentDuplicatesStillCreateOneAccount() throws Exception {
        AuthenticatedIdentity id = identity("burst@gmail.com");

        List<Object> outcomes = runConcurrently(8, () -> service().persist(id, alex));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof StudentRegistrationData).count(), outcomes.toString());
        assertEquals(7, outcomes.stream().filter(o -> "ACCOUNT_ALREADY_REGISTERED".equals(o)).count(), outcomes.toString());
        assertTotals(1);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------

    /** Runs the task on N threads at once; each outcome is the result or the RegistrationException code. */
    private static List<Object> runConcurrently(int threads, Callable<StudentRegistrationData> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RegistrationException e) {
                        return e.code();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertTotals(long expected) throws SQLException {
        assertEquals(expected, db.count("SELECT COUNT(*) FROM users"), "users");
        assertEquals(expected, db.count("SELECT COUNT(*) FROM students"), "students");
        assertEquals(expected, db.count("SELECT COUNT(*) FROM user_identities"), "user_identities");
    }

    private static void assertCode(Runnable call, String code, int status) {
        RegistrationException e = assertThrows(RegistrationException.class, call::run);
        assertEquals(code, e.code());
        assertEquals(status, e.status());
    }

    private static long seedUser(String email, String status, boolean deleted) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO users (first_name, last_name, email, phone, "
                     + "email_verified, account_status, is_deleted) VALUES ('Seed', 'User', ?, '+17135550199', TRUE, ?, ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.setString(2, status);
            ps.setBoolean(3, deleted);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                db.execute("INSERT INTO students (student_id) VALUES (?)", id);
                return id;
            }
        }
    }

    private static void seedIdentity(long userId, String sub) throws SQLException {
        db.execute("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, 'GOOGLE', ?)", userId, sub);
    }
}
