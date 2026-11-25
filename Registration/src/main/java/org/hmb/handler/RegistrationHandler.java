package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.hmb.config.RegistrationConfig;
import org.hmb.config.RegistrationDataSource;
import org.hmb.dto.AccountLookupResponse;
import org.hmb.dto.ApiResponse;
import org.hmb.dto.RecruiterAccountLookupResponse;
import org.hmb.dto.RecruiterRegistrationData;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AccessTokenVerifier;
import org.hmb.security.BearerToken;
import org.hmb.security.CognitoAccessTokenVerifier;
import org.hmb.security.CognitoAdminUserLookup;
import org.hmb.security.CognitoUserInfoClient;
import org.hmb.security.VerifiedAccessToken;
import org.hmb.service.AccountLookupService;
import org.hmb.service.CognitoIdentityService;
import org.hmb.service.RecruiterAccountLookupService;
import org.hmb.service.RecruiterAccountRules;
import org.hmb.service.RecruiterRegistrationService;
import org.hmb.service.RegistrationValidator;
import org.hmb.service.StudentRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lambda entry point of the {@code Registration} function (API Gateway HTTP API, payload format 2.0), serving the
 * unified "Continue with Google" sign-in of both portals:
 * <ul>
 *   <li>{@code GET /api/v1/auth/me}: student account lookup &rarr; {@code DASHBOARD}, {@code SIGNUP_FORM} or
 *       {@code ACCOUNT_PENDING} ({@link AccountLookupService}, read-only);</li>
 *   <li>{@code POST /api/v1/students/register}: create the student account ({@link StudentRegistrationService});</li>
 *   <li>{@code GET /api/v1/recruiters/auth/me}: recruiter account lookup &rarr; {@code RECRUITER_DASHBOARD},
 *       {@code RECRUITER_SIGNUP_FORM} or {@code APPROVAL_PENDING} ({@link RecruiterAccountLookupService}, read-only);</li>
 *   <li>{@code POST /api/v1/recruiters/register}: create the recruiter account, always {@code PENDING} until the
 *       operator approves it in MySQL ({@link RecruiterRegistrationService}).</li>
 * </ul>
 *
 * <p>Order of checks: route (404/405) &rarr; bearer token present and locally verified (401) &rarr; for the two
 * registration routes only: JSON content type (415), body size (413), JSON shape and types (400) &rarr; service. The
 * token is verified here on every route even when API Gateway's JWT authorizer already ran (and under
 * {@code sam local}, which does not run JWT authorizers), so a direct invocation or a forged request context gets
 * nowhere; when authorizer claims are present they must name the same subject. Identity comes only from the token:
 * query parameters, and identity fields in the body, are ignored.
 *
 * <p>Every response is JSON with {@code Cache-Control: no-store}; errors carry the request id (also in
 * {@code X-Request-Id}). Logs carry the request id, outcome and timing, never the token, names, email or phone. CORS
 * headers are added by API Gateway (the API's CorsConfiguration), not here.
 */
public class RegistrationHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    static final String ROUTE_PATH = "/api/v1/students/register";
    static final String ME_PATH = "/api/v1/auth/me";
    static final String RECRUITER_ROUTE_PATH = "/api/v1/recruiters/register";
    static final String RECRUITER_ME_PATH = "/api/v1/recruiters/auth/me";
    /** The valid body is ~150 bytes; 4 KB leaves room for formatting without accepting bulk input. */
    static final int MAX_BODY_BYTES = 4 * 1024;
    private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8 * 1024 + 16;
    /** RFC 6750 b64token after a case-insensitive "Bearer" scheme. */
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer +([A-Za-z0-9._~+/-]+=*)");

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationHandler.class);
    /** Set by the SAM CLI in its local containers; never set by AWS in a deployed Lambda. */
    private static final boolean SAM_LOCAL = "true".equalsIgnoreCase(System.getenv("AWS_SAM_LOCAL"));
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The routes of this Lambda. A path has exactly one allowed method. */
    enum Route {
        ME(ME_PATH, "GET"),
        REGISTER(ROUTE_PATH, "POST"),
        RECRUITER_ME(RECRUITER_ME_PATH, "GET"),
        RECRUITER_REGISTER(RECRUITER_ROUTE_PATH, "POST");

        final String path;
        final String method;

        Route(String path, String method) {
            this.path = path;
            this.method = method;
        }

        static Route forPath(String path) {
            for (Route route : values()) {
                if (route.path.equals(path)) {
                    return route;
                }
            }
            return null;
        }
    }

    /** Everything the handler needs, created once per container. */
    public record Components(AccessTokenVerifier verifier, StudentRegistrationService service,
                             AccountLookupService lookup, RecruiterRegistrationService recruiterService,
                             RecruiterAccountLookupService recruiterLookup) {

        /** The student services only (the recruiter routes then answer 500). */
        public Components(AccessTokenVerifier verifier, StudentRegistrationService service,
                          AccountLookupService lookup) {
            this(verifier, service, lookup, null, null);
        }
    }

    private static volatile Components defaultComponents;

    private final Supplier<Components> components;

    /** Used by the Lambda runtime. Components are created on first use and reused while the container is warm. */
    public RegistrationHandler() {
        this(RegistrationHandler::defaultComponents);
    }

    /** For tests that exercise all four routes. */
    public RegistrationHandler(AccessTokenVerifier verifier, StudentRegistrationService service,
                               AccountLookupService lookup, RecruiterRegistrationService recruiterService,
                               RecruiterAccountLookupService recruiterLookup) {
        this(() -> new Components(verifier, service, lookup, recruiterService, recruiterLookup));
    }

    /** For tests that exercise the two student routes (the recruiter routes then answer 500). */
    public RegistrationHandler(AccessTokenVerifier verifier, StudentRegistrationService service,
                               AccountLookupService lookup) {
        this(verifier, service, lookup, null, null);
    }

    /** For registration-only tests ({@code GET /api/v1/auth/me} then answers 500). */
    public RegistrationHandler(AccessTokenVerifier verifier, StudentRegistrationService service) {
        this(verifier, service, null);
    }

    private RegistrationHandler(Supplier<Components> components) {
        this.components = components;
    }

    private static Components defaultComponents() {
        Components local = defaultComponents;
        if (local == null) {
            synchronized (RegistrationHandler.class) {
                local = defaultComponents;
                if (local == null) {
                    Map<String, String> env = System.getenv();
                    RegistrationConfig config = RegistrationConfig.fromEnv(env);
                    Clock clock = Clock.systemUTC();
                    CognitoIdentityService identity = new CognitoIdentityService(
                            new CognitoUserInfoClient(config.userInfoUri()),
                            CognitoAdminUserLookup.create(config.region(), config.userPoolId()));
                    DataSource dataSource = RegistrationDataSource.get(env);
                    UserRepository users = new UserRepository();
                    UserIdentityRepository identities = new UserIdentityRepository();
                    StudentRegistrationService service = new StudentRegistrationService(dataSource,
                            new RegistrationValidator(), identity, users, new StudentRepository(), identities, config,
                            clock);
                    AccountLookupService lookup = new AccountLookupService(dataSource, identity, identities, users);
                    RecruiterRegistrationService recruiterService = new RecruiterRegistrationService(dataSource,
                            new RegistrationValidator(), identity, users, new RecruiterRepository(), identities, config,
                            clock);
                    RecruiterAccountLookupService recruiterLookup =
                            new RecruiterAccountLookupService(dataSource, identity, identities, users);
                    local = new Components(CognitoAccessTokenVerifier.forConfig(config, clock), service, lookup,
                            recruiterService, recruiterLookup);
                    defaultComponents = local;
                    LOG.info("Registration configured (student accountStatus on signup={}, recruiter accountStatus on "
                                    + "signup={}, termsVersion={})", config.initialAccountStatus(),
                            RecruiterAccountRules.REGISTRATION_STATUS, config.termsVersion());
                }
            }
        }
        return local;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String requestId = requestId(event, context);
        long started = System.nanoTime();
        Route route = null;
        try {
            route = resolveRoute(event);
            Map<String, String> headers = headers(event);
            BearerToken bearer = bearerToken(headers);
            Components c = components.get();
            VerifiedAccessToken token = c.verifier().verify(bearer);
            checkAuthorizerSubject(event, token);

            switch (route) {
                case ME -> {
                    AccountLookupResponse result = c.lookup().lookup(token, bearer);
                    log(route, 200, result.nextStep().name(), started, requestId);
                    return json(200, result, requestId, Map.of());
                }
                case RECRUITER_ME -> {
                    RecruiterAccountLookupResponse result = c.recruiterLookup().lookup(token, bearer);
                    log(route, 200, result.nextStep().name(), started, requestId);
                    return json(200, result, requestId, Map.of());
                }
                case REGISTER -> {
                    checkContentType(headers);
                    StudentRegistrationRequest request = RegistrationRequestParser.parse(body(event));
                    StudentRegistrationData data = c.service().register(token, bearer, request);
                    log(route, 201, "STUDENT_REGISTERED", started, requestId);
                    return json(201, ApiResponse.registered(data), requestId, Map.of());
                }
                case RECRUITER_REGISTER -> {
                    checkContentType(headers);
                    // Same four-field body and strict parsing as the student registration.
                    StudentRegistrationRequest request = RegistrationRequestParser.parse(body(event));
                    RecruiterRegistrationData data = c.recruiterService().register(token, bearer, request);
                    log(route, 201, "RECRUITER_REGISTRATION_SUBMITTED", started, requestId);
                    return json(201, ApiResponse.recruiterRegistered(data), requestId, Map.of());
                }
                default -> throw new IllegalStateException("Unhandled route " + route);
            }
        } catch (RegistrationException e) {
            log(route, e.status(), e.code(), started, requestId);
            Map<String, String> extra = e.status() == 405 ? Map.of("Allow", allowedMethod(event)) : Map.of();
            return json(e.status(), ApiResponse.error(e, requestId), requestId, extra);
        } catch (Exception e) {
            LOG.error("Unhandled error [requestId={}]", requestId, e);
            return json(500, ApiResponse.error(RegistrationException.internal(e), requestId), requestId, Map.of());
        }
    }

    // ---- request checks ----------------------------------------------------------------------------------------

    /** Matches the path first (404 if unknown), then the method (405 with {@code Allow} if the route forbids it). */
    private static Route resolveRoute(APIGatewayV2HTTPEvent event) {
        Route route = Route.forPath(normalizedPath(event));
        if (route == null) {
            throw RegistrationException.notFound();
        }
        String method = event.getRequestContext() != null && event.getRequestContext().getHttp() != null
                ? event.getRequestContext().getHttp().getMethod() : null;
        if (!route.method.equalsIgnoreCase(method)) {
            throw RegistrationException.methodNotAllowed(route.method);
        }
        return route;
    }

    private static String allowedMethod(APIGatewayV2HTTPEvent event) {
        Route route = Route.forPath(normalizedPath(event));
        return route == null ? "" : route.method;
    }

    private static String normalizedPath(APIGatewayV2HTTPEvent event) {
        String path = event.getRawPath();
        if (path == null && event.getRequestContext() != null && event.getRequestContext().getHttp() != null) {
            path = event.getRequestContext().getHttp().getPath();
        }
        if (path != null && path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static BearerToken bearerToken(Map<String, String> headers) {
        String authorization = headers.get("Authorization");
        if (authorization == null || authorization.isBlank()) {
            LOG.info("Rejected request: no Authorization header");
            throw RegistrationException.unauthorized();
        }
        if (authorization.length() > MAX_AUTHORIZATION_HEADER_LENGTH) {
            LOG.info("Rejected request: oversized Authorization header");
            throw RegistrationException.unauthorized();
        }
        Matcher matcher = BEARER.matcher(authorization.strip());
        if (!matcher.matches()) {
            LOG.info("Rejected request: Authorization header is not a Bearer token");
            throw RegistrationException.unauthorized();
        }
        return new BearerToken(matcher.group(1));
    }

    /**
     * When API Gateway's JWT authorizer ran, its claims must describe the same user as the token verified here. A
     * mismatch means the request context was forged or the wrong token was forwarded.
     */
    private static void checkAuthorizerSubject(APIGatewayV2HTTPEvent event, VerifiedAccessToken token) {
        APIGatewayV2HTTPEvent.RequestContext ctx = event.getRequestContext();
        if (ctx == null || ctx.getAuthorizer() == null || ctx.getAuthorizer().getJwt() == null
                || ctx.getAuthorizer().getJwt().getClaims() == null) {
            return;
        }
        String authorizerSubject = ctx.getAuthorizer().getJwt().getClaims().get("sub");
        if (authorizerSubject != null && !authorizerSubject.equals(token.subject())) {
            LOG.warn("Security: authorizer subject does not match the verified access token");
            throw RegistrationException.unauthorized();
        }
    }

    /** {@code application/json}, optionally with {@code charset=utf-8}; anything else is 415. */
    static void checkContentType(Map<String, String> headers) {
        String contentType = headers.get("Content-Type");
        if (contentType == null) {
            throw RegistrationException.unsupportedMediaType();
        }
        String[] parts = contentType.split(";");
        if (!"application/json".equalsIgnoreCase(parts[0].trim())) {
            throw RegistrationException.unsupportedMediaType();
        }
        for (int i = 1; i < parts.length; i++) {
            String[] parameter = parts[i].split("=", 2);
            if (parameter.length == 2 && "charset".equalsIgnoreCase(parameter[0].trim())) {
                String charset = parameter[1].trim().replace("\"", "").toLowerCase(Locale.ROOT);
                if (!charset.equals("utf-8") && !charset.equals("utf8")) {
                    throw RegistrationException.unsupportedMediaType();
                }
            }
        }
    }

    private static String body(APIGatewayV2HTTPEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            throw RegistrationException.invalidRequest("The request body is required.");
        }
        if (event.getIsBase64Encoded()) {
            if (body.length() > (MAX_BODY_BYTES / 3 + 1) * 4) {
                throw RegistrationException.payloadTooLarge();
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(body);
            } catch (IllegalArgumentException e) {
                throw RegistrationException.invalidRequest("The request body is not valid base64.");
            }
            if (decoded.length > MAX_BODY_BYTES) {
                throw RegistrationException.payloadTooLarge();
            }
            return new String(decoded, StandardCharsets.UTF_8);
        }
        if (body.length() > MAX_BODY_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw RegistrationException.payloadTooLarge();
        }
        return body;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Map<String, String> headers(APIGatewayV2HTTPEvent event) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (event.getHeaders() != null) {
            headers.putAll(event.getHeaders());
        }
        return headers;
    }

    /**
     * API Gateway's request id (matches its access logs), else the Lambda request id, else a random one. Under
     * {@code sam local} the emulated API Gateway sends the same request id on every call, so the Lambda id is used.
     */
    static String requestId(APIGatewayV2HTTPEvent event, Context context) {
        if (!SAM_LOCAL && event != null && event.getRequestContext() != null) {
            String id = event.getRequestContext().getRequestId();
            if (id != null && id.matches("[A-Za-z0-9=._-]{1,100}")) {
                return id;
            }
        }
        if (context != null && context.getAwsRequestId() != null) {
            return context.getAwsRequestId();
        }
        return UUID.randomUUID().toString();
    }

    private static void log(Route route, int status, String code, long started, String requestId) {
        long millis = (System.nanoTime() - started) / 1_000_000;
        String target = route == null ? "(unmatched route)" : route.method + " " + route.path;
        if (status >= 500) {
            LOG.error("{} -> {} {} in {} ms [requestId={}]", target, status, code, millis, requestId);
        } else {
            LOG.info("{} -> {} {} in {} ms [requestId={}]", target, status, code, millis, requestId);
        }
    }

    private static APIGatewayV2HTTPResponse json(int status, Object body, String requestId,
                                                 Map<String, String> extraHeaders) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Content-Type", "application/json");
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Request-Id", requestId);
        headers.putAll(extraHeaders);
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize response [requestId={}]", requestId, e);
            status = 500;
            serialized = "{\"success\":false,\"code\":\"INTERNAL_ERROR\",\"message\":\"An unexpected error occurred.\"}";
        }
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(headers)
                .withBody(serialized)
                .build();
    }
}
