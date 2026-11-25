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
 * The whole Lambda path, locally: the real handler, the real JWT verifier fetching JWKS over HTTP (Nimbus remote
 * source), the real UserInfo HTTP client and the real AWS SDK AdminGetUser client, all pointed at {@link FakeCognito},
 * and real MySQL (a {@link ScratchSchema}). Only Cognito itself is simulated; see the README for the live test.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables set.
 */
class RegistrationEndToEndLocalIntegrationTest {

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
                "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01", true);
        CognitoIdentityService identity = new CognitoIdentityService(
                new CognitoUserInfoClient(cognito.userInfoUri()),
                new CognitoAdminUserLookup(cognito.sdkClient(), TestTokens.POOL_ID));
        StudentRegistrationService service = new StudentRegistrationService(db.dataSource(), new RegistrationValidator(),
                identity, new UserRepository(), new StudentRepository(), new UserIdentityRepository(), config,
                Clock.systemUTC());
        CognitoAccessTokenVerifier verifier = new CognitoAccessTokenVerifier(TestTokens.ISSUER, TestTokens.CLIENT_ID,
                CognitoAccessTokenVerifier.remoteKeys(cognito.jwksUri()), Clock.systemUTC());
        handler = new RegistrationHandler(verifier, service);
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

    @Test
    void aNewGoogleStudentIsRegisteredEndToEnd() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "Alex.Smith@Gmail.com", "true");
        // The body also tries to supply an email and a phone verification: both are ignored.
        String body = """
                {"firstName":" Alex ","lastName":"Smith","phoneNumber":"+1 713 555 0101","acceptedTerms":true,
                 "email":"intruder@example.com","phoneVerified":true,"otpVerified":true}""";

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(Events.register(cognito.tokens().accessToken(sub), body), null);

        assertEquals(201, response.getStatusCode(), response.getBody());
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        long userId = data.get("userId").asLong();
        assertTrue(userId >= 1_000_000_000L);
        assertEquals(userId, data.get("studentId").asLong());
        assertEquals("alex.smith@gmail.com", data.get("email").asText());
        assertEquals("+17135550101", data.get("phoneNumber").asText());
        assertEquals("PENDING", data.get("accountStatus").asText());
        assertEquals("ACCOUNT_PENDING", data.get("nextStep").asText());
        assertFalse(data.get("phoneVerified").asBoolean());

        assertEquals(1, db.count("SELECT COUNT(*) FROM users WHERE user_id = ? AND email = 'alex.smith@gmail.com' "
                + "AND first_name = 'Alex' AND email_verified = TRUE AND phone_verified = FALSE "
                + "AND account_status = 'PENDING' AND terms_version = '2026-11-01' AND terms_accepted_at IS NOT NULL",
                userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM students WHERE student_id = ?", userId));
        assertEquals(1, db.count("SELECT COUNT(*) FROM user_identities WHERE user_id = ? AND provider = 'GOOGLE' "
                + "AND cognito_sub = ?", userId, sub));
        assertEquals(0, db.count("SELECT COUNT(*) FROM users WHERE email = 'intruder@example.com'"));

        // Second attempt with the same Google account.
        APIGatewayV2HTTPResponse again =
                handler.handleRequest(Events.register(cognito.tokens().accessToken(sub), Events.VALID_BODY), null);
        assertEquals(409, again.getStatusCode());
        assertEquals("ACCOUNT_ALREADY_REGISTERED", JSON.readTree(again.getBody()).get("code").asText());
        assertEquals(1, db.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void booleanEmailVerifiedIsAcceptedToo() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "bool@gmail.com", Boolean.TRUE);
        assertEquals(201, handler.handleRequest(
                Events.register(cognito.tokens().accessToken(sub), Events.VALID_BODY), null).getStatusCode());
    }

    @Test
    void anUnverifiedGoogleEmailIsDeniedAndNothingIsWritten() throws Exception {
        // This is what every Google user gets today: the pool does not map Google's email_verified.
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "unverified@gmail.com", null);

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(Events.register(cognito.tokens().accessToken(sub), Events.VALID_BODY), null);

        assertEquals(403, response.getStatusCode());
        assertEquals("EMAIL_NOT_VERIFIED", JSON.readTree(response.getBody()).get("code").asText());
        assertEquals(0, db.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void aNativeCognitoUserWithAGmailAddressIsNotAGoogleSignUp() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addNativeUser(sub, "native@gmail.com");

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(Events.register(cognito.tokens().accessToken(sub), Events.VALID_BODY), null);

        assertEquals(403, response.getStatusCode());
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", JSON.readTree(response.getBody()).get("code").asText());
        assertEquals(0, db.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void anIdTokenIsRejectedBeforeAnyCognitoOrDatabaseCall() throws Exception {
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "idtoken@gmail.com", "true");
        int userInfoBefore = cognito.userInfoCalls.get();

        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.register(
                cognito.tokens().sign(cognito.tokens().idClaims(sub, "idtoken@gmail.com").build()), Events.VALID_BODY),
                null);

        assertEquals(401, response.getStatusCode());
        assertEquals(userInfoBefore, cognito.userInfoCalls.get());
        assertEquals(0, db.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void aTokenFromAnotherPoolIsRejected() throws Exception {
        TestTokens otherPool = new TestTokens();
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                Events.register(otherPool.accessToken(UUID.randomUUID().toString()), Events.VALID_BODY), null);
        assertEquals(401, response.getStatusCode());
    }

    @Test
    void aUserDeletedFromCognitoIsUnauthorized() throws Exception {
        // Valid token, UserInfo still answers, but AdminGetUser no longer finds the user.
        String sub = UUID.randomUUID().toString();
        cognito.addGoogleUser(sub, "gone@gmail.com", "true");
        String token = cognito.tokens().sign(cognito.tokens().accessClaims(sub).claim("username", "google_gone").build());

        APIGatewayV2HTTPResponse response = handler.handleRequest(Events.register(token, Events.VALID_BODY), null);

        assertEquals(401, response.getStatusCode());
        assertEquals(0, db.count("SELECT COUNT(*) FROM users"));
    }
}
