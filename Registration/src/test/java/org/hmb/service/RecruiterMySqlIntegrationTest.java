package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.RecruiterAccountLookupResponse;
import org.hmb.dto.RecruiterRegistrationData;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.VerifiedAccessToken;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recruiter registration and account lookup against a real MySQL loaded with the V1 DDL (a throwaway schema, see
 * {@link ScratchSchema}); the Cognito identity is supplied by a mock. Covers the three-table transaction, the
 * "lookup never writes" guarantee, rollback at each insert, account-type conflicts in both directions, and races.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class RecruiterMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T18:30:15Z");
    private static final String TERMS = "2026-11-01";
    private static ScratchSchema db;

    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
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

    // ---- wiring -------------------------------------------------------------------------------------------------

    private static RegistrationConfig config(boolean studentPhoneVerificationRequired) {
        return new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY", "5rgjoisv64qhe70oulfla17id9",
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", TERMS, studentPhoneVerificationRequired);
    }

    private static RecruiterRegistrationService recruiterRegistration(UserRepository users, RecruiterRepository recruiters,
                                                                      UserIdentityRepository identities) {
        // false = the V1 STUDENT policy (students ACTIVE); recruiters must still be PENDING.
        return new RecruiterRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), users, recruiters, identities, config(false),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RecruiterRegistrationService recruiterRegistration() {
        return recruiterRegistration(new UserRepository(), new RecruiterRepository(), new UserIdentityRepository());
    }

    private static StudentRegistrationService studentRegistration(UserRepository users) {
        return new StudentRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), users, new StudentRepository(), new UserIdentityRepository(),
                config(false), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RecruiterAccountLookupService recruiterLookup() {
        return new RecruiterAccountLookupService(db.dataSource(), identityService, new UserIdentityRepository(),
                new UserRepository());
    }

    private AccountLookupService studentLookup() {
        return new AccountLookupService(db.dataSource(), identityService, new UserIdentityRepository(),
                new UserRepository());
    }

    private static AuthenticatedIdentity identity(String email) {
        return new AuthenticatedIdentity(UUID.randomUUID().toString(), email, IdentityProvider.GOOGLE);
    }

    private static VerifiedAccessToken token(String sub) {
        return new VerifiedAccessToken(sub, "google_" + sub.substring(0, 8), "client", Set.of("openid", "email"),
                Instant.now().plusSeconds(600));
    }

    private void cognitoSays(AuthenticatedIdentity identity) {
        when(identityService.resolve(argThat(t -> t != null && identity.cognitoSubject().equals(t.subject())), any()))
                .thenReturn(identity);
    }

    private RecruiterAccountLookupResponse lookup(AuthenticatedIdentity identity) {
        cognitoSays(identity);
        return recruiterLookup().lookup(token(identity.cognitoSubject()), new BearerToken("t"));
    }

    // ---- registration: exactly the three related rows ----------------------------------------------------------------

    @Test
    void registrationCreatesExactlyUsersRecruitersAndIdentityWithTheSameIdAndStaysPending() throws SQLException {
        AuthenticatedIdentity id = identity("recruiter.alex@gmail.com");

        RecruiterRegistrationData data = recruiterRegistration().persist(id, alex);

        long userId = data.userId();
        assertTrue(userId >= 1_000_000_000L, "generated id from the V1 AUTO_INCREMENT start, got " + userId);
        assertEquals(userId, data.recruiterId());
        assertEquals(AccountStatus.PENDING, data.accountStatus());
        assertEquals(RecruiterNextStep.APPROVAL_PENDING, data.nextStep());
        assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM students"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM managers"));
        assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters WHERE recruiter_id = ? AND manager_id IS NULL", userId));

        try (Connection c = db.dataSource().getConnection();
             PreparedStatement users = c.prepareStatement("SELECT * FROM users WHERE user_id = ?");
             PreparedStatement identities = c.prepareStatement("SELECT * FROM user_identities WHERE user_id = ?")) {
            users.setLong(1, userId);
            try (ResultSet rs = users.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alex", rs.getString("first_name"));
                assertEquals("Smith", rs.getString("last_name"));
                assertEquals("recruiter.alex@gmail.com", rs.getString("email")); // from the Cognito identity
                assertEquals("+17135550101", rs.getString("phone"));
                assertTrue(rs.getBoolean("email_verified"));
                assertFalse(rs.getBoolean("phone_verified"));
                assertEquals("PENDING", rs.getString("account_status"));
                assertEquals(NOW, rs.getTimestamp("terms_accepted_at").toInstant());
                assertEquals(TERMS, rs.getString("terms_version"));
                assertFalse(rs.getBoolean("is_deleted"));
            }
            identities.setLong(1, userId);
            try (ResultSet rs = identities.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("GOOGLE", rs.getString("provider"));
                assertEquals(id.cognitoSubject(), rs.getString("cognito_sub"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void consecutiveRegistrationsGetDistinctIds() {
        long first = recruiterRegistration().persist(identity("r1@gmail.com"),
                new ValidatedRegistration("Ann", "One", "+17135550111")).userId();
        long second = recruiterRegistration().persist(identity("r2@gmail.com"),
                new ValidatedRegistration("Ben", "Two", "+17135550112")).userId();
        assertTrue(second > first);
    }

    // ---- lookup: read only -----------------------------------------------------------------------------------------------

    @Test
    void aNewGoogleUserGetsTheSignupFormAndTheLookupWritesNothing() throws SQLException {
        AuthenticatedIdentity id = identity("new.recruiter@gmail.com");

        for (int i = 0; i < 3; i++) {
            RecruiterAccountLookupResponse response = lookup(id);
            assertEquals(RecruiterNextStep.RECRUITER_SIGNUP_FORM, response.nextStep());
            assertFalse(response.registered());
            assertNull(response.user());
        }
        assertEquals("0/0/0/0/0", snapshotCounts());
    }

    @Test
    void registeredPendingRecruiterIsRecognisedOnEveryLaterVisitWithoutNewRows() throws SQLException {
        AuthenticatedIdentity id = identity("pending.recruiter@gmail.com");
        assertEquals(RecruiterNextStep.RECRUITER_SIGNUP_FORM, lookup(id).nextStep());

        RecruiterRegistrationData registered = recruiterRegistration().persist(id, alex);

        String before = snapshot();
        for (int i = 0; i < 3; i++) {
            RecruiterAccountLookupResponse again = lookup(id);
            assertEquals(RecruiterNextStep.APPROVAL_PENDING, again.nextStep());
            assertTrue(again.registered());
            assertEquals(registered.userId(), again.user().userId());
            assertEquals(registered.userId(), again.user().recruiterId());
            assertEquals(AccountStatus.PENDING, again.user().accountStatus());
            assertEquals("RECRUITER", again.user().accountType());
        }
        assertEquals(before, snapshot(), "lookups must not insert or update anything");
        // A second registration of the same Google account creates nothing.
        assertCode(() -> recruiterRegistration().persist(id, alex), "ACCOUNT_ALREADY_REGISTERED", 409);
        assertEquals(before, snapshot());
    }

    @Test
    void anActiveRecruiterFoundByLookupNeedsNoCognitoCall() throws SQLException {
        AuthenticatedIdentity id = identity("active.recruiter@gmail.com");
        long userId = recruiterRegistration().persist(id, alex).userId();
        db.execute("UPDATE users SET account_status = 'ACTIVE' WHERE user_id = ?", userId); // the operator's approval

        RecruiterAccountLookupResponse response = recruiterLookup().lookup(token(id.cognitoSubject()), new BearerToken("t"));

        assertEquals(RecruiterNextStep.RECRUITER_DASHBOARD, response.nextStep());
        assertEquals("Alex", response.user().firstName());
        assertEquals("active.recruiter@gmail.com", response.user().email());
        assertFalse(response.user().phoneVerified());
        assertEquals(AccountStatus.ACTIVE, response.user().accountStatus());
        verify(identityService, never()).resolve(any(), any());
    }

    // ---- restricted accounts and other account types ---------------------------------------------------------------

    @Test
    void suspendedAndDeletedRecruitersAreDeniedOnLookupAndRegistration() throws SQLException {
        AuthenticatedIdentity id = identity("restricted.recruiter@gmail.com");
        long userId = recruiterRegistration().persist(id, alex).userId();

        db.execute("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", userId);
        assertLookupCode(id, "ACCOUNT_SUSPENDED", 403);
        assertCode(() -> recruiterRegistration().persist(id, alex), "ACCOUNT_SUSPENDED", 403);

        db.execute("UPDATE users SET account_status = 'ACTIVE', is_deleted = TRUE WHERE user_id = ?", userId);
        assertLookupCode(id, "ACCOUNT_DELETED", 403);
        assertCode(() -> recruiterRegistration().persist(id, alex), "ACCOUNT_DELETED", 403);

        assertEquals(1, db.count("SELECT COUNT(*) FROM users"), "never recreated or reactivated");
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND is_deleted = TRUE", userId));
    }

    @Test
    void anExistingStudentIsNeverTreatedAsANewRecruiterNorConverted() throws SQLException {
        AuthenticatedIdentity id = identity("student.first@gmail.com");
        studentRegistration(new UserRepository()).persist(id, new ValidatedRegistration("Nia", "Okafor", "+17135550121"));
        String before = snapshot();

        assertLookupCode(id, "ACCOUNT_TYPE_MISMATCH", 403);
        assertCode(() -> recruiterRegistration().persist(id, alex), "ACCOUNT_TYPE_MISMATCH", 403);

        assertEquals(before, snapshot());
        assertEquals(0, db.count("SELECT COUNT(*) FROM recruiters"));
        // A suspended student is still "another account type", not "your recruiter account is suspended".
        db.execute("UPDATE users SET account_status = 'SUSPENDED'");
        assertLookupCode(id, "ACCOUNT_TYPE_MISMATCH", 403);
    }

    @Test
    void anExistingRecruiterIsNeverTreatedAsANewStudentEither() throws SQLException {
        AuthenticatedIdentity id = identity("recruiter.first@gmail.com");
        recruiterRegistration().persist(id, alex);
        String before = snapshot();

        assertCode(() -> studentRegistration(new UserRepository()).persist(id,
                new ValidatedRegistration("Zed", "Zimmer", "+17135550122")), "ACCOUNT_TYPE_MISMATCH", 403);
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> studentLookup().lookup(token(id.cognitoSubject()), new BearerToken("t")));
        assertEquals("ACCOUNT_TYPE_MISMATCH", e.code());

        assertEquals(before, snapshot());
        assertEquals(0, db.count("SELECT COUNT(*) FROM students"));
    }

    @Test
    void managerAndRoleLessIdentitiesAreAccountTypeMismatches() throws SQLException {
        String managerSub = UUID.randomUUID().toString();
        long managerId = seedUser("Mary", "Manager", "mary@hiremebot.test", "ACTIVE", false);
        db.execute("INSERT INTO managers (manager_id) VALUES (?)", managerId);
        seedIdentity(managerId, "GOOGLE", managerSub);

        String roleLessSub = UUID.randomUUID().toString();
        long roleLessId = seedUser("Rory", "Roleless", "rory@hiremebot.test", "ACTIVE", false);
        seedIdentity(roleLessId, "GOOGLE", roleLessSub);

        String before = snapshot();
        for (String sub : new String[] {managerSub, roleLessSub}) {
            RegistrationException e = assertThrows(RegistrationException.class,
                    () -> recruiterLookup().lookup(token(sub), new BearerToken("t")));
            assertEquals("ACCOUNT_TYPE_MISMATCH", e.code());
            assertCode(() -> recruiterRegistration().persist(new AuthenticatedIdentity(sub, "x" + sub + "@gmail.com",
                    IdentityProvider.GOOGLE), alex), "ACCOUNT_TYPE_MISMATCH", 403);
        }
        assertEquals(before, snapshot());
    }

    @Test
    void aNonGoogleIdentityOfARecruiterNeedsGoogle() throws SQLException {
        String sub = UUID.randomUUID().toString();
        long id = seedUser("Cora", "Cognito", "cora@hiremebot.test", "ACTIVE", false);
        db.execute("INSERT INTO recruiters (recruiter_id) VALUES (?)", id);
        seedIdentity(id, "COGNITO", sub);

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> recruiterLookup().lookup(token(sub), new BearerToken("t")));
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", e.code());
        assertEquals("Please use Continue with Google to sign in as a recruiter.", e.getMessage());
    }

    @Test
    void anEmailOwnedByAnotherIdentityIsAConflictNotTheSignupFormAndNeverLinked() throws SQLException {
        AuthenticatedIdentity student = identity("shared@gmail.com");
        studentRegistration(new UserRepository()).persist(student, new ValidatedRegistration("Ana", "Lopez", "+17135550123"));
        AuthenticatedIdentity newIdentity = identity("shared@gmail.com");
        String before = snapshot();

        assertLookupCode(newIdentity, "ACCOUNT_IDENTITY_CONFLICT", 409);
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> recruiterRegistration().persist(newIdentity, alex));
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", e.code());
        assertEquals("This email is already associated with another HireMeBot account.", e.getMessage());

        assertEquals(before, snapshot());
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", newIdentity.cognitoSubject()));
    }

    @Test
    void emailMatchingIsCaseInsensitiveLikeTheColumn() throws SQLException {
        seedUser("Mixed", "Case", "Mixed.Case@Gmail.com", "ACTIVE", false);
        assertCode(() -> recruiterRegistration().persist(identity("mixed.case@gmail.com"), alex),
                "ACCOUNT_IDENTITY_CONFLICT", 409);
    }

    // ---- rollback at each of the three inserts --------------------------------------------------------------------------

    @Test
    void failureInsertingUsersLeavesNoRows() throws SQLException {
        UserRepository failingUsers = new UserRepository() {
            @Override
            public long insert(Connection connection, org.hmb.model.NewStudentUser user) throws SQLException {
                throw new SQLException("simulated failure inserting users", "HY000", 1105);
            }
        };
        assertCode(() -> recruiterRegistration(failingUsers, new RecruiterRepository(), new UserIdentityRepository())
                .persist(identity("rollback1@gmail.com"), alex), "INTERNAL_ERROR", 500);
        assertEquals("0/0/0/0/0", snapshotCounts());
    }

    @Test
    void failureInsertingRecruitersRollsTheUsersInsertBack() throws SQLException {
        RecruiterRepository failingRecruiters = new RecruiterRepository() {
            @Override
            public void insert(Connection connection, long recruiterId) throws SQLException {
                super.insert(connection, recruiterId); // really written inside the transaction...
                throw new SQLException("simulated failure after INSERT recruiters", "HY000", 1105);
            }
        };
        assertCode(() -> recruiterRegistration(new UserRepository(), failingRecruiters, new UserIdentityRepository())
                .persist(identity("rollback2@gmail.com"), alex), "INTERNAL_ERROR", 500);
        assertEquals("0/0/0/0/0", snapshotCounts()); // ...and gone, together with the users row
    }

    @Test
    void failureInsertingTheIdentityRollsEverythingBackAndALaterRegistrationSucceeds() throws SQLException {
        UserIdentityRepository failingIdentities = new UserIdentityRepository() {
            @Override
            public void insert(Connection connection, long userId, IdentityProvider provider, String cognitoSub)
                    throws SQLException {
                super.insert(connection, userId, provider, cognitoSub);
                throw new SQLException("simulated failure after INSERT user_identities", "HY000", 1105);
            }
        };
        AuthenticatedIdentity id = identity("rollback3@gmail.com");
        assertCode(() -> recruiterRegistration(new UserRepository(), new RecruiterRepository(), failingIdentities)
                .persist(id, alex), "INTERNAL_ERROR", 500);
        assertEquals("0/0/0/0/0", snapshotCounts());

        recruiterRegistration().persist(id, alex); // nothing was left behind to collide with
        assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters"));
    }

    // ---- concurrency ------------------------------------------------------------------------------------------------------

    /** A UserRepository whose email pre-check waits until {@code parties} callers have passed it (forces the race). */
    private static UserRepository racingUsers(int parties) {
        CountDownLatch allChecked = new CountDownLatch(parties);
        return new UserRepository() {
            @Override
            public Optional<ExistingAccount> findByEmail(Connection connection, String email) throws SQLException {
                Optional<ExistingAccount> result = super.findByEmail(connection, email);
                allChecked.countDown();
                try {
                    allChecked.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }
        };
    }

    @Test
    void racingRequestsForTheSameGoogleAccountCreateExactlyOneRecruiter() throws Exception {
        RecruiterRegistrationService racing = recruiterRegistration(racingUsers(2), new RecruiterRepository(),
                new UserIdentityRepository());
        AuthenticatedIdentity id = identity("race@gmail.com");

        List<Object> outcomes = runConcurrently(2, () -> racing.persist(id, alex));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof RecruiterRegistrationData).count(), outcomes.toString());
        assertEquals(1, outcomes.stream().filter("ACCOUNT_ALREADY_REGISTERED"::equals).count(), outcomes.toString());
        assertEquals("1/0/1/0/1", snapshotCounts());
    }

    @Test
    void racingRequestsForTheSameEmailUnderDifferentIdentitiesCreateExactlyOneRecruiter() throws Exception {
        RecruiterRegistrationService racing = recruiterRegistration(racingUsers(2), new RecruiterRepository(),
                new UserIdentityRepository());

        List<Object> outcomes = runConcurrently(2, () -> racing.persist(identity("same-email@gmail.com"), alex));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof RecruiterRegistrationData).count(), outcomes.toString());
        assertEquals(1, outcomes.stream().filter("ACCOUNT_IDENTITY_CONFLICT"::equals).count(), outcomes.toString());
        assertEquals("1/0/1/0/1", snapshotCounts());
    }

    @Test
    void aStudentAndARecruiterRacingForTheSameEmailCreateExactlyOneAccount() throws Exception {
        UserRepository shared = racingUsers(2);
        RecruiterRegistrationService recruiters = recruiterRegistration(shared, new RecruiterRepository(),
                new UserIdentityRepository());
        StudentRegistrationService students = studentRegistration(shared);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> recruiter = pool.submit(() -> {
                start.await();
                return outcome(() -> recruiters.persist(identity("cross@gmail.com"), alex));
            });
            Future<Object> student = pool.submit(() -> {
                start.await();
                return outcome(() -> students.persist(identity("cross@gmail.com"),
                        new ValidatedRegistration("Sam", "Student", "+17135550131")));
            });
            start.countDown();
            List<Object> outcomes = List.of(recruiter.get(60, TimeUnit.SECONDS), student.get(60, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(o -> o instanceof RecruiterRegistrationData
                    || o instanceof StudentRegistrationData).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter("ACCOUNT_IDENTITY_CONFLICT"::equals).count(), outcomes.toString());
            assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
            assertEquals(1, db.count("SELECT (SELECT COUNT(*) FROM students) + (SELECT COUNT(*) FROM recruiters)"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyConcurrentDuplicatesStillCreateOneRecruiter() throws Exception {
        AuthenticatedIdentity id = identity("burst@gmail.com");

        List<Object> outcomes = runConcurrently(8, () -> recruiterRegistration().persist(id, alex));

        assertEquals(1, outcomes.stream().filter(o -> o instanceof RecruiterRegistrationData).count(), outcomes.toString());
        assertEquals(7, outcomes.stream().filter("ACCOUNT_ALREADY_REGISTERED"::equals).count(), outcomes.toString());
        assertEquals("1/0/1/0/1", snapshotCounts());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private static Object outcome(Callable<Object> task) throws Exception {
        try {
            return task.call();
        } catch (RegistrationException e) {
            return e.code();
        }
    }

    private static List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return outcome(task);
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

    /** users/students/recruiters/managers/identities row counts plus the sum of users.updated_at (detects updates). */
    private static String snapshot() throws SQLException {
        return snapshotCounts() + "/" + db.count("SELECT COALESCE(SUM(UNIX_TIMESTAMP(updated_at)), 0) FROM users");
    }

    private static String snapshotCounts() throws SQLException {
        return db.count("SELECT COUNT(*) FROM users") + "/" + db.count("SELECT COUNT(*) FROM students") + "/"
                + db.count("SELECT COUNT(*) FROM recruiters") + "/" + db.count("SELECT COUNT(*) FROM managers") + "/"
                + db.count("SELECT COUNT(*) FROM user_identities");
    }

    private void assertLookupCode(AuthenticatedIdentity identity, String code, int status) {
        cognitoSays(identity);
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> recruiterLookup().lookup(token(identity.cognitoSubject()), new BearerToken("t")));
        assertEquals(code, e.code());
        assertEquals(status, e.status());
    }

    private static void assertCode(Runnable call, String code, int status) {
        RegistrationException e = assertThrows(RegistrationException.class, call::run);
        assertEquals(code, e.code());
        assertEquals(status, e.status());
    }

    private static long seedUser(String first, String last, String email, String status, boolean deleted)
            throws SQLException {
        db.execute("INSERT INTO users (first_name, last_name, email, phone, email_verified, account_status, is_deleted) "
                + "VALUES (?, ?, ?, '+17135550199', TRUE, ?, ?)", first, last, email, status, deleted);
        return db.count("SELECT user_id FROM users WHERE email = ?", email);
    }

    private static void seedIdentity(long userId, String provider, String sub) throws SQLException {
        db.execute("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, ?, ?)", userId, provider, sub);
    }
}
