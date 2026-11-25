package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.config.RegistrationConfig;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.security.CognitoAdminUserLookup;
import org.hmb.security.CognitoUserInfoClient;
import org.hmb.service.AccountLookupService;
import org.hmb.service.CognitoIdentityService;
import org.hmb.service.RegistrationValidator;
import org.hmb.service.StudentRegistrationService;
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
 * Definition-of-done scenarios A-D for "Continue with Google", locally and end to end: the real handler routing both
 * endpoints, the real JWT verifier (JWKS over HTTP), the real UserInfo client and the real AWS SDK AdminGetUser
 * client against {@link FakeCognito}, and real MySQL (V1 schema). The V1 policy is configured exactly as deployed:
 * {@code PHONE_VERIFICATION_REQUIRED=false}.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class UnifiedSignInEndToEndLocalIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ScratchSchema db;
    private static FakeCognito cognito;
    private static RegistrationHandler handler;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(ScratchSchema.enabled(), "Set RUN_DB_TESTS=true and DB_* to run the MySQL integration tests");
        db = ScratchSchema.create();
        cognito = new FakeCognito(new TestTokens());
        RegistrationConfig config = new RegistrationConfig("us-east-2", TestTokens.POOL_ID, TestTokens.CLIENT_ID,
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", false);
        CognitoIdentityService identity = new CognitoIdentityService(
                new CognitoUserInfoClient(cognito.userInfoUri()),
                new CognitoAdminUserLookup(cognito.sdkClient(), TestTokens.POOL_ID));
        UserRepository users = new UserRepository();
        UserIdentityRepository identities = new UserIdentityRepository();
        StudentRegistrationService registration = new StudentRegistrationService(db.dataSource(),
                new RegistrationValidator(), identity, users, new StudentRepository(), identities, config,
                Clock.systemUTC());
        AccountLookupService lookup = new AccountLookupService(db.dataSource(), identity, identities, users);
        handler = new RegistrationHandler(new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID,
                CognitoAccessTokenVerifier.remoteKeys(cognito.jwksUri()), Clock.systemUTC()), registration, lookup);
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

    private static JsonNode me(String token, int expectedStatus) throws Exception {
        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.me(token), null);
        assertEquals(expectedStatus, response.getStatusCode(), response.getBody());
        return JSON.readTree(response.getBody());
    }

    private static long rows() throws Exception {
        return db.count("SELECT COUNT(*) FROM users") + db.count("SELECT COUNT(*) FROM students")
                + db.count("SELECT COUNT(*) FROM user_identities");
    }

    // ---- Scenario B (first-time student), then A/C (returning student) -----------------------------------------------

    @Test
    void firstTimeStudentSignsUpAndIsRecognisedOnEveryLaterVisit() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "Priya.Shah@Gmail.com", "true");
        String token = cognito.tokens().accessToken(sub);

        // 1. Continue with Google -> GET /auth/me -> SIGNUP_FORM, nothing created.
        JsonNode lookup = me(token, 200);
        assertTrue(lookup.get("success").asBoolean());
        assertFalse(lookup.get("registered").asBoolean());
        assertEquals("SIGNUP_FORM", lookup.get("nextStep").asText());
        assertEquals("Complete your HireMeBot student registration.", lookup.get("message").asText());
        assertFalse(lookup.has("user"));
        assertEquals(0, rows());

        // 2. Signup form + mock OTP -> POST /students/register (the same session/token). Identity fields in the body
        //    and a "verified" mock OTP are ignored.
        String body = """
                {"firstName":"Priya","lastName":"Shah","phoneNumber":"+1 (713) 555-0120","acceptedTerms":true,
                 "email":"someone.else@gmail.com","cognitoSub":"forged-sub","userId":42,
                 "phoneVerified":true,"otpVerified":true}""";
        APIGatewayV2HTTPResponse registered = handler.handleRequest(Events.register(token, body), null);
        assertEquals(201, registered.getStatusCode(), registered.getBody());
        JsonNode data = JSON.readTree(registered.getBody()).get("data");
        long userId = data.get("userId").asLong();
        assertEquals(userId, data.get("studentId").asLong());
        assertEquals("priya.shah@gmail.com", data.get("email").asText());
        assertEquals("ACTIVE", data.get("accountStatus").asText());
        assertEquals("DASHBOARD", data.get("nextStep").asText());
        assertTrue(data.get("emailVerified").asBoolean());
        assertFalse(data.get("phoneVerified").asBoolean());

        // Exactly one row per table, all with the same internal id, identity from the token.
        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND email = 'priya.shah@gmail.com' "
                + "AND email_verified = TRUE AND phone_verified = FALSE AND account_status = 'ACTIVE' "
                + "AND phone = '+17135550120' AND terms_version = '2026-11-01' AND terms_accepted_at IS NOT NULL", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM students WHERE student_id = ?", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities WHERE user_id = ? AND provider = 'GOOGLE' "
                + "AND cognito_sub = ?", userId, sub));
        assertEquals(3, rows());
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = 'forged-sub'"));

        // 3. Returning student: GET /auth/me -> DASHBOARD with the profile, every time, nothing written.
        for (int visit = 0; visit < 3; visit++) {
            JsonNode returning = me(cognito.tokens().accessToken(sub), 200);
            assertTrue(returning.get("registered").asBoolean());
            assertEquals("DASHBOARD", returning.get("nextStep").asText());
            JsonNode user = returning.get("user");
            assertEquals(userId, user.get("userId").asLong());
            assertEquals("Priya", user.get("firstName").asText());
            assertEquals("Shah", user.get("lastName").asText());
            assertEquals("priya.shah@gmail.com", user.get("email").asText());
            assertEquals("+17135550120", user.get("phoneNumber").asText());
            assertEquals("ACTIVE", user.get("accountStatus").asText());
            assertEquals("STUDENT", user.get("accountType").asText());
        }
        assertEquals(3, rows());

        // 4. A second registration attempt (double submit, back button) creates nothing.
        APIGatewayV2HTTPResponse again = handler.handleRequest(Events.register(token, Events.VALID_BODY), null);
        assertEquals(409, again.getStatusCode());
        assertEquals("ACCOUNT_ALREADY_REGISTERED", JSON.readTree(again.getBody()).get("code").asText());
        assertEquals(3, rows());
    }

    // ---- Scenario D: restricted accounts --------------------------------------------------------------------------------

    @Test
    void suspendedDeletedAndPendingStudents() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "restricted@gmail.com", "true");
        String token = cognito.tokens().accessToken(sub);
        assertEquals(201, handler.handleRequest(Events.register(token, Events.VALID_BODY), null).getStatusCode());
        long userId = db.count("SELECT user_id FROM users WHERE email = 'restricted@gmail.com'");

        db.execute("UPDATE users SET account_status = 'PENDING' WHERE user_id = ?", userId);
        JsonNode pending = me(token, 200);
        assertEquals("ACCOUNT_PENDING", pending.get("nextStep").asText());
        assertEquals("PENDING", pending.get("user").get("accountStatus").asText());

        db.execute("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", userId);
        JsonNode suspended = me(token, 403);
        assertEquals("ACCOUNT_SUSPENDED", suspended.get("code").asText());
        assertEquals("Your account is currently suspended.", suspended.get("message").asText());
        assertFalse(suspended.has("nextStep"));

        db.execute("UPDATE users SET account_status = 'ACTIVE', is_deleted = TRUE WHERE user_id = ?", userId);
        JsonNode deleted = me(token, 403);
        assertEquals("ACCOUNT_DELETED", deleted.get("code").asText());
        assertEquals("This account is unavailable.", deleted.get("message").asText());
        // ... and registering again does not recreate it.
        APIGatewayV2HTTPResponse reRegister = handler.handleRequest(Events.register(token, Events.VALID_BODY), null);
        assertEquals(403, reRegister.getStatusCode());
        assertEquals("ACCOUNT_DELETED", JSON.readTree(reRegister.getBody()).get("code").asText());
        assertEquals(3, rows());
    }

    @Test
    void aRecruiterIdentityIsAnAccountTypeMismatch() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "recruiter@hiremebot.test", "true");
        db.execute("INSERT INTO users (first_name, last_name, email, phone, email_verified, account_status) "
                + "VALUES ('Rita', 'Recruiter', 'recruiter@hiremebot.test', '+17135550198', TRUE, 'ACTIVE')");
        long recruiterId = db.count("SELECT user_id FROM users WHERE email = 'recruiter@hiremebot.test'");
        db.execute("INSERT INTO recruiters (recruiter_id) VALUES (?)", recruiterId);
        db.execute("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, 'GOOGLE', ?)",
                recruiterId, sub);

        JsonNode body = me(cognito.tokens().accessToken(sub), 403);
        assertEquals("ACCOUNT_TYPE_MISMATCH", body.get("code").asText());
        APIGatewayV2HTTPResponse register = handler.handleRequest(
                Events.register(cognito.tokens().accessToken(sub), Events.VALID_BODY), null);
        assertEquals("ACCOUNT_TYPE_MISMATCH", JSON.readTree(register.getBody()).get("code").asText());
        assertEquals(0, db.count("SELECT COUNT(*) FROM students"));
    }

    @Test
    void anExistingEmailUnderAnotherGoogleIdentityIsAConflictBeforeTheForm() throws Exception {
        String original = UUID.randomUUID().toString();
        cognito.addGoogleUser(original, "taken@gmail.com", "true");
        assertEquals(201, handler.handleRequest(
                Events.register(cognito.tokens().accessToken(original), Events.VALID_BODY), null).getStatusCode());

        String other = UUID.randomUUID().toString();
        cognito.addGoogleUser(other, "taken@gmail.com", "true");
        JsonNode body = me(cognito.tokens().accessToken(other), 409);
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", body.get("code").asText());
        assertEquals(3, rows());
        assertEquals(0, db.count("SELECT COUNT(*) FROM user_identities WHERE cognito_sub = ?", other));
    }

    // ---- identity checks on the lookup -------------------------------------------------------------------------------------

    @Test
    void aNativeCognitoUserIsNotSentToTheStudentSignupForm() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addNativeUser(sub, "native@gmail.com");
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", me(cognito.tokens().accessToken(sub), 403).get("code").asText());
        assertEquals(0, rows());
    }

    @Test
    void anUnverifiedGoogleEmailIsNotSentToTheSignupForm() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "unverified@gmail.com", "false");
        assertEquals("EMAIL_NOT_VERIFIED", me(cognito.tokens().accessToken(sub), 403).get("code").asText());
    }

    @Test
    void invalidTokensNeverReachCognitoOrMySql() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "x@gmail.com", "true");
        int userInfoBefore = cognito.userInfoCalls.get();

        assertEquals("UNAUTHORIZED", me(null, 401).get("code").asText());
        assertEquals("UNAUTHORIZED", me(cognito.tokens().sign(
                cognito.tokens().idClaims(sub, "x@gmail.com").build()), 401).get("code").asText());
        assertEquals("UNAUTHORIZED", me(new TestTokens().accessToken(sub), 401).get("code").asText());
        assertEquals("UNAUTHORIZED", me(TestTokens.unsigned(cognito.tokens().accessClaims(sub).build()), 401)
                .get("code").asText());

        assertEquals(userInfoBefore, cognito.userInfoCalls.get());
        assertEquals(0, rows());
    }
}
