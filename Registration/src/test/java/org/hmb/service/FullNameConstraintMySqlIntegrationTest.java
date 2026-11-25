package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.exception.RegistrationException;
import org.hmb.model.IdentityProvider;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UniqueKeyCatalog;
import org.hmb.repository.UniqueKeyCatalog.UniqueKey;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.support.ScratchSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Registration against a schema that has the owner's {@code UNIQUE (first_name, last_name)} index (declared unnamed,
 * as in the owner's DDL, so MySQL names it {@code first_name}). The real MySQL duplicate-key error and the real
 * {@code information_schema} lookup decide the answer.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class FullNameConstraintMySqlIntegrationTest {

    private static ScratchSchema db;

    @BeforeAll
    static void createSchema() throws Exception {
        assumeTrue(ScratchSchema.enabled(), "Set RUN_DB_TESTS=true and DB_* to run the MySQL integration tests");
        db = ScratchSchema.create(true);
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

    private static StudentRegistrationService service() {
        RegistrationConfig config = new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY",
                "5rgjoisv64qhe70oulfla17id9", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01",
                false);
        return new StudentRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), new UserRepository(), new StudentRepository(),
                new UserIdentityRepository(), config, Clock.systemUTC());
    }

    private static AuthenticatedIdentity identity(String email) {
        return new AuthenticatedIdentity(UUID.randomUUID().toString(), email, IdentityProvider.GOOGLE);
    }

    @Test
    void theIndexIsPresentInThisSchema() throws SQLException {
        assertEquals(1, db.count("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND NON_UNIQUE = 0 "
                + "AND INDEX_NAME = 'first_name'"));
    }

    @Test
    void aSecondStudentWithTheSameNameGetsAControlledNameConflictAndNothingIsWritten() throws SQLException {
        StudentRegistrationData first = service().persist(identity("alex.one@gmail.com"),
                new ValidatedRegistration("Alex", "Smith", "+17135550101"));
        long updatedBefore = db.count("SELECT UNIX_TIMESTAMP(updated_at) FROM users WHERE user_id = ?", first.userId());
        AuthenticatedIdentity second = identity("alex.two@gmail.com");

        RegistrationException e = assertThrows(RegistrationException.class, () -> service().persist(second,
                new ValidatedRegistration("Alex", "Smith", "+17135550102")));

        assertEquals("NAME_ALREADY_REGISTERED", e.code());
        assertEquals(409, e.status());
        assertEquals("An account with this name already exists. Please contact support.", e.getMessage());
        assertFalse(e.getMessage().contains("alex.one"));
        // Full rollback, and the existing user is untouched.
        assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM students"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE email = 'alex.two@gmail.com'"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", second.cognitoSubject()));
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND email = 'alex.one@gmail.com' "
                + "AND phone = '+17135550101'", first.userId()));
        assertEquals(updatedBefore,
                db.count("SELECT UNIX_TIMESTAMP(updated_at) FROM users WHERE user_id = ?", first.userId()));
    }

    @Test
    void theIndexCollationAlsoMatchesCaseAndAccentVariants() {
        service().persist(identity("jose@gmail.com"), new ValidatedRegistration("José", "García", "+17135550103"));

        RegistrationException e = assertThrows(RegistrationException.class, () -> service().persist(
                identity("jose.other@gmail.com"), new ValidatedRegistration("JOSE", "garcia", "+17135550104")));
        assertEquals("NAME_ALREADY_REGISTERED", e.code());
    }

    @Test
    void differentNamesStillRegister() throws SQLException {
        service().persist(identity("a@gmail.com"), new ValidatedRegistration("Alex", "Smith", "+17135550101"));
        service().persist(identity("b@gmail.com"), new ValidatedRegistration("Alex", "Smithson", "+17135550102"));
        service().persist(identity("c@gmail.com"), new ValidatedRegistration("Alexa", "Smith", "+17135550103"));
        assertEquals(3, db.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void emailAndSubjectDuplicatesAreStillReportedAsSuchNotAsNameConflicts() {
        AuthenticatedIdentity original = identity("same@gmail.com");
        service().persist(original, new ValidatedRegistration("Alex", "Smith", "+17135550101"));

        // Same email (and same name) under another identity: the email conflict, which is what is true.
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", assertThrows(RegistrationException.class, () -> service().persist(
                identity("same@gmail.com"), new ValidatedRegistration("Alex", "Smith", "+17135550101"))).code());
        // The same Google identity again: already registered.
        assertEquals("ACCOUNT_ALREADY_REGISTERED", assertThrows(RegistrationException.class, () -> service().persist(
                original, new ValidatedRegistration("Alex", "Smith", "+17135550101"))).code());
    }

    @Test
    void racingStudentsWithTheSameNameCreateExactlyOneAccount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String email = "racer" + i + "@gmail.com";
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return service().persist(identity(email),
                                new ValidatedRegistration("Sam", "Lee", "+17135550105"));
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
            assertEquals(1, outcomes.stream().filter(o -> o instanceof StudentRegistrationData).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter("NAME_ALREADY_REGISTERED"::equals).count(), outcomes.toString());
            assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM students"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theCatalogClassifiesRealMySqlDuplicateKeyErrors() throws SQLException {
        long userId = service().persist(identity("real@gmail.com"),
                new ValidatedRegistration("Kim", "Park", "+17135550106")).userId();
        UniqueKeyCatalog catalog = new UniqueKeyCatalog();
        try (Connection c = db.dataSource().getConnection()) {
            assertEquals(UniqueKey.FULL_NAME, catalog.classify(c, duplicateOf(c,
                    "INSERT INTO users (first_name, last_name, email, phone) VALUES ('Kim', 'Park', 'x@y.co', '+1')")));
            assertEquals(UniqueKey.EMAIL, catalog.classify(c, duplicateOf(c,
                    "INSERT INTO users (first_name, last_name, email, phone) VALUES ('Zed', 'Zed', 'real@gmail.com', '+1')")));
            String existingSub = subjectOf(userId);
            assertEquals(UniqueKey.COGNITO_SUBJECT, catalog.classify(c, duplicateOf(c,
                    "INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (" + userId + ", 'COGNITO', '"
                            + existingSub + "')")));
            assertEquals(UniqueKey.USER_PROVIDER, catalog.classify(c, duplicateOf(c,
                    "INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (" + userId
                            + ", 'GOOGLE', 'another-sub')")));
        }
    }

    private static SQLException duplicateOf(Connection c, String sql) {
        return assertThrows(SQLIntegrityConstraintViolationException.class, () -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate(sql);
            }
        });
    }

    private static String subjectOf(long userId) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             var ps = c.prepareStatement("SELECT cognito_sub FROM user_identities WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
