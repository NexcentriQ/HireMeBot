package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.RecruiterRegistrationData;
import org.hmb.exception.RegistrationException;
import org.hmb.model.IdentityProvider;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.support.ScratchSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
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
 * Recruiter registration against a schema that has the owner's {@code UNIQUE (first_name, last_name)} index (declared
 * unnamed, as in the owner's DDL; MySQL names it {@code first_name}). The index spans <em>all</em> users, so a recruiter
 * can collide with a student's name and the other way round. Real MySQL duplicate-key errors and the real
 * {@code information_schema} lookup decide the answer.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class RecruiterFullNameConstraintMySqlIntegrationTest {

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

    private static RegistrationConfig config() {
        return new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY", "5rgjoisv64qhe70oulfla17id9",
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", false);
    }

    private static RecruiterRegistrationService recruiters() {
        return new RecruiterRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), new UserRepository(), new RecruiterRepository(),
                new UserIdentityRepository(), config(), Clock.systemUTC());
    }

    private static StudentRegistrationService students() {
        return new StudentRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), new UserRepository(), new StudentRepository(),
                new UserIdentityRepository(), config(), Clock.systemUTC());
    }

    private static AuthenticatedIdentity identity(String email) {
        return new AuthenticatedIdentity(UUID.randomUUID().toString(), email, IdentityProvider.GOOGLE);
    }

    private static ValidatedRegistration named(String first, String last) {
        return new ValidatedRegistration(first, last, "+17135550101");
    }

    private static long rows() throws SQLException {
        return db.count("SELECT COUNT(*) FROM users") + db.count("SELECT COUNT(*) FROM recruiters")
                + db.count("SELECT COUNT(*) FROM students") + db.count("SELECT COUNT(*) FROM user_identities");
    }

    @Test
    void aRecruiterWithTheNameOfAnExistingStudentGetsAControlledNameConflictAndNothingIsWritten() throws SQLException {
        students().persist(identity("alex.student@gmail.com"), named("Alex", "Smith"));
        long before = rows();
        long updatedBefore = db.count("SELECT UNIX_TIMESTAMP(updated_at) FROM users");
        AuthenticatedIdentity recruiter = identity("alex.recruiter@gmail.com");

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> recruiters().persist(recruiter, named("Alex", "Smith")));

        assertEquals("NAME_ALREADY_REGISTERED", e.code());
        assertEquals(409, e.status());
        assertEquals("An account with this name already exists. Please contact support.", e.getMessage());
        assertFalse(e.getMessage().contains("alex.student"), "reveals nothing about the other user");
        assertEquals(before, rows(), "full rollback");
        assertEquals(0, db.count("SELECT COUNT(*) FROM recruiters"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE email = 'alex.recruiter@gmail.com'"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", recruiter.cognitoSubject()));
        assertEquals(updatedBefore, db.count("SELECT UNIX_TIMESTAMP(updated_at) FROM users"), "the other row is untouched");
    }

    @Test
    void aStudentWithTheNameOfAnExistingRecruiterGetsTheSameConflict() throws SQLException {
        recruiters().persist(identity("rita.recruiter@gmail.com"), named("Rita", "Rao"));

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> students().persist(identity("rita.student@gmail.com"), named("Rita", "Rao")));

        assertEquals("NAME_ALREADY_REGISTERED", e.code());
        assertEquals(0, db.count("SELECT COUNT(*) FROM students"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters"));
    }

    @Test
    void twoRecruitersWithTheSameNameConflictAndCaseOrAccentVariantsToo() {
        recruiters().persist(identity("jose@gmail.com"), named("José", "García"));

        assertEquals("NAME_ALREADY_REGISTERED", assertThrows(RegistrationException.class,
                () -> recruiters().persist(identity("jose.b@gmail.com"), named("JOSE", "garcia"))).code());
    }

    @Test
    void differentNamesStillRegisterAsPendingRecruiters() throws SQLException {
        recruiters().persist(identity("a@gmail.com"), named("Alex", "Smith"));
        recruiters().persist(identity("b@gmail.com"), named("Alex", "Smithson"));
        RecruiterRegistrationData third = recruiters().persist(identity("c@gmail.com"), named("Alexa", "Smith"));

        assertEquals(3, db.count("SELECT COUNT(*) FROM recruiters"));
        assertEquals("PENDING", third.accountStatus().name());
    }

    @Test
    void emailAndSubjectDuplicatesAreStillReportedAsSuchNotAsNameConflicts() {
        AuthenticatedIdentity original = identity("same@gmail.com");
        recruiters().persist(original, named("Alex", "Smith"));

        // Same email (and the same name) under another identity: the email conflict, which is what is true.
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", assertThrows(RegistrationException.class,
                () -> recruiters().persist(identity("same@gmail.com"), named("Alex", "Smith"))).code());
        // The same Google identity again: already registered.
        assertEquals("ACCOUNT_ALREADY_REGISTERED", assertThrows(RegistrationException.class,
                () -> recruiters().persist(original, named("Alex", "Smith"))).code());
    }

    @Test
    void racingRecruitersWithTheSameNameCreateExactlyOneAccount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String email = "racer" + i + "@gmail.com";
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return recruiters().persist(identity(email), named("Sam", "Lee"));
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
            assertEquals(1, outcomes.stream().filter(o -> o instanceof RecruiterRegistrationData).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter("NAME_ALREADY_REGISTERED"::equals).count(), outcomes.toString());
            assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities"));
        } finally {
            pool.shutdownNow();
        }
    }
}
