package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.repository.StudentApplicationRepository;
import org.hmb.repository.StudentIdentityRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.StudentStatisticsRepository;
import org.hmb.security.StudentAuthenticator;
import org.hmb.service.StudentApplicationService;
import org.hmb.service.StudentStatisticsService;
import org.hmb.support.ScratchSchema;
import org.hmb.support.TestJwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code GET /api/v1/student/applications} end to end with REAL pieces everywhere except Cognito itself: the real
 * handler, the real {@link StudentAuthenticator} (the production JWT verifier checking real RSA signatures from a locally
 * generated key), the real service and repositories, and a real MySQL.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and the {@code DB_*} variables name a server where the user may CREATE and
 * DROP databases. Everything runs in a throwaway schema ({@link ScratchSchema}) that is dropped afterwards; {@code DB_NAME}
 * is ignored, so existing databases and their records are never touched. Each test starts from empty tables.
 */
class StudentTokenAuthIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/api/v1/student/applications";
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static ScratchSchema schema;
    private static DataSource dataSource;
    private static final TestJwt JWT = new TestJwt();

    /** A user with the Cognito subject its tokens carry. */
    private record Account(long userId, String sub) {
    }

    private Account studentA;
    private Account studentB;
    private Account recruiter;
    private long applicationsOfA;
    private final List<Long> idsOfA = new ArrayList<>();
    private final List<Long> idsOfB = new ArrayList<>();
    private long managerId;

    @BeforeAll
    static void createSchema() throws Exception {
        assumeTrue(ScratchSchema.enabled(),
                "Set RUN_DB_TESTS=true and the DB_* variables to run database integration tests (scratch schema)");
        schema = ScratchSchema.create();
        dataSource = schema.dataSource();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        if (schema != null) {
            schema.close();
        }
    }

    @BeforeEach
    void seed() throws SQLException {
        for (String table : List.of("applications", "recruiter_student_assignments", "user_identities", "students",
                "recruiters", "managers", "users")) {
            update("DELETE FROM " + table);
        }
        idsOfA.clear();
        idsOfB.clear();

        long manager = newUser("ACTIVE", false);
        managerId = manager;
        update("INSERT INTO managers (manager_id) VALUES (?)", manager);
        recruiter = newAccount("ACTIVE", false, "GOOGLE");
        update("INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", recruiter.userId(), manager);

        studentA = newStudent("ACTIVE", false, "GOOGLE");
        studentB = newStudent("ACTIVE", false, "GOOGLE");
        assign(studentA);
        assign(studentB);
        idsOfA.add(newApplication(studentA, "Backend Engineer A1", 0));
        idsOfA.add(newApplication(studentA, "Backend Engineer A2", 1));
        idsOfB.add(newApplication(studentB, "Data Engineer B1", 2));
        idsOfB.add(newApplication(studentB, "Data Engineer B2", 3));
        idsOfB.add(newApplication(studentB, "Data Engineer B3", 4));
        applicationsOfA = idsOfA.size();
    }

    // ---- ACTIVE ---------------------------------------------------------------------------------------------------

    @Test
    void anActiveStudentGetsTheirOwnApplicationsAndNothingElse() throws Exception {
        APIGatewayV2HTTPResponse response = get(handler(false), bearer(studentA));

        assertEquals(200, response.getStatusCode());
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertEquals(studentA.userId(), data.get("studentId").asLong());
        assertEquals(applicationsOfA, data.get("totalApplications").asLong());
        assertEquals(new HashSet<>(idsOfA), applicationIds(data));
        assertEquals("2026-10-26", data.get("date").asText());
        assertEquals(1, data.get("totalPages").asInt());
    }

    @Test
    void theSuccessResponseKeepsTheDashboardFormat() throws Exception {
        JsonNode root = JSON.readTree(get(handler(false), bearer(studentA)).getBody());

        assertEquals(List.of("success", "data"), names(root));
        assertTrue(root.get("success").asBoolean());
        JsonNode data = root.get("data");
        assertEquals(List.of("studentId", "date", "student", "totalApplications", "pageSize", "totalPages", "pages",
                "statistics"), names(data));
        assertEquals(List.of("firstName", "lastName", "email", "phoneE164", "phoneVerified"), names(data.get("student")));
        assertEquals(List.of("applicationId", "jobTitle", "companyName", "jobLocation", "jobLink", "jobSource",
                "appliedAt"), names(data.get("pages").get("1").get(0)));
        assertEquals(List.of("asOfDate", "timezone", "monthlyProgress", "todayProgress", "weeklyActivity"),
                names(data.get("statistics")));
        assertEquals(20, data.get("pageSize").asInt());
    }

    // ---- cross-student --------------------------------------------------------------------------------------------

    @Test
    void eachStudentSeesExactlyTheirOwnApplicationsFromTheTokenAlone() throws Exception {
        JsonNode a = JSON.readTree(get(handler(false), bearer(studentA)).getBody()).get("data");
        JsonNode b = JSON.readTree(get(handler(false), bearer(studentB)).getBody()).get("data");

        assertEquals(new HashSet<>(idsOfA), applicationIds(a));
        assertEquals(new HashSet<>(idsOfB), applicationIds(b));
        assertEquals(studentA.userId(), a.get("studentId").asLong());
        assertEquals(studentB.userId(), b.get("studentId").asLong());
        assertFalse(a.get("student").get("email").asText().equals(b.get("student").get("email").asText()));
    }

    @Test
    void studentAsTokenWithStudentBsIdInTheHeaderOrQueryNeverShowsStudentBsData() throws Exception {
        for (boolean devHeaderPermitted : new boolean[]{false, true}) {
            Map<String, String> headers = bearer(studentA);
            headers.put("X-Dev-Student-Id", String.valueOf(studentB.userId()));
            headers.put("X-Student-Id", String.valueOf(studentB.userId()));
            APIGatewayV2HTTPEvent event = event(headers);
            event.setQueryStringParameters(Map.of("studentId", String.valueOf(studentB.userId())));

            APIGatewayV2HTTPResponse response = handler(devHeaderPermitted).handleRequest(event, null);

            assertEquals(200, response.getStatusCode());
            JsonNode data = JSON.readTree(response.getBody()).get("data");
            assertEquals(studentA.userId(), data.get("studentId").asLong());
            assertEquals(new HashSet<>(idsOfA), applicationIds(data));
            assertFalse(response.getBody().contains("Data Engineer B"));
        }
    }

    @Test
    void anotherStudentsApplicationsNeverAppearEvenWhenTheTokenOwnerHasNone() throws Exception {
        Account noApplications = newStudent("ACTIVE", false, "GOOGLE");
        assign(noApplications);

        JsonNode data = JSON.readTree(get(handler(false), bearer(noApplications)).getBody()).get("data");

        assertEquals(0, data.get("totalApplications").asInt());
        assertEquals(0, data.get("pages").size());
        assertEquals(noApplications.userId(), data.get("studentId").asLong());
    }

    // ---- statuses and roles that must be refused ------------------------------------------------------------------

    @Test
    void aPendingStudentIs403AndSeesNothing() throws Exception {
        Account pending = newStudent("PENDING", false, "GOOGLE");

        assertRefused(get(handler(false), bearer(pending)), 403, "FORBIDDEN");
    }

    @Test
    void aSuspendedStudentIs403AndSeesNothing() throws Exception {
        Account suspended = newStudent("SUSPENDED", false, "GOOGLE");
        assign(suspended);
        newApplication(suspended, "Suspended Student Job", 5);

        APIGatewayV2HTTPResponse response = get(handler(false), bearer(suspended));

        assertRefused(response, 403, "FORBIDDEN");
        assertFalse(response.getBody().contains("Suspended Student Job"));
    }

    @Test
    void aSoftDeletedStudentIs403EvenIfTheirStatusIsActive() throws Exception {
        Account deleted = newStudent("ACTIVE", true, "GOOGLE");

        assertRefused(get(handler(false), bearer(deleted)), 403, "FORBIDDEN");
    }

    @Test
    void suspendingOrDeletingAStudentTakesEffectOnTheNextRequest() throws Exception {
        StudentLambdaHandler handler = handler(false);
        assertEquals(200, get(handler, bearer(studentA)).getStatusCode());

        update("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", studentA.userId());
        assertEquals(403, get(handler, bearer(studentA)).getStatusCode());

        update("UPDATE users SET account_status = 'ACTIVE' WHERE user_id = ?", studentA.userId());
        assertEquals(200, get(handler, bearer(studentA)).getStatusCode());

        update("UPDATE users SET is_deleted = TRUE WHERE user_id = ?", studentA.userId());
        assertEquals(403, get(handler, bearer(studentA)).getStatusCode());
    }

    @Test
    void aRecruiterOrManagerTokenIs403() throws Exception {
        Account manager = new Account(managerId, "mgr-" + UUID.randomUUID());
        update("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, 'GOOGLE', ?)",
                manager.userId(), manager.sub());

        APIGatewayV2HTTPResponse asRecruiter = get(handler(false), bearer(recruiter));
        assertRefused(asRecruiter, 403, "FORBIDDEN");
        assertEquals("This account is not a student account", JSON.readTree(asRecruiter.getBody()).get("message").asText());
        assertRefused(get(handler(false), bearer(manager)), 403, "FORBIDDEN");
    }

    @Test
    void aUserWithNoRoleRowIs403() throws Exception {
        Account roleless = newAccount("ACTIVE", false, "GOOGLE");

        assertRefused(get(handler(false), bearer(roleless)), 403, "FORBIDDEN");
    }

    @Test
    void aStudentWhoseIdentityIsNotGoogleIs403() throws Exception {
        Account cognitoOnly = newStudent("ACTIVE", false, "COGNITO");

        assertRefused(get(handler(false), bearer(cognitoOnly)), 403, "FORBIDDEN");
    }

    @Test
    void aValidTokenForAnIdentityNotInTheDatabaseIs403() throws Exception {
        Account stranger = new Account(0, UUID.randomUUID().toString());

        assertRefused(get(handler(false), bearer(stranger)), 403, "FORBIDDEN");
    }

    // ---- invalid tokens -------------------------------------------------------------------------------------------

    @Test
    void invalidTokensAre401EvenWithAValidStudentIdInTheDevHeader() throws Exception {
        for (String bad : JWT.badTokensFor(studentA.sub())) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + bad);
            headers.put("X-Dev-Student-Id", String.valueOf(studentA.userId()));

            assertRefused(handler(true).handleRequest(event(headers), null), 401, "UNAUTHORIZED");
        }
    }

    @Test
    void aTokenSignedByAnotherKeyIs401() throws Exception {
        String forged = new TestJwt().accessToken(studentA.sub());

        assertRefused(get(handler(false), Map.of("Authorization", "Bearer " + forged)), 401, "UNAUTHORIZED");
    }

    @Test
    void noTokenIs401AndTheDevHeaderIsIgnoredUnlessPermitted() throws Exception {
        Map<String, String> headers = Map.of("X-Dev-Student-Id", String.valueOf(studentA.userId()));

        assertRefused(get(handler(false), headers), 401, "UNAUTHORIZED");
        assertEquals(200, get(handler(true), headers).getStatusCode());
    }

    // ---- read-only ------------------------------------------------------------------------------------------------

    @Test
    void authenticatedAndRefusedRequestsNeverChangeAnyRow() throws Exception {
        Account pending = newStudent("PENDING", false, "GOOGLE");
        Map<String, Long> baseline = rowCounts();
        String usersBaseline = usersFingerprint();

        get(handler(false), bearer(studentA));
        get(handler(false), bearer(studentB));
        get(handler(false), bearer(pending));
        get(handler(false), bearer(recruiter));
        get(handler(false), Map.of("Authorization", "Bearer garbage"));

        assertEquals(baseline, rowCounts());
        assertEquals(usersBaseline, usersFingerprint());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static StudentLambdaHandler handler(boolean devHeaderPermitted) {
        StudentStatisticsService statistics =
                new StudentStatisticsService(new StudentStatisticsRepository(), CHICAGO, 50);
        Clock noon = Clock.fixed(TODAY.atTime(12, 0).atZone(CHICAGO).toInstant(), ZoneOffset.UTC);
        StudentApplicationService service = new StudentApplicationService(dataSource, new StudentRepository(),
                new StudentApplicationRepository(), statistics, CHICAGO, noon);
        return new StudentLambdaHandler(service, new StudentAuthenticator(JWT.verifier(), dataSource,
                new StudentIdentityRepository(), devHeaderPermitted));
    }

    private static Map<String, String> bearer(Account account) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + JWT.accessToken(account.sub()));
        return headers;
    }

    private static APIGatewayV2HTTPEvent event(Map<String, String> headers) {
        return APIGatewayV2HTTPEvent.builder().withRawPath(PATH).withHeaders(headers)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod("GET").withPath(PATH).build()).build())
                .build();
    }

    private static APIGatewayV2HTTPResponse get(StudentLambdaHandler handler, Map<String, String> headers) {
        return handler.handleRequest(event(headers), null);
    }

    private static void assertRefused(APIGatewayV2HTTPResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatusCode(), response.getBody());
        JsonNode body = JSON.readTree(response.getBody());
        assertFalse(body.get("success").asBoolean());
        assertEquals(code, body.get("error").asText());
        assertFalse(body.has("data"));
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<Long> applicationIds(JsonNode data) {
        Set<Long> ids = new HashSet<>();
        data.get("pages").forEach(page -> page.forEach(app -> ids.add(app.get("applicationId").asLong())));
        return ids;
    }

    private Account newAccount(String status, boolean deleted, String provider) throws SQLException {
        long userId = newUser(status, deleted);
        String sub = UUID.randomUUID().toString();
        update("INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, ?, ?)", userId, provider, sub);
        return new Account(userId, sub);
    }

    private Account newStudent(String status, boolean deleted, String provider) throws SQLException {
        Account account = newAccount(status, deleted, provider);
        update("INSERT INTO students (student_id) VALUES (?)", account.userId());
        return account;
    }

    private void assign(Account student) throws SQLException {
        update("INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                recruiter.userId(), student.userId(), managerId);
    }

    private long newUser(String status, boolean deleted) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (first_name, last_name, email, phone, account_status, is_deleted) "
                             + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "it" + suffix);
            ps.setString(2, "user" + suffix);
            ps.setString(3, "it-" + suffix + "@example.test");
            ps.setString(4, "+1713" + Math.abs(suffix.hashCode() % 10_000_000));
            ps.setString(5, status);
            ps.setBoolean(6, deleted);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    /** An application recorded at noon Chicago today, {@code minute} minutes in. */
    private long newApplication(Account student, String title, int minute) throws SQLException {
        LocalDateTime appliedAtUtc = TODAY.atTime(12, minute).atZone(CHICAGO)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO applications (student_id, recruiter_id, job_title, company_name, job_location, "
                             + "job_link, job_source, applied_at) VALUES (?, ?, ?, 'Test Co', 'Remote', ?, 'IT', ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, student.userId());
            ps.setLong(2, recruiter.userId());
            ps.setString(3, title);
            ps.setString(4, "https://example.test/jobs/" + UUID.randomUUID());
            ps.setObject(5, appliedAtUtc);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void update(String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private static Map<String, Long> rowCounts() throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        for (String table : List.of("users", "user_identities", "students", "recruiters", "managers", "applications",
                "recruiter_student_assignments")) {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                counts.put(table, rs.getLong(1));
            }
        }
        return counts;
    }

    private static String usersFingerprint() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT user_id, account_status, is_deleted, updated_at FROM users "
                     + "ORDER BY user_id")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getLong(1)).append(rs.getString(2)).append(rs.getBoolean(3)).append(rs.getString(4))
                        .append(';');
            }
            return sb.toString();
        }
    }
}
