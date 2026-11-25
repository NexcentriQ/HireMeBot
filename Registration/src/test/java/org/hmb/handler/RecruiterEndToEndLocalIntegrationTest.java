package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.config.RegistrationConfig;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.security.CognitoAdminUserLookup;
import org.hmb.security.CognitoUserInfoClient;
import org.hmb.service.AccountLookupService;
import org.hmb.service.CognitoIdentityService;
import org.hmb.service.RecruiterAccountLookupService;
import org.hmb.service.RecruiterRegistrationService;
import org.hmb.service.RegistrationValidator;
import org.hmb.service.StudentRegistrationService;
import org.hmb.support.ApprovalSql;
import org.hmb.support.Events;
import org.hmb.support.FakeCognito;
import org.hmb.support.ScratchSchema;
import org.hmb.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Definition-of-done scenarios A-E of the recruiter flow, locally and end to end: the real handler routing all four
 * routes, the real JWT verifier (JWKS over HTTP), the real UserInfo client and the real AWS SDK AdminGetUser client
 * against {@link FakeCognito}, real MySQL (V1 schema), and the operator's approval script executed verbatim. The student
 * policy is configured as deployed ({@code PHONE_VERIFICATION_REQUIRED=false}); recruiters must still be PENDING.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class RecruiterEndToEndLocalIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ScratchSchema db;
    private static FakeCognito cognito;
    private static RegistrationHandler handler;
    private static ApprovalSql approval;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(ScratchSchema.enabled(), "Set RUN_DB_TESTS=true and DB_* to run the MySQL integration tests");
        db = ScratchSchema.create();
        approval = ApprovalSql.load();
        cognito = new FakeCognito(new TestTokens());
        RegistrationConfig config = new RegistrationConfig("us-east-2", TestTokens.POOL_ID, TestTokens.CLIENT_ID,
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", false);
        CognitoIdentityService identity = new CognitoIdentityService(
                new CognitoUserInfoClient(cognito.userInfoUri()),
                new CognitoAdminUserLookup(cognito.sdkClient(), TestTokens.POOL_ID));
        UserRepository users = new UserRepository();
        UserIdentityRepository identities = new UserIdentityRepository();
        handler = new RegistrationHandler(new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID,
                CognitoAccessTokenVerifier.remoteKeys(cognito.jwksUri()), Clock.systemUTC()),
                new StudentRegistrationService(db.dataSource(), new RegistrationValidator(), identity, users,
                        new StudentRepository(), identities, config, Clock.systemUTC()),
                new AccountLookupService(db.dataSource(), identity, identities, users),
                new RecruiterRegistrationService(db.dataSource(), new RegistrationValidator(), identity, users,
                        new RecruiterRepository(), identities, config, Clock.systemUTC()),
                new RecruiterAccountLookupService(db.dataSource(), identity, identities, users));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (cognito != null) {
            cognito.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        db.clear();
    }

    private static JsonNode lookup(String token, int expectedStatus) throws Exception {
        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.recruiterMe(token), null);
        assertEquals(expectedStatus, response.getStatusCode(), response.getBody());
        return JSON.readTree(response.getBody());
    }

    private static APIGatewayV2HTTPResponse register(String token, String body) {
        return handler.handleRequest(Events.recruiterRegister(token, body), null);
    }

    private static long rows() throws Exception {
        return db.count("SELECT COUNT(*) FROM users") + db.count("SELECT COUNT(*) FROM students")
                + db.count("SELECT COUNT(*) FROM recruiters") + db.count("SELECT COUNT(*) FROM user_identities");
    }

    private String freshGoogleUser(String email) {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, email, "true");
        return sub;
    }

    // ---- Scenarios A-D: new recruiter -> pending -> manual approval -> approved ------------------------------------------

    @Test
    void newRecruiterRegistersStaysPendingIsApprovedByTheOperatorAndThenReachesTheDashboard() throws Exception {
        String sub = freshGoogleUser("Riley.Recruiter@Gmail.com");
        String token = cognito.tokens().accessToken(sub);

        // A. Continue with Google -> lookup -> RECRUITER_SIGNUP_FORM, nothing created.
        JsonNode first = lookup(token, 200);
        assertTrue(first.get("success").asBoolean());
        assertFalse(first.get("registered").asBoolean());
        assertEquals("RECRUITER_SIGNUP_FORM", first.get("nextStep").asText());
        assertEquals("Complete your HireMeBot recruiter registration.", first.get("message").asText());
        assertFalse(first.has("user"));
        assertEquals(0, rows());

        // A. Form + register (same session). Forged identity, status, manager and OTP fields in the body are ignored.
        String body = """
                {"firstName":" Riley ","lastName":"Recruiter","phoneNumber":"+1 (713) 555-0140","acceptedTerms":true,
                 "email":"someone.else@gmail.com","cognitoSub":"forged-sub","userId":42,"managerId":1000000003,
                 "accountStatus":"ACTIVE","emailVerified":true,"phoneVerified":true,"otpVerified":true}""";
        APIGatewayV2HTTPResponse registered = register(token, body);
        assertEquals(201, registered.getStatusCode(), registered.getBody());
        JsonNode envelope = JSON.readTree(registered.getBody());
        assertEquals("RECRUITER_REGISTRATION_SUBMITTED", envelope.get("code").asText());
        assertEquals("Your recruiter registration is pending approval.", envelope.get("message").asText());
        JsonNode data = envelope.get("data");
        long userId = data.get("userId").asLong();
        assertEquals(userId, data.get("recruiterId").asLong());
        assertEquals("riley.recruiter@gmail.com", data.get("email").asText());
        assertEquals("PENDING", data.get("accountStatus").asText());
        assertEquals("RECRUITER", data.get("accountType").asText());
        assertEquals("APPROVAL_PENDING", data.get("nextStep").asText());
        assertTrue(data.get("emailVerified").asBoolean());
        assertFalse(data.get("phoneVerified").asBoolean());

        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND email = 'riley.recruiter@gmail.com' "
                + "AND first_name = 'Riley' AND email_verified = TRUE AND phone_verified = FALSE "
                + "AND account_status = 'PENDING' AND phone = '+17135550140' AND terms_version = '2026-11-01' "
                + "AND terms_accepted_at IS NOT NULL AND is_deleted = FALSE", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM recruiters WHERE recruiter_id = ? AND manager_id IS NULL", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities WHERE user_id = ? AND provider = 'GOOGLE' "
                + "AND cognito_sub = ?", userId, sub));
        assertEquals(3, rows());
        assertEquals(0, db.count("SELECT COUNT(*) FROM students"));
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = 'forged-sub'"));

        // B. The pending recruiter returns: APPROVAL_PENDING every time, nothing written, not the dashboard.
        for (int visit = 0; visit < 3; visit++) {
            JsonNode pending = lookup(cognito.tokens().accessToken(sub), 200);
            assertTrue(pending.get("registered").asBoolean());
            assertEquals("APPROVAL_PENDING", pending.get("nextStep").asText());
            assertEquals("Your recruiter account is waiting for approval.", pending.get("message").asText());
            assertEquals("PENDING", pending.get("user").get("accountStatus").asText());
            assertEquals(userId, pending.get("user").get("recruiterId").asLong());
            assertFalse(pending.get("user").has("email"));
        }
        assertEquals(3, rows());
        // ... and registering again creates nothing.
        APIGatewayV2HTTPResponse again = register(token, Events.VALID_BODY);
        assertEquals(409, again.getStatusCode());
        assertEquals("ACCOUNT_ALREADY_REGISTERED", JSON.readTree(again.getBody()).get("code").asText());
        assertEquals(3, rows());

        // C. The operator approves in MySQL with the documented script. Exactly one row.
        assertEquals(1, approval.approve(db.dataSource(), userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND account_status = 'ACTIVE' "
                + "AND phone_verified = FALSE", userId));

        // D. The approved recruiter returns: RECRUITER_DASHBOARD with the profile; no Cognito change was needed.
        JsonNode approved = lookup(cognito.tokens().accessToken(sub), 200);
        assertTrue(approved.get("registered").asBoolean());
        assertEquals("RECRUITER_DASHBOARD", approved.get("nextStep").asText());
        JsonNode user = approved.get("user");
        assertEquals(userId, user.get("userId").asLong());
        assertEquals("Riley", user.get("firstName").asText());
        assertEquals("Recruiter", user.get("lastName").asText());
        assertEquals("riley.recruiter@gmail.com", user.get("email").asText());
        assertEquals("+17135550140", user.get("phoneNumber").asText());
        assertEquals("ACTIVE", user.get("accountStatus").asText());
        assertEquals("RECRUITER", user.get("accountType").asText());
        assertFalse(user.get("phoneVerified").asBoolean());
        assertEquals(3, rows());
    }

    // ---- Scenario E: restricted accounts and other account types ----------------------------------------------------------

    @Test
    void suspendedAndDeletedRecruitersAreDeniedAndNeverRecreated() throws Exception {
        String sub = freshGoogleUser("restricted.recruiter@gmail.com");
        String token = cognito.tokens().accessToken(sub);
        assertEquals(201, register(token, Events.VALID_BODY).getStatusCode());
        long userId = db.count("SELECT user_id FROM users WHERE email = 'restricted.recruiter@gmail.com'");

        db.execute("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", userId);
        JsonNode suspended = lookup(token, 403);
        assertEquals("ACCOUNT_SUSPENDED", suspended.get("code").asText());
        assertEquals("Your recruiter account has been suspended.", suspended.get("message").asText());
        assertFalse(suspended.has("nextStep"));

        db.execute("UPDATE users SET account_status = 'ACTIVE', is_deleted = TRUE WHERE user_id = ?", userId);
        JsonNode deleted = lookup(token, 403);
        assertEquals("ACCOUNT_DELETED", deleted.get("code").asText());
        assertEquals("This account is unavailable.", deleted.get("message").asText());
        APIGatewayV2HTTPResponse reRegister = register(token, Events.VALID_BODY);
        assertEquals(403, reRegister.getStatusCode());
        assertEquals("ACCOUNT_DELETED", JSON.readTree(reRegister.getBody()).get("code").asText());
        assertEquals(3, rows());
    }

    @Test
    void aStudentGoogleAccountIsAnAccountTypeMismatchInTheRecruiterPortalAndViceVersa() throws Exception {
        String studentSub = freshGoogleUser("a.student@gmail.com");
        String studentToken = cognito.tokens().accessToken(studentSub);
        assertEquals(201, handler.handleRequest(Events.register(studentToken, Events.VALID_BODY), null).getStatusCode());
        long beforeStudent = rows();

        JsonNode mismatch = lookup(studentToken, 403);
        assertEquals("ACCOUNT_TYPE_MISMATCH", mismatch.get("code").asText());
        assertEquals("This Google account is already associated with another HireMeBot account type.",
                mismatch.get("message").asText());
        APIGatewayV2HTTPResponse register = register(studentToken, Events.VALID_BODY);
        assertEquals(403, register.getStatusCode());
        assertEquals("ACCOUNT_TYPE_MISMATCH", JSON.readTree(register.getBody()).get("code").asText());
        assertEquals(beforeStudent, rows(), "the student is neither converted nor given a second account");
        assertEquals(0, db.count("SELECT COUNT(*) FROM recruiters"));

        // The other direction: a recruiter signing in through the student portal.
        String recruiterSub = freshGoogleUser("a.recruiter@gmail.com");
        String recruiterToken = cognito.tokens().accessToken(recruiterSub);
        assertEquals(201, register(recruiterToken,
                "{\"firstName\":\"Rex\",\"lastName\":\"Recruit\",\"phoneNumber\":\"+17135550150\",\"acceptedTerms\":true}")
                .getStatusCode());
        APIGatewayV2HTTPResponse studentLookup = handler.handleRequest(Events.me(recruiterToken), null);
        assertEquals(403, studentLookup.getStatusCode());
        assertEquals("ACCOUNT_TYPE_MISMATCH", JSON.readTree(studentLookup.getBody()).get("code").asText());
    }

    @Test
    void anExistingEmailUnderAnotherGoogleIdentityIsAConflictBeforeTheForm() throws Exception {
        String original = freshGoogleUser("taken@gmail.com");
        assertEquals(201, register(cognito.tokens().accessToken(original), Events.VALID_BODY).getStatusCode());

        String other = freshGoogleUser("taken@gmail.com");
        JsonNode conflict = lookup(cognito.tokens().accessToken(other), 409);
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", conflict.get("code").asText());
        assertEquals("This email is already associated with another HireMeBot account.", conflict.get("message").asText());
        assertEquals(3, rows());
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", other));
    }

    // ---- identity and token checks ----------------------------------------------------------------------------------------------

    @Test
    void aNativeCognitoUserIsNotAGoogleRecruiterAndTheMessageSaysRecruiter() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addNativeUser(sub, "native@gmail.com");
        String token = cognito.tokens().accessToken(sub);

        JsonNode body = lookup(token, 403);
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", body.get("code").asText());
        assertEquals("Please use Continue with Google to sign in as a recruiter.", body.get("message").asText());
        assertEquals("GOOGLE_ACCOUNT_REQUIRED",
                JSON.readTree(register(token, Events.VALID_BODY).getBody()).get("code").asText());
        assertEquals(0, rows());
    }

    @Test
    void anUnverifiedGoogleEmailIsDeniedOnBothRoutes() throws Exception {
        for (String verified : new String[] {"false", null}) {
            String sub = UUID.randomUUID().toString();
            cognito.addGoogleUser(sub, "unverified" + verified + "@gmail.com", verified);
            String token = cognito.tokens().accessToken(sub);
            assertEquals("EMAIL_NOT_VERIFIED", lookup(token, 403).get("code").asText());
            APIGatewayV2HTTPResponse response = register(token, Events.VALID_BODY);
            assertEquals(403, response.getStatusCode());
            assertEquals("EMAIL_NOT_VERIFIED", JSON.readTree(response.getBody()).get("code").asText());
        }
        assertEquals(0, rows());
    }

    @Test
    void invalidTokensNeverReachCognitoOrMySql() throws Exception {
        String sub = freshGoogleUser("x@gmail.com");
        int userInfoBefore = cognito.userInfoCalls.get();

        assertEquals("UNAUTHORIZED", lookup(null, 401).get("code").asText());
        assertEquals("UNAUTHORIZED", lookup(cognito.tokens().sign(
                cognito.tokens().idClaims(sub, "x@gmail.com").build()), 401).get("code").asText());
        assertEquals("UNAUTHORIZED", lookup(new TestTokens().accessToken(sub), 401).get("code").asText());
        assertEquals("UNAUTHORIZED", lookup(TestTokens.unsigned(cognito.tokens().accessClaims(sub).build()), 401)
                .get("code").asText());
        assertEquals(401, register(null, Events.VALID_BODY).getStatusCode());
        assertEquals(401, register(new TestTokens().accessToken(sub), Events.VALID_BODY).getStatusCode());

        assertEquals(userInfoBefore, cognito.userInfoCalls.get());
        assertEquals(0, rows());
    }
}
