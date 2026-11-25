package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.config.DatabaseConfig;
import org.hmb.config.DocumentUploadConfig;
import org.hmb.dto.DocumentType;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.exception.ApiException;
import org.hmb.repository.AccountProfileRepository;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.RecruiterAccessRepository;
import org.hmb.security.RecruiterAuthenticator;
import org.hmb.security.SigningSecretProvider;
import org.hmb.security.TokenVerifier;
import org.hmb.security.UploadIntent;
import org.hmb.security.UploadTokenService;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.DocumentUploadService;
import org.hmb.storage.S3DocumentStorageService;
import org.hmb.storage.S3DocumentStorageService.PresignedUpload;
import org.hmb.storage.S3DocumentStorageService.StoredObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The document routes end to end through the real handler, the real {@link RecruiterAuthenticator} (account lookup in real
 * MySQL), the real {@link DocumentUploadService} and its SQL authorization; only S3 is mocked and the token verifier is a
 * stub (real signature verification is exercised end to end in RecruiterLambda's token integration test and in
 * {@code common}). Proves the acting recruiter is the one in the token and that another recruiter's student can never be
 * touched.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} with the {@code DB_*} variables pointing at a database that is safe to write
 * to (a scratch copy of the V1 schema). Every test creates its own users and deletes them afterwards.
 */
class DocumentTokenAuthIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPLOAD_URL = "/api/v1/documents/upload-url";
    private static final String REGISTER = "/api/v1/documents/register";
    private static final String PDF = "application/pdf";
    private static final long SIZE = 4096;

    private static DataSource dataSource;

    private record Account(long userId, String sub) {
    }

    private final List<Long> userIds = new ArrayList<>();
    private final Map<String, String> subByToken = new ConcurrentHashMap<>();
    private S3DocumentStorageService storage;
    private UploadTokenService uploadTokens;
    private Account manager;
    private Account recruiterA;
    private Account recruiterB;
    private long studentOfA;
    private long studentOfB;

    /** Accepts exactly the tokens issued by {@link #tokenFor}; anything else behaves like a rejected token. */
    private final TokenVerifier verifier = token -> {
        String sub = subByToken.get(token.value());
        if (sub == null) {
            throw ApiException.unauthorized();
        }
        return new VerifiedAccessToken(sub, "google_1", "client", Set.of("openid"), Instant.now().plusSeconds(600));
    };

    private DocumentLambdaHandler handler;

    @BeforeAll
    static void connect() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS")),
                "Set RUN_DB_TESTS=true and the DB_* variables (on a scratch schema) to run database integration tests");
        dataSource = DatabaseConfig.getDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        if (dataSource == null) {
            return;
        }
        storage = Mockito.mock(S3DocumentStorageService.class);
        uploadTokens = new UploadTokenService(
                new SigningSecretProvider.FixedProvider("integration-test-secret-0123456789abcdef"), Clock.systemUTC());
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PresignedUpload("https://s3.example.test/upload", Map.of()));

        manager = newAccount("ACTIVE", false, "GOOGLE");
        insert("INSERT INTO managers (manager_id) VALUES (?)", manager.userId());
        recruiterA = newRecruiter("ACTIVE", false, "GOOGLE");
        recruiterB = newRecruiter("ACTIVE", false, "GOOGLE");
        studentOfA = newStudent();
        studentOfB = newStudent();
        assign(recruiterA, studentOfA);
        assign(recruiterB, studentOfB);
        handler = handlerWith(false);
    }

    private DocumentLambdaHandler handlerWith(boolean devHeaderPermitted) {
        DocumentUploadService service = new DocumentUploadService(dataSource,
                DocumentUploadConfig.fromEnv(Map.of()), new DocumentRepository(), new RecruiterAccessRepository(), storage,
                uploadTokens, Clock.systemUTC());
        return new DocumentLambdaHandler(service,
                new RecruiterAuthenticator(verifier, dataSource, new AccountProfileRepository(), devHeaderPermitted));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (long id : userIds) {
                execute(c, "DELETE FROM recruiter_student_assignments WHERE recruiter_id = ? OR student_id = ? OR assigned_by = ?",
                        id, id, id);
            }
            // Deleting a student's user cascades to its students row and its object_store_entries rows; recruiters go
            // before their manager (ON DELETE RESTRICT), so users are removed in reverse creation order.
            for (int i = userIds.size() - 1; i >= 0; i--) {
                execute(c, "DELETE FROM users WHERE user_id = ?", userIds.get(i));
            }
        }
        userIds.clear();
        subByToken.clear();
    }

    // ---- the acting recruiter is the one in the token -----------------------------------------------------------------

    @Test
    void anApprovedRecruiterUploadsAndRegistersADocumentForTheirOwnStudent() throws Exception {
        String sha = randomSha();

        APIGatewayV2HTTPResponse upload = post(UPLOAD_URL, bearer(recruiterA), uploadBody(studentOfA, sha));
        assertEquals(200, upload.getStatusCode(), upload.getBody());
        assertEquals("UPLOAD_REQUIRED", JSON.readTree(upload.getBody()).get("status").asText());

        RegisterRequest register = prepareUpload(studentOfA, recruiterA.userId(), sha);
        APIGatewayV2HTTPResponse registered = post(REGISTER, bearer(recruiterA), JSON.writeValueAsString(register));

        assertEquals(201, registered.getStatusCode(), registered.getBody());
        assertEquals(1, count("SELECT COUNT(*) FROM object_store_entries WHERE student_id = ? AND hashed_value = ?",
                studentOfA, sha));
    }

    @Test
    void anotherRecruitersStudentCanNeitherGetAnUploadUrlNorHaveADocumentRegistered() throws Exception {
        String sha = randomSha();

        assertEquals(403, post(UPLOAD_URL, bearer(recruiterA), uploadBody(studentOfB, sha)).getStatusCode());

        // recruiter A holds a perfectly valid upload token for ITS OWN student; pointing it at B's student is refused
        RegisterRequest forged = prepareUpload(studentOfB, recruiterA.userId(), sha);
        assertEquals(403, post(REGISTER, bearer(recruiterA), JSON.writeValueAsString(forged)).getStatusCode());
        // and B's token cannot register a document that A's upload token was issued for
        RegisterRequest mine = prepareUpload(studentOfA, recruiterA.userId(), randomSha());
        assertEquals(403, post(REGISTER, bearer(recruiterB), JSON.writeValueAsString(mine)).getStatusCode());

        assertEquals(0, count("SELECT COUNT(*) FROM object_store_entries WHERE student_id IN (?, ?)", studentOfA, studentOfB));
    }

    @Test
    void anotherRecruitersIdInTheHeadersOrBodyNeverChangesTheActor() throws Exception {
        handler = handlerWith(true); // even where the dev header is permitted, the token wins
        Map<String, String> headers = bearer(recruiterA);
        headers.put("X-Dev-Recruiter-Id", String.valueOf(recruiterB.userId()));
        headers.put("X-Recruiter-Id", String.valueOf(recruiterB.userId()));

        // B's student with A's token and B's id in the header: still A, so still refused
        assertEquals(403, post(UPLOAD_URL, headers, uploadBody(studentOfB, randomSha())).getStatusCode());
        // a recruiter id in the body is an unknown field
        String forged = uploadBody(studentOfB, randomSha()).replaceFirst("\\{", "{\"recruiterId\": " + recruiterB.userId() + ",");
        assertEquals(400, post(UPLOAD_URL, bearer(recruiterA), forged).getStatusCode());
        // A's own student works with the very same forged headers
        assertEquals(200, post(UPLOAD_URL, headers, uploadBody(studentOfA, randomSha())).getStatusCode());
    }

    // ---- who may use the routes at all ------------------------------------------------------------------------------------

    @Test
    void pendingSuspendedDeletedAndNonGoogleRecruitersAre403EvenWithAnAssignedStudentOnBothRoutes() throws Exception {
        for (Account refused : List.of(newRecruiter("PENDING", false, "GOOGLE"),
                newRecruiter("SUSPENDED", false, "GOOGLE"), newRecruiter("ACTIVE", true, "GOOGLE"),
                newRecruiter("ACTIVE", false, "COGNITO"))) {
            long own = newStudent();
            assign(refused, own); // the recruiter really does have a student; that must not rescue the account
            String sha = randomSha();
            RegisterRequest register = prepareUpload(own, refused.userId(), sha);

            assertEquals(403, post(UPLOAD_URL, bearer(refused), uploadBody(own, sha)).getStatusCode(), "upload " + refused);
            assertEquals(403, post(REGISTER, bearer(refused), JSON.writeValueAsString(register)).getStatusCode(),
                    "register " + refused);
            assertEquals(0, count("SELECT COUNT(*) FROM object_store_entries WHERE student_id = ?", own));
        }
    }

    @Test
    void studentManagerRoleLessAndUnregisteredGoogleUsersAreRefused() throws Exception {
        Account student = newStudentAccount();
        Account roleless = newAccount("ACTIVE", false, "GOOGLE");
        for (Account refused : List.of(student, manager, roleless)) {
            assertEquals(403, post(UPLOAD_URL, bearer(refused), uploadBody(studentOfA, randomSha())).getStatusCode(),
                    "upload " + refused);
        }
        String stranger = "stranger-" + UUID.randomUUID();
        subByToken.put("stranger-token", stranger);
        assertEquals(403, post(UPLOAD_URL, bearerRaw("stranger-token"), uploadBody(studentOfA, randomSha())).getStatusCode());
    }

    @Test
    void approvalByTheOperatorTurnsAPendingRecruiterIntoAWorkingOneWithoutANewToken() throws Exception {
        Account pending = newRecruiter("PENDING", false, "GOOGLE");
        long mine = newStudent();
        assign(pending, mine);
        String token = tokenFor(pending);

        assertEquals(403, post(UPLOAD_URL, bearerRaw(token), uploadBody(mine, randomSha())).getStatusCode());

        // The manual approval, on this scratch database only.
        insert("UPDATE users SET account_status = 'ACTIVE' WHERE user_id = ? AND account_status = 'PENDING'", pending.userId());
        assertEquals(200, post(UPLOAD_URL, bearerRaw(token), uploadBody(mine, randomSha())).getStatusCode());

        insert("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = ?", pending.userId());
        assertEquals(403, post(UPLOAD_URL, bearerRaw(token), uploadBody(mine, randomSha())).getStatusCode());
    }

    // ---- tokens and the development header ---------------------------------------------------------------------------------

    @Test
    void anUnknownOrInvalidTokenIs401EvenWithAPermittedValidDevelopmentHeader() throws Exception {
        handler = handlerWith(true);
        Map<String, String> headers = bearerRaw("not-issued-by-anyone");
        headers.put("X-Dev-Recruiter-Id", String.valueOf(recruiterA.userId()));

        APIGatewayV2HTTPResponse upload = post(UPLOAD_URL, headers, uploadBody(studentOfA, randomSha()));
        APIGatewayV2HTTPResponse register = post(REGISTER, headers, "{}");

        assertEquals(401, upload.getStatusCode());
        assertEquals(401, register.getStatusCode());
        assertEquals("UNAUTHORIZED", JSON.readTree(upload.getBody()).get("error").asText());
    }

    @Test
    void theDevelopmentHeaderAloneWorksOnlyOnTheLocalPathAndNeverWhenDeployed() throws Exception {
        Map<String, String> header = new HashMap<>();
        header.put("x-dev-recruiter-id", String.valueOf(recruiterA.userId()));

        assertEquals(401, post(UPLOAD_URL, header, uploadBody(studentOfA, randomSha())).getStatusCode()); // not permitted
        handler = handlerWith(true); // local sam run with the explicit flag
        assertEquals(200, post(UPLOAD_URL, header, uploadBody(studentOfA, randomSha())).getStatusCode());
        assertEquals(403, post(UPLOAD_URL, header, uploadBody(studentOfB, randomSha())).getStatusCode());
        // the service still refuses a pending recruiter on the header path
        Account pending = newRecruiter("PENDING", false, "GOOGLE");
        long mine = newStudent();
        assign(pending, mine);
        Map<String, String> pendingHeader = new HashMap<>();
        pendingHeader.put("x-dev-recruiter-id", String.valueOf(pending.userId()));
        assertEquals(403, post(UPLOAD_URL, pendingHeader, uploadBody(mine, randomSha())).getStatusCode());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private String tokenFor(Account account) {
        String token = "token-" + UUID.randomUUID();
        subByToken.put(token, account.sub());
        return token;
    }

    private Map<String, String> bearer(Account account) {
        return bearerRaw(tokenFor(account));
    }

    private static Map<String, String> bearerRaw(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + token);
        return headers;
    }

    private APIGatewayV2HTTPResponse post(String path, Map<String, String> headers, String body) {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withRequestId("req-it-1")
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod("POST").withPath(path).build())
                        .build())
                .build();
        return handler.handleRequest(event, null);
    }

    private static String uploadBody(long studentId, String sha) throws Exception {
        return JSON.writeValueAsString(new UploadUrlRequest(studentId, "RESUME", "Resume.pdf", PDF, SIZE, sha));
    }

    /** A signed upload intent for this recruiter and student, with the S3 object the service will verify mocked. */
    private RegisterRequest prepareUpload(long studentId, long recruiterId, String sha) {
        String key = "students/%d/resumes/%s.pdf".formatted(studentId, UUID.randomUUID());
        String token = uploadTokens.sign(new UploadIntent(recruiterId, studentId, DocumentType.RESUME, key, sha, SIZE, PDF,
                Clock.systemUTC().instant().getEpochSecond() + 1800));
        when(storage.head(key)).thenReturn(Optional.of(new StoredObject(SIZE, PDF,
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha)))));
        when(storage.readHead(eq(key), anyInt())).thenReturn("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        when(storage.readTail(eq(key), anyInt())).thenReturn("\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
        return new RegisterRequest(studentId, "RESUME", key, sha, token);
    }

    private static String randomSha() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ---- fixtures (all in the scratch schema, all deleted afterwards) -------------------------------------------------------

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
        insert("INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                recruiter.userId(), studentId, manager.userId());
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

    private static long count(String sql, long first, String second) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, first);
            ps.setString(2, second);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }
}
