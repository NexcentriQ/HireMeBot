package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.AccountLookupResponse;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NextStep;
import org.hmb.model.ValidatedRegistration;
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

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
 * {@code GET /api/v1/auth/me} logic against real MySQL (V1 schema, as in the live local database). The Cognito
 * identity is supplied by a mock; the SQL, joins and the "never writes" guarantee are real.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class AccountLookupMySqlIntegrationTest {

    private static ScratchSchema db;

    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
    private AccountLookupService lookup;

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
    void setUp() throws SQLException {
        db.clear();
        lookup = new AccountLookupService(db.dataSource(), identityService, new UserIdentityRepository(),
                new UserRepository());
    }

    private static StudentRegistrationService registration(boolean phoneVerificationRequired) {
        RegistrationConfig config = new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY",
                "5rgjoisv64qhe70oulfla17id9", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01",
                phoneVerificationRequired);
        return new StudentRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), new UserRepository(), new StudentRepository(),
                new UserIdentityRepository(), config, Clock.systemUTC());
    }

    private static VerifiedAccessToken token(String sub) {
        return new VerifiedAccessToken(sub, "google_" + sub.substring(0, 8), "client", Set.of("openid", "email"),
                Instant.now().plusSeconds(600));
    }

    private void cognitoSays(String sub, String email) {
        when(identityService.resolve(argThat(t -> t != null && sub.equals(t.subject())), any()))
                .thenReturn(new AuthenticatedIdentity(sub, email, IdentityProvider.GOOGLE));
    }

    private static String snapshot() throws SQLException {
        return db.count("SELECT COUNT(*) FROM users") + "/" + db.count("SELECT COUNT(*) FROM students") + "/"
                + db.count("SELECT COUNT(*) FROM user_identities") + "/"
                + db.count("SELECT COALESCE(SUM(UNIX_TIMESTAMP(updated_at)), 0) FROM users");
    }

    // ---- Scenario B then C: new student, registration, returning student ---------------------------------------

    @Test
    void newStudentGetsSignupFormThenDashboardAfterRegistration() throws SQLException {
        String sub = UUID.randomUUID().toString();
        cognitoSays(sub, "new.student@gmail.com");

        AccountLookupResponse first = lookup.lookup(token(sub), new BearerToken("t"));
        assertEquals(NextStep.SIGNUP_FORM, first.nextStep());
        assertFalse(first.registered());
        assertNull(first.user());
        assertEquals("0/0/0/0", snapshot(), "the lookup must not create anything");

        StudentRegistrationData registered = registration(false).persist(
                new AuthenticatedIdentity(sub, "new.student@gmail.com", IdentityProvider.GOOGLE),
                new ValidatedRegistration("Nia", "Okafor", "+17135550111"));
        assertEquals(NextStep.DASHBOARD, registered.nextStep());
        assertEquals(AccountStatus.ACTIVE, registered.accountStatus());

        String before = snapshot();
        for (int i = 0; i < 3; i++) {  // returning student, refreshes: same answer, nothing written
            AccountLookupResponse again = lookup.lookup(token(sub), new BearerToken("t"));
            assertEquals(NextStep.DASHBOARD, again.nextStep());
            assertTrue(again.registered());
            assertEquals(registered.userId(), again.user().userId());
            assertEquals(registered.userId(), again.user().studentId());
            assertEquals("Nia", again.user().firstName());
            assertEquals("Okafor", again.user().lastName());
            assertEquals("new.student@gmail.com", again.user().email());
            assertEquals("+17135550111", again.user().phoneNumber());
            assertTrue(again.user().emailVerified());
            assertFalse(again.user().phoneVerified());
            assertEquals(AccountStatus.ACTIVE, again.user().accountStatus());
        }
        assertEquals(before, snapshot());
        assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void aRegisteredStudentIsFoundWithoutAnyCognitoCall() {
        String sub = UUID.randomUUID().toString();
        registration(false).persist(new AuthenticatedIdentity(sub, "returning@gmail.com", IdentityProvider.GOOGLE),
                new ValidatedRegistration("Ravi", "Patel", "+17135550112"));

        assertEquals(NextStep.DASHBOARD, lookup.lookup(token(sub), new BearerToken("t")).nextStep());
        verify(identityService, never()).resolve(any(), any());
    }

    // ---- Scenario D: restricted accounts --------------------------------------------------------------------------

    @Test
    void pendingSuspendedAndDeletedStudents() throws SQLException {
        String sub = UUID.randomUUID().toString();
        long userId = registration(true).persist(
                new AuthenticatedIdentity(sub, "restricted@gmail.com", IdentityProvider.GOOGLE),
                new ValidatedRegistration("Mei", "Chen", "+17135550113")).userId();

        AccountLookupResponse pending = lookup.lookup(token(sub), new BearerToken("t"));
        assertEquals(NextStep.ACCOUNT_PENDING, pending.nextStep());
        assertTrue(pending.registered());
        assertEquals(AccountStatus.PENDING, pending.user().accountStatus());

        db.execute("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", userId);
        assertCode(sub, "ACCOUNT_SUSPENDED", 403);

        db.execute("UPDATE users SET account_status = 'ACTIVE', is_deleted = TRUE WHERE user_id = ?", userId);
        assertCode(sub, "ACCOUNT_DELETED", 403);

        assertEquals(1, db.count("SELECT COUNT(*) FROM users"), "never recreated");
        verify(identityService, never()).resolve(any(), any());
    }

    @Test
    void recruiterAndManagerIdentitiesAreNotTreatedAsNewStudents() throws SQLException {
        String recruiterSub = UUID.randomUUID().toString();
        long recruiterId = seedUser("Rob", "Recruiter", "rob@hiremebot.test");
        db.execute("INSERT INTO recruiters (recruiter_id) VALUES (?)", recruiterId);
        db.execute("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, 'GOOGLE', ?)", recruiterId,
                recruiterSub);

        String managerSub = UUID.randomUUID().toString();
        long managerId = seedUser("Mary", "Manager", "mary@hiremebot.test");
        db.execute("INSERT INTO managers (manager_id) VALUES (?)", managerId);
        db.execute("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, 'COGNITO', ?)", managerId,
                managerSub);

        String before = snapshot();
        assertCode(recruiterSub, "ACCOUNT_TYPE_MISMATCH", 403);
        assertCode(managerSub, "ACCOUNT_TYPE_MISMATCH", 403);
        assertEquals(before, snapshot());
        assertEquals(0, db.count("SELECT COUNT(*) FROM students"));
    }

    @Test
    void anEmailAlreadyUsedByAnotherIdentityIsAConflictNotTheSignupForm() throws SQLException {
        registration(false).persist(new AuthenticatedIdentity(UUID.randomUUID().toString(), "shared@gmail.com",
                IdentityProvider.GOOGLE), new ValidatedRegistration("Ana", "Lopez", "+17135550114"));
        String newSub = UUID.randomUUID().toString();
        cognitoSays(newSub, "shared@gmail.com");

        String before = snapshot();
        assertCode(newSub, "ACCOUNT_IDENTITY_CONFLICT", 409);
        assertEquals(before, snapshot());
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", newSub));
    }

    private void assertCode(String sub, String code, int status) {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> lookup.lookup(token(sub), new BearerToken("t")));
        assertEquals(code, e.code());
        assertEquals(status, e.status());
    }

    private static long seedUser(String first, String last, String email) throws SQLException {
        db.execute("INSERT INTO users (first_name, last_name, email, phone, email_verified, account_status) "
                + "VALUES (?, ?, ?, '+17135550199', TRUE, 'ACTIVE')", first, last, email);
        return db.count("SELECT user_id FROM users WHERE email = ?", email);
    }
}
