package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.config.DatabaseConfig;
import org.hmb.repository.AccountProfileRepository;
import org.hmb.repository.ApplicationRepository;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.QuestionRepository;
import org.hmb.repository.RecruiterRepository;
import org.hmb.security.LocalJwks;
import org.hmb.security.RecruiterAuthenticator;
import org.hmb.service.ApplicationService;
import org.hmb.service.RecruiterStudentService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The recruiter routes end to end with REAL pieces everywhere except Cognito itself: the real handler, the real
 * {@link RecruiterAuthenticator} with the production JWT verifier reading a real (locally served) JWKS and verifying real
 * RSA signatures, the real services and repositories, and real MySQL. Only a stand-in key endpoint replaces the user pool.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and the {@code DB_*} variables point at a database that is safe to write
 * to (use a throwaway copy of the V1 schema, never a database with real users). Every test creates its own users and
 * deletes them afterwards; the schema is never changed.
 */
class RecruiterTokenAuthIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STUDENTS = "/api/v1/recruiter/students";
    private static final String APPLICATIONS = "/api/v1/recruiter/applications";

    private static DataSource dataSource;
    private static LocalJwks jwks;

    /** A Google-linked recruiter (or other account) with the Cognito subject its tokens carry. */
    private record Account(long userId, String sub) {
    }

    private final List<Long> userIds = new ArrayList<>();
    private Account manager;
    private Account recruiterA;
    private Account recruiterB;
    private long studentOfA1;
    private long studentOfA2;
    private long studentOfB;
    private long resumeOfA1;

    private RecruiterLambdaHandler handler;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS")),
                "Set RUN_DB_TESTS=true and the DB_* variables (on a scratch schema) to run database integration tests");
        dataSource = DatabaseConfig.getDataSource();
        jwks = new LocalJwks();
    }

    @AfterAll
    static void stopJwks() {
        if (jwks != null) {
            jwks.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (dataSource == null) {
            return;
        }
        manager = newAccount("ACTIVE", false, "GOOGLE");
        insert("INSERT INTO managers (manager_id) VALUES (?)", manager.userId());
        recruiterA = newRecruiter("ACTIVE", false, "GOOGLE");
        recruiterB = newRecruiter("ACTIVE", false, "GOOGLE");
        studentOfA1 = newStudent();
        studentOfA2 = newStudent();
        studentOfB = newStudent();
        assign(recruiterA, studentOfA1);
        assign(recruiterA, studentOfA2);
        assign(recruiterB, studentOfB);
        resumeOfA1 = newResume(studentOfA1);
        handler = handlerWith(false);
    }

    private RecruiterLambdaHandler handlerWith(boolean devHeaderPermitted) {
        return new RecruiterLambdaHandler(
                new ApplicationService(dataSource, new AssignmentRepository(), new ApplicationRepository(),
                        new DocumentRepository(), new QuestionRepository()),
                new RecruiterStudentService(dataSource, new RecruiterRepository(), new AssignmentRepository()),
                new RecruiterAuthenticator(jwks.verifier(), dataSource, new AccountProfileRepository(), devHeaderPermitted));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (long id : userIds) {
                execute(c, "DELETE FROM applications WHERE recruiter_id = ? OR student_id = ?", id, id);
            }
            for (long id : userIds) {
                execute(c, "DELETE FROM recruiter_student_assignments WHERE recruiter_id = ? OR student_id = ? OR assigned_by = ?",
                        id, id, id);
            }
            // Deleting the user cascades to its role row, identity and objects; recruiters before their manager.
            for (int i = userIds.size() - 1; i >= 0; i--) {
                execute(c, "DELETE FROM users WHERE user_id = ?", userIds.get(i));
            }
        }
        userIds.clear();
    }

    // ---- students: exactly the caller's own, taken from the token ---------------------------------------------------------

    @Test
    void eachRecruiterSeesExactlyTheirOwnAssignedStudentsFromTheTokenAlone() throws Exception {
        assertEquals(Set.of(studentOfA1, studentOfA2), studentIds(listStudents(bearer(recruiterA), 200)));
        assertEquals(Set.of(studentOfB), studentIds(listStudents(bearer(recruiterB), 200)));
        assertEquals(recruiterA.userId(), JSON.readTree(
                handler.handleRequest(get(STUDENTS, bearer(recruiterA), null), null).getBody())
                .get("data").get("recruiterId").asLong());
    }

    @Test
    void anotherRecruitersIdInTheHeaderOrQueryNeverShowsTheirStudents() throws Exception {
        handler = handlerWith(true); // even where the dev header is permitted, the token wins
        Map<String, String> headers = bearer(recruiterA);
        headers.put("X-Dev-Recruiter-Id", String.valueOf(recruiterB.userId()));
        headers.put("X-Recruiter-Id", String.valueOf(recruiterB.userId()));
        APIGatewayV2HTTPEvent event = get(STUDENTS, headers, null);
        event.setQueryStringParameters(Map.of("recruiterId", String.valueOf(recruiterB.userId()),
                "userId", String.valueOf(recruiterB.userId())));

        Set<Long> seen = studentIds(JSON.readTree(handler.handleRequest(event, null).getBody()));

        assertEquals(Set.of(studentOfA1, studentOfA2), seen);
        assertFalse(seen.contains(studentOfB));
    }

    // ---- applications: the student must be assigned to the token's recruiter ---------------------------------------------------

    @Test
    void anAssignedStudentIsAccepted() throws Exception {
        APIGatewayV2HTTPResponse response = post(APPLICATIONS, bearer(recruiterA), applicationJson(studentOfA1, resumeOfA1));

        assertEquals(201, response.getStatusCode(), response.getBody());
        long applicationId = JSON.readTree(response.getBody()).get("applicationId").asLong();
        assertEquals(1, count("SELECT COUNT(*) FROM applications WHERE application_id = ? AND recruiter_id = ? AND student_id = ?",
                applicationId, recruiterA.userId(), studentOfA1));
    }

    @Test
    void anotherRecruitersStudentIsRefusedAndNothingIsWritten() throws Exception {
        long before = count("SELECT COUNT(*) FROM applications WHERE student_id = ?", studentOfB);

        APIGatewayV2HTTPResponse response = post(APPLICATIONS, bearer(recruiterA),
                applicationJson(studentOfB, newResume(studentOfB)));

        assertEquals(403, response.getStatusCode(), response.getBody());
        assertEquals(before, count("SELECT COUNT(*) FROM applications WHERE student_id = ?", studentOfB));
        assertEquals(0, count("SELECT COUNT(*) FROM applications WHERE recruiter_id = ?", recruiterA.userId()));
    }

    @Test
    void anotherStudentsDocumentCannotBeAttachedToAnAssignedStudent() throws Exception {
        long foreignResume = newResume(studentOfB);

        APIGatewayV2HTTPResponse response = post(APPLICATIONS, bearer(recruiterA), applicationJson(studentOfA1, foreignResume));

        assertTrue(response.getStatusCode() >= 400 && response.getStatusCode() < 500, response.getBody());
        assertEquals(0, count("SELECT COUNT(*) FROM applications WHERE recruiter_id = ?", recruiterA.userId()));
    }

    @Test
    void aRecruiterIdInTheApplicationBodyIsRejectedNotUsed() throws Exception {
        String forged = applicationJson(studentOfB, newResume(studentOfB)).replaceFirst("\\{",
                "{\"recruiterId\": " + recruiterB.userId() + ",");

        APIGatewayV2HTTPResponse response = post(APPLICATIONS, bearer(recruiterA), forged);

        assertEquals(400, response.getStatusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM applications WHERE student_id = ?", studentOfB));
    }

    // ---- who may use the routes at all ----------------------------------------------------------------------------------------

    @Test
    void pendingSuspendedDeletedAndNonGoogleRecruitersAre403EvenWithAnAssignedStudentOnBothRoutes() throws Exception {
        for (Account refused : List.of(newRecruiter("PENDING", false, "GOOGLE"),
                newRecruiter("SUSPENDED", false, "GOOGLE"), newRecruiter("ACTIVE", true, "GOOGLE"),
                newRecruiter("ACTIVE", false, "COGNITO"))) {
            long own = newStudent();
            assign(refused, own); // the recruiter really does have a student; that must not rescue the account
            long ownResume = newResume(own);

            assertEquals(403, handler.handleRequest(get(STUDENTS, bearer(refused), null), null).getStatusCode(),
                    "students " + refused);
            assertEquals(403, post(APPLICATIONS, bearer(refused), applicationJson(own, ownResume)).getStatusCode(),
                    "applications " + refused);
            assertEquals(0, count("SELECT COUNT(*) FROM applications WHERE student_id = ?", own));
        }
    }

    @Test
    void studentManagerAndRoleLessAccountsAreRefusedAndAGoogleUserWithNoAccountToo() throws Exception {
        Account student = newStudentAccount();
        Account roleless = newAccount("ACTIVE", false, "GOOGLE");
        for (Account refused : List.of(student, manager, roleless)) {
            assertEquals(403, handler.handleRequest(get(STUDENTS, bearer(refused), null), null).getStatusCode(),
                    "students " + refused);
            assertEquals(403, post(APPLICATIONS, bearer(refused), applicationJson(studentOfA1, resumeOfA1)).getStatusCode(),
                    "applications " + refused);
        }
        String stranger = "stranger-" + UUID.randomUUID();
        assertEquals(403, handler.handleRequest(get(STUDENTS, bearer(stranger), null), null).getStatusCode());
        assertEquals(403, post(APPLICATIONS, bearer(stranger), applicationJson(studentOfA1, resumeOfA1)).getStatusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM applications WHERE student_id = ?", studentOfA1));
    }

    @Test
    void approvalByTheOperatorTurnsAPendingRecruiterIntoAWorkingOneWithoutANewToken() throws Exception {
        Account pending = newRecruiter("PENDING", false, "GOOGLE");
        long mine = newStudent();
        assign(pending, mine, true);
        String token = jwks.accessToken(pending.sub());

        assertEquals(403, handler.handleRequest(get(STUDENTS, bearerRaw(token), null), null).getStatusCode());

        // The manual approval, on this scratch database only.
        insert("UPDATE users SET account_status = 'ACTIVE' WHERE user_id = ? AND account_status = 'PENDING'", pending.userId());

        assertEquals(Set.of(mine), studentIds(listStudents(bearerRaw(token), 200)));
        // ... and a suspension takes effect on the very next call.
        insert("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", pending.userId());
        assertEquals(403, handler.handleRequest(get(STUDENTS, bearerRaw(token), null), null).getStatusCode());
    }

    // ---- tokens -------------------------------------------------------------------------------------------------------------------

    @Test
    void invalidExpiredWrongPoolWrongClientAndIdTokensAre401EvenWithAPermittedValidHeader() throws Exception {
        handler = handlerWith(true);
        for (String token : jwks.badTokensFor(recruiterA.sub())) {
            Map<String, String> headers = bearerRaw(token);
            headers.put("X-Dev-Recruiter-Id", String.valueOf(recruiterA.userId()));
            for (APIGatewayV2HTTPResponse response : List.of(handler.handleRequest(get(STUDENTS, headers, null), null),
                    post(APPLICATIONS, headers, applicationJson(studentOfA1, resumeOfA1)))) {
                assertEquals(401, response.getStatusCode(), token.length() > 25 ? token.substring(0, 25) : token);
                assertEquals("UNAUTHORIZED", JSON.readTree(response.getBody()).get("error").asText());
            }
        }
        // A token signed by another pool's key is a forgery.
        try (LocalJwks otherPool = new LocalJwks()) {
            assertEquals(401, handler.handleRequest(get(STUDENTS, bearerRaw(otherPool.accessToken(recruiterA.sub())), null),
                    null).getStatusCode());
        }
        assertEquals(0, count("SELECT COUNT(*) FROM applications WHERE recruiter_id = ?", recruiterA.userId()));
    }

    @Test
    void theDevelopmentHeaderAloneWorksOnlyOnTheLocalPathAndNeverWhenDeployed() throws Exception {
        Map<String, String> header = new HashMap<>();
        header.put("x-dev-recruiter-id", String.valueOf(recruiterA.userId()));

        assertEquals(401, handler.handleRequest(get(STUDENTS, header, null), null).getStatusCode()); // not permitted
        handler = handlerWith(true); // local sam run with the explicit flag
        assertEquals(Set.of(studentOfA1, studentOfA2), studentIds(listStudents(header, 200)));
        // the services still refuse a pending recruiter on the header path
        Account pending = newRecruiter("PENDING", false, "GOOGLE");
        Map<String, String> pendingHeader = new HashMap<>();
        pendingHeader.put("x-dev-recruiter-id", String.valueOf(pending.userId()));
        assertEquals(403, handler.handleRequest(get(STUDENTS, pendingHeader, null), null).getStatusCode());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------------------

    private Map<String, String> bearer(Account account) {
        return bearerRaw(jwks.accessToken(account.sub()));
    }

    private Map<String, String> bearer(String sub) {
        return bearerRaw(jwks.accessToken(sub));
    }

    private static Map<String, String> bearerRaw(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + token);
        return headers;
    }

    private static APIGatewayV2HTTPEvent get(String path, Map<String, String> headers, String body) {
        return event("GET", path, headers, body);
    }

    private APIGatewayV2HTTPResponse post(String path, Map<String, String> headers, String body) {
        return handler.handleRequest(event("POST", path, headers, body), null);
    }

    private static APIGatewayV2HTTPEvent event(String method, String path, Map<String, String> headers, String body) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withRequestId("req-it-1")
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method).withPath(path).build())
                        .build())
                .build();
    }

    private JsonNode listStudents(Map<String, String> headers, int expectedStatus) throws Exception {
        APIGatewayV2HTTPResponse response = handler.handleRequest(get(STUDENTS, headers, null), null);
        assertEquals(expectedStatus, response.getStatusCode(), response.getBody());
        return JSON.readTree(response.getBody());
    }

    private static Set<Long> studentIds(JsonNode body) {
        Set<Long> ids = new HashSet<>();
        body.get("data").get("students").forEach(s -> ids.add(s.get("studentId").asLong()));
        return ids;
    }

    private static String applicationJson(long studentId, long resumeId) {
        return """
                {"studentId": %d,
                 "application": {"jobTitle": "Integration Test Engineer", "companyName": "Test Co", "jobLocation": "Remote",
                                 "jobLink": "https://example.test/jobs/%s", "jobSource": "IT"},
                 "documents": {"resumeObjectId": %d},
                 "questions": [{"question": "Are you willing to relocate?", "answer": "Yes"}]}
                """.formatted(studentId, UUID.randomUUID(), resumeId);
    }

    // ---- fixtures (all in the scratch schema, all deleted afterwards) ---------------------------------------------------------

    private Account newAccount(String status, boolean deleted, String provider) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sub = UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (first_name, last_name, email, phone, email_verified, account_status, is_deleted) "
                             + "VALUES (?, ?, ?, ?, TRUE, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "it" + suffix);
            ps.setString(2, "user" + suffix);
            ps.setString(3, "it-" + suffix + "@example.test");
            ps.setString(4, "9" + (100000000 + ThreadLocalRandom.current().nextInt(900000000)));
            ps.setString(5, status);
            ps.setBoolean(6, deleted);
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                id = keys.getLong(1);
            }
            userIds.add(id);
            try (PreparedStatement identity = c.prepareStatement(
                    "INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, ?, ?)")) {
                identity.setLong(1, id);
                identity.setString(2, provider);
                identity.setString(3, sub);
                identity.executeUpdate();
            }
            return new Account(id, sub);
        }
    }

    private Account newRecruiter(String status, boolean deleted, String provider) throws SQLException {
        Account account = newAccount(status, deleted, provider);
        insert("INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", account.userId(), manager.userId());
        return account;
    }

    private Account newStudentAccount() throws SQLException {
        Account account = newAccount("ACTIVE", false, "GOOGLE");
        insert("INSERT INTO students (student_id) VALUES (?)", account.userId());
        return account;
    }

    private long newStudent() throws SQLException {
        return newStudentAccount().userId();
    }

    private void assign(Account recruiter, long studentId) throws SQLException {
        assign(recruiter, studentId, true);
    }

    /** @param mustSucceed false when the recruiter row may not exist (the insert is then expected to be refused) */
    private void assign(Account recruiter, long studentId, boolean mustSucceed) throws SQLException {
        try {
            insert("INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                    recruiter.userId(), studentId, manager.userId());
        } catch (SQLException e) {
            if (mustSucceed) {
                throw e;
            }
        }
    }

    private long newResume(long studentId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO object_store_entries (student_id, document_type, object_key, hashed_value) "
                             + "VALUES (?, 'RESUME', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, studentId);
            ps.setString(2, "it/" + UUID.randomUUID());
            ps.setString(3, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void insert(String sql, long... params) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            execute(c, sql, params);
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

    private static long count(String sql, long... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setLong(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }
}
