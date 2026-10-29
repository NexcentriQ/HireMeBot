package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.hmb.config.AppConfig;
import org.hmb.config.DatabaseConfig;
import org.hmb.config.StatisticsConfig;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.exception.ApiException;
import org.hmb.exception.ErrorResponse;
import org.hmb.repository.StudentApplicationRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.StudentStatisticsRepository;
import org.hmb.service.StudentApplicationService;
import org.hmb.service.StudentStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Lambda entry point for {@code GET /api/v1/student/applications}.
 *
 * <p>The endpoint always reports the current date (resolved in the reporting zone) and takes no query parameters.
 * The deprecated {@code date} parameter, and other period/pagination parameters, are rejected with 400.
 *
 * <p><b>The {@code X-Dev-Student-Id} header is NOT authentication.</b> It exists only for local development
 * and is honoured only when the environment variable {@code ALLOW_DEV_STUDENT_HEADER} is {@code true}.
 * It must be replaced by Cognito/JWT claims before this API is exposed publicly.
 */
public class StudentLambdaHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    static final String ROUTE_PATH = "/api/v1/student/applications";
    static final String DEV_STUDENT_HEADER = "X-Dev-Student-Id";
    static final String DEV_HEADER_ENABLE_VAR = "ALLOW_DEV_STUDENT_HEADER";

    /**
     * Query parameters the frontend must no longer send: the backend chooses all reporting periods and returns
     * every page at once. Compared case-insensitively (stored lower-case).
     */
    static final Set<String> REJECTED_PARAMS = Set.of("date", "startdate", "enddate", "month", "week", "page", "size");

    private static final Logger LOG = LoggerFactory.getLogger(StudentLambdaHandler.class);

    /** Shared across warm invocations. Only used to write responses. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static volatile StudentApplicationService defaultService;

    private final Supplier<StudentApplicationService> serviceSupplier;
    private final boolean devHeaderEnabled;

    /** Used by the Lambda runtime. Resources are created lazily and reused while the environment is warm. */
    public StudentLambdaHandler() {
        this(StudentLambdaHandler::defaultService,
                "true".equalsIgnoreCase(System.getenv(DEV_HEADER_ENABLE_VAR)));
    }

    /** For tests. */
    public StudentLambdaHandler(StudentApplicationService service, boolean devHeaderEnabled) {
        this(() -> service, devHeaderEnabled);
    }

    private StudentLambdaHandler(Supplier<StudentApplicationService> serviceSupplier, boolean devHeaderEnabled) {
        this.serviceSupplier = serviceSupplier;
        this.devHeaderEnabled = devHeaderEnabled;
    }

    private static StudentApplicationService defaultService() {
        StudentApplicationService local = defaultService;
        if (local == null) {
            synchronized (StudentLambdaHandler.class) {
                local = defaultService;
                if (local == null) {
                    Map<String, String> env = System.getenv();
                    ZoneId zone = AppConfig.timeZone(env);
                    StudentStatisticsService statistics = new StudentStatisticsService(
                            new StudentStatisticsRepository(), zone, StatisticsConfig.dailyTarget(env));
                    local = new StudentApplicationService(DatabaseConfig.getDataSource(),
                            new StudentRepository(), new StudentApplicationRepository(), statistics,
                            zone, Clock.systemUTC());
                    defaultService = local;
                }
            }
        }
        return local;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            checkRoute(event);
            long studentId = readStudentId(event);
            rejectDeprecatedParams(event);

            StudentApplicationsResponse result = serviceSupplier.get().getApplications(studentId);
            return json(200, result, Map.of());
        } catch (ApiException e) {
            Map<String, String> extra = e.getStatusCode() == 405 ? Map.of("Allow", "GET") : Map.of();
            return json(e.getStatusCode(), ErrorResponse.from(e), extra);
        } catch (Exception e) {
            LOG.error("Unhandled error", e);
            return json(500, ErrorResponse.from(ApiException.internal(e)), Map.of());
        }
    }

    private static void checkRoute(APIGatewayV2HTTPEvent event) {
        String path = event.getRawPath();
        String method = null;
        if (event.getRequestContext() != null && event.getRequestContext().getHttp() != null) {
            method = event.getRequestContext().getHttp().getMethod();
            if (path == null) {
                path = event.getRequestContext().getHttp().getPath();
            }
        }
        if (path != null && path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!ROUTE_PATH.equals(path)) {
            throw new ApiException(404, "NOT_FOUND", "Route not found");
        }
        if (!"GET".equalsIgnoreCase(method)) {
            throw new ApiException(405, "METHOD_NOT_ALLOWED", "Method not allowed; use GET");
        }
    }

    private long readStudentId(APIGatewayV2HTTPEvent event) {
        if (!devHeaderEnabled) {
            // Fail closed: without real authentication in place, a plain header must never identify a student.
            throw ApiException.forbidden("Student authentication is not configured");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (event.getHeaders() != null) {
            headers.putAll(event.getHeaders());
        }
        String raw = headers.get(DEV_STUDENT_HEADER);
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Missing required header " + DEV_STUDENT_HEADER);
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("not positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(DEV_STUDENT_HEADER + " must be a positive number");
        }
    }

    /**
     * Rejects the deprecated {@code date} parameter (and the other period/pagination parameters) with 400, so a
     * caller is never silently given a different day than it asked for. Any other parameter is ignored.
     */
    private static void rejectDeprecatedParams(APIGatewayV2HTTPEvent event) {
        Map<String, String> params = event.getQueryStringParameters();
        if (params == null || params.isEmpty()) {
            return;
        }
        for (String key : params.keySet()) {
            if (key != null && REJECTED_PARAMS.contains(key.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("Query parameter '" + key
                        + "' is not accepted; this endpoint always reports the current date and returns all pages");
            }
        }
    }

    private static APIGatewayV2HTTPResponse json(int status, Object body, Map<String, String> extraHeaders) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Content-Type", "application/json");
        headers.putAll(extraHeaders);
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(headers)
                    .withBody(MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize response", e);
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(headers)
                    .withBody("{\"success\":false,\"error\":\"INTERNAL_ERROR\",\"message\":\"An unexpected error occurred.\"}")
                    .build();
        }
    }
}
