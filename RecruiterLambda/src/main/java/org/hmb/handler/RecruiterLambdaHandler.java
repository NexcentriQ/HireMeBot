package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.hmb.config.DatabaseConfig;
import org.hmb.dto.RecruiterRequest;
import org.hmb.dto.RecruiterResponse;
import org.hmb.exception.ApiException;
import org.hmb.exception.ErrorResponse;
import org.hmb.repository.ApplicationRepository;
import org.hmb.repository.AssignmentRepository;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.QuestionRepository;
import org.hmb.service.ApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Lambda entry point for {@code POST /api/v1/recruiter/applications}.
 *
 * <p><b>The {@code X-Dev-Recruiter-Id} header is NOT authentication.</b> It exists only for local development
 * and is honoured only when the environment variable {@code ALLOW_DEV_RECRUITER_HEADER} is {@code true}.
 * It must be replaced by Cognito/JWT claims before this API is exposed publicly.
 */
public class RecruiterLambdaHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    static final String ROUTE_PATH = "/api/v1/recruiter/applications";
    static final String DEV_RECRUITER_HEADER = "X-Dev-Recruiter-Id";
    static final String DEV_HEADER_ENABLE_VAR = "ALLOW_DEV_RECRUITER_HEADER";

    private static final Logger LOG = LoggerFactory.getLogger(RecruiterLambdaHandler.class);

    /** Shared across warm invocations. Strict: no scalar coercion, no unknown properties, no trailing tokens. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static volatile ApplicationService defaultService;

    private final Supplier<ApplicationService> serviceSupplier;
    private final boolean devHeaderEnabled;

    /** Used by the Lambda runtime. Resources are created lazily and reused while the environment is warm. */
    public RecruiterLambdaHandler() {
        this(RecruiterLambdaHandler::defaultService,
                "true".equalsIgnoreCase(System.getenv(DEV_HEADER_ENABLE_VAR)));
    }

    /** For tests. */
    public RecruiterLambdaHandler(ApplicationService service, boolean devHeaderEnabled) {
        this(() -> service, devHeaderEnabled);
    }

    private RecruiterLambdaHandler(Supplier<ApplicationService> serviceSupplier, boolean devHeaderEnabled) {
        this.serviceSupplier = serviceSupplier;
        this.devHeaderEnabled = devHeaderEnabled;
    }

    private static ApplicationService defaultService() {
        ApplicationService local = defaultService;
        if (local == null) {
            synchronized (RecruiterLambdaHandler.class) {
                local = defaultService;
                if (local == null) {
                    local = new ApplicationService(DatabaseConfig.getDataSource(),
                            new AssignmentRepository(), new ApplicationRepository(), new DocumentRepository(),
                            new QuestionRepository());
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
            long recruiterId = readRecruiterId(event);
            RecruiterRequest request = parseBody(event);

            RecruiterResponse result = serviceSupplier.get().createApplication(recruiterId, request);
            return json(201, result, Map.of());
        } catch (ApiException e) {
            Map<String, String> extra = e.getStatusCode() == 405 ? Map.of("Allow", "POST") : Map.of();
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
        if (!"POST".equalsIgnoreCase(method)) {
            throw new ApiException(405, "METHOD_NOT_ALLOWED", "Method not allowed; use POST");
        }
    }

    private long readRecruiterId(APIGatewayV2HTTPEvent event) {
        if (!devHeaderEnabled) {
            // Fail closed: without real authentication in place, a plain header must never identify a recruiter.
            throw ApiException.forbidden("Recruiter authentication is not configured");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (event.getHeaders() != null) {
            headers.putAll(event.getHeaders());
        }
        String raw = headers.get(DEV_RECRUITER_HEADER);
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Missing required header " + DEV_RECRUITER_HEADER);
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("not positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(DEV_RECRUITER_HEADER + " must be a positive number");
        }
    }

    private static RecruiterRequest parseBody(APIGatewayV2HTTPEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("Request body is required");
        }
        if (event.getIsBase64Encoded()) {
            try {
                body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Request body is not valid base64");
            }
        }
        try {
            return MAPPER.readValue(body, RecruiterRequest.class);
        } catch (UnrecognizedPropertyException e) {
            String name = e.getPropertyName();
            if (name != null && name.length() > 50) {
                name = name.substring(0, 50) + "...";
            }
            throw ApiException.badRequest("Unknown field '" + name + "' is not allowed");
        } catch (JsonProcessingException e) {
            LOG.debug("Rejected malformed JSON: {}", e.getOriginalMessage());
            throw ApiException.badRequest("Malformed or invalid JSON request body");
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
