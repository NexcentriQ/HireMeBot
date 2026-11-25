package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.RecruiterAccountLookupResponse;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.support.ApprovalSql;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * The manual approval, exactly as the operator performs it: the statements of {@code Registration/db/recruiter-approval.sql}
 * are loaded from that file and executed against a throwaway schema (never against {@code hiremebot_db_v1}). Proves the
 * documented UPDATE approves exactly one pending recruiter, can never approve anything else, and that the next account
 * lookup then returns {@code RECRUITER_DASHBOARD}.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class RecruiterManualApprovalMySqlIntegrationTest {

    private static ScratchSchema db;
    private static ApprovalSql approval;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(ScratchSchema.enabled(), "Set RUN_DB_TESTS=true and DB_* to run the MySQL integration tests");
        db = ScratchSchema.create();
        approval = ApprovalSql.load();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        db.clear();
    }

    private static RecruiterRegistrationService registration() {
        RegistrationConfig config = new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY",
                "5rgjoisv64qhe70oulfla17id9", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", false);
        return new RecruiterRegistrationService(db.dataSource(), new RegistrationValidator(),
                mock(CognitoIdentityService.class), new UserRepository(), new RecruiterRepository(),
                new UserIdentityRepository(), config, Clock.systemUTC());
    }

    private static RecruiterAccountLookupService lookupService() {
        return new RecruiterAccountLookupService(db.dataSource(), mock(CognitoIdentityService.class),
                new UserIdentityRepository(), new UserRepository());
    }

    private static long register(String email, String first, String last, String sub) {
        return registration().persist(new AuthenticatedIdentity(sub, email, IdentityProvider.GOOGLE),
                new ValidatedRegistration(first, last, "+17135550101")).userId();
    }

    private static RecruiterNextStep nextStep(String sub) {
        return lookupService().lookup(new VerifiedAccessToken(sub, "google_" + sub.substring(0, 8), "client",
                Set.of("openid"), Instant.now().plusSeconds(600)), new BearerToken("t")).nextStep();
    }

    private static String statusName(long userId) throws SQLException {
        try (var c = db.dataSource().getConnection();
             var ps = c.prepareStatement("SELECT account_status FROM users WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    // ---- the script itself --------------------------------------------------------------------------------------------

    @Test
    void theShippedUpdateIsGuardedByIdStatusDeletionAndTheRecruitersTableAndNeverTouchesThePhone() {
        String update = approval.updateStatement();
        assertTrue(update.contains("WHERE user_id = @recruiter_id"), update);
        assertTrue(update.contains("account_status = 'PENDING'"), update);
        assertTrue(update.contains("is_deleted = FALSE"), update);
        assertTrue(update.contains("EXISTS"), update);
        assertTrue(update.contains("FROM recruiters"), update);
        assertFalse(update.toLowerCase().contains("phone"), "approval must not touch phone verification: " + update);
        assertFalse(update.toLowerCase().contains("email_verified"), update);
        assertEquals("SELECT ROW_COUNT() AS rows_changed", approval.rowCountStatement());
    }

    @Test
    void runningTheScriptExactlyAsShippedChangesNothing() throws SQLException {
        long pending = register("shipped@gmail.com", "Pat", "Pending", UUID.randomUUID().toString());
        String before = statusName(pending) + "/" + db.count("SELECT SUM(UNIX_TIMESTAMP(updated_at)) FROM users");

        approval.runUnedited(db.dataSource()); // @recruiter_id defaults to 0, which matches no user

        assertEquals(before, statusName(pending) + "/" + db.count("SELECT SUM(UNIX_TIMESTAMP(updated_at)) FROM users"));
        assertEquals("PENDING", statusName(pending));
    }

    // ---- approving a pending recruiter -----------------------------------------------------------------------------------

    @Test
    void approvingOnePendingRecruiterChangesExactlyOneRowThenTheLookupReturnsTheDashboard() throws SQLException {
        String sub = UUID.randomUUID().toString();
        long target = register("target@gmail.com", "Tess", "Target", sub);
        long other = register("other@gmail.com", "Otto", "Other", UUID.randomUUID().toString());
        assertEquals(RecruiterNextStep.APPROVAL_PENDING, nextStep(sub));

        long changed = approval.approve(db.dataSource(), target);

        assertEquals(1, changed, "ROW_COUNT() must be exactly 1");
        assertEquals("ACTIVE", statusName(target));
        assertEquals("PENDING", statusName(other), "no other recruiter is touched");
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE account_status = 'ACTIVE'"));
        // Approval never verifies anything and never reassigns a manager.
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND phone_verified = FALSE "
                + "AND email_verified = TRUE AND is_deleted = FALSE", target));
        assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters WHERE recruiter_id = ? AND manager_id IS NULL", target));

        RecruiterAccountLookupResponse response = lookupService().lookup(new VerifiedAccessToken(sub, "google_x",
                "client", Set.of("openid"), Instant.now().plusSeconds(600)), new BearerToken("t"));
        assertEquals(RecruiterNextStep.RECRUITER_DASHBOARD, response.nextStep());
        assertEquals(AccountStatus.ACTIVE, response.user().accountStatus());
        assertFalse(response.user().phoneVerified());

        assertEquals(0, approval.approve(db.dataSource(), target), "a second run changes nothing");
    }

    // ---- nobody else can be approved ---------------------------------------------------------------------------------------

    @Test
    void aStudentManagerOrAnyOtherKindOfUserCannotBeApprovedWithTheDocumentedSql() throws SQLException {
        long student = seedUser("Stu", "Dent", "student@hiremebot.test", "PENDING", false);
        db.execute("INSERT INTO students (student_id) VALUES (?)", student);
        long manager = seedUser("Mia", "Manager", "manager@hiremebot.test", "PENDING", false);
        db.execute("INSERT INTO managers (manager_id) VALUES (?)", manager);
        long roleLess = seedUser("Rae", "Roleless", "roleless@hiremebot.test", "PENDING", false);

        for (long id : new long[] {student, manager, roleLess}) {
            assertEquals(0, approval.approve(db.dataSource(), id), "user " + id);
            assertEquals("PENDING", statusName(id), "user " + id);
        }
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE account_status = 'ACTIVE'"));
    }

    @Test
    void onlyAPendingNonDeletedRecruiterIsApprovedActiveSuspendedAndDeletedOnesAreNot() throws SQLException {
        long active = seedRecruiter("act@hiremebot.test", "Ada", "Active", "ACTIVE", false);
        long suspended = seedRecruiter("sus@hiremebot.test", "Sue", "Suspended", "SUSPENDED", false);
        long deleted = seedRecruiter("del@hiremebot.test", "Dan", "Deleted", "PENDING", true);

        assertEquals(0, approval.approve(db.dataSource(), active));
        assertEquals(0, approval.approve(db.dataSource(), suspended));
        assertEquals(0, approval.approve(db.dataSource(), deleted));
        assertEquals(0, approval.approve(db.dataSource(), 999_999_999_999L), "an unknown id");

        assertEquals("ACTIVE", statusName(active));
        assertEquals("SUSPENDED", statusName(suspended), "a suspension is never lifted by approval");
        assertEquals("PENDING", statusName(deleted));
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND is_deleted = TRUE", deleted));
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------------------

    private static long seedUser(String first, String last, String email, String status, boolean deleted)
            throws SQLException {
        db.execute("INSERT INTO users (first_name, last_name, email, phone, email_verified, account_status, is_deleted) "
                + "VALUES (?, ?, ?, '+17135550199', TRUE, ?, ?)", first, last, email, status, deleted);
        return db.count("SELECT user_id FROM users WHERE email = ?", email);
    }

    private static long seedRecruiter(String email, String first, String last, String status, boolean deleted)
            throws SQLException {
        long id = seedUser(first, last, email, status, deleted);
        db.execute("INSERT INTO recruiters (recruiter_id) VALUES (?)", id);
        return id;
    }
}
