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
import org.hmb.config.DocumentUploadConfig;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.exception.ApiException;
import org.hmb.exception.ErrorResponse;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.RecruiterAccessRepository;
import org.hmb.security.DevAuth;
import org.hmb.security.SigningSecretProvider;
import org.hmb.security.UploadTokenService;
import org.hmb.service.DocumentUploadService;
import org.hmb.service.DocumentUploadService.RegisterOutcome;
import org.hmb.storage.S3DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Lambda entry point for the document upload API:
 * <ul>
 *   <li>{@code POST /api/v1/documents/upload-url}: duplicate check and presigned S3 PUT</li>
 *   <li>{@code POST /api/v1/documents/register}: verify the uploaded object and register it in MySQL</li>
 * </ul>
 * Routes are matched on path and method. Responses carry {@code Cache-Control: no-store}: they hold upload URLs and
 * tokens, which are credentials and must never be logged or cached.
 *
 * <p><b>Authentication is development-only today.</b> The recruiter is identified by {@code X-Dev-Recruiter-Id}, which
 * is honoured only under {@code sam local} (see {@link DevAuth}). In a deployed Lambda every call is therefore
 * answered with 403 until production authentication exists. <b>Production authentication (a validated Cognito/JWT
 * identity mapped to a {@code recruiters.recruiter_id}) is a prerequisite before these APIs are exposed publicly</b>;
 * it replaces only {@link #resolveRecruiter}.
 */
public class DocumentLambdaHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    static final String DEV_RECRUITER_HEADER = "X-Dev-Recruiter-Id";

    /** The routes this Lambda serves. A path has exactly one allowed method. */
    enum Route {
        UPLOAD_URL("/api/v1/documents/upload-url", "POST"),
        REGISTER("/api/v1/documents/register", "POST");

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

    private static final Logger LOG = LoggerFactory.getLogger(DocumentLambdaHandler.class);

    /** Shared across warm invocations. Strict: no scalar coercion, no unknown properties, no trailing tokens. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static volatile DocumentUploadService defaultService;

    private final Supplier<DocumentUploadService> serviceSupplier;
    private final boolean devAuthPermitted;

    /** Used by the Lambda runtime. Resources are created lazily and reused while the environment is warm. */
    public DocumentLambdaHandler() {
        this(DocumentLambdaHandler::defaultService, DevAuth.permitted(System.getenv()));
    }

    /**
     * For tests.
     *
     * @param devAuthPermitted whether the dev recruiter header may identify a caller (see {@link DevAuth#permitted})
     */
    public DocumentLambdaHandler(DocumentUploadService service, boolean devAuthPermitted) {
        this(() -> service, devAuthPermitted);
    }

    private DocumentLambdaHandler(Supplier<DocumentUploadService> serviceSupplier, boolean devAuthPermitted) {
        this.serviceSupplier = serviceSupplier;
        this.devAuthPermitted = devAuthPermitted;
    }

    private static DocumentUploadService defaultService() {
        DocumentUploadService local = defaultService;
        if (local == null) {
            synchronized (DocumentLambdaHandler.class) {
                local = defaultService;
                if (local == null) {
                    Map<String, String> env = System.getenv();
                    DocumentUploadConfig config = DocumentUploadConfig.fromEnv(env);
                    Clock clock = Clock.systemUTC();
                    local = new DocumentUploadService(DatabaseConfig.getDataSource(), config, new DocumentRepository(),
                            new RecruiterAccessRepository(), S3DocumentStorageService.create(config.bucket()),
                            new UploadTokenService(SigningSecretProvider.fromEnvironment(env), clock), clock);
                    defaultService = local;
                }
            }
        }
        return local;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            Route route = resolveRoute(event);
            long recruiterId = resolveRecruiter(event);

            return switch (route) {
                case UPLOAD_URL -> json(200, serviceSupplier.get()
                        .requestUploadUrl(parseBody(event, UploadUrlRequest.class), recruiterId), Map.of());
                case REGISTER -> {
                    RegisterOutcome outcome = serviceSupplier.get()
                            .registerDocument(parseBody(event, RegisterRequest.class), recruiterId);
                    yield json(outcome.created() ? 201 : 200, outcome.body(), Map.of());
                }
            };
        } catch (ApiException e) {
            Map<String, String> extra = e.getStatusCode() == 405 ? Map.of("Allow", allowedMethod(event)) : Map.of();
            return json(e.getStatusCode(), ErrorResponse.from(e), extra);
        } catch (Exception e) {
            LOG.error("Unhandled error", e);
            return json(500, ErrorResponse.from(ApiException.internal(e)), Map.of());
        }
    }

    /** Matches the path first (404 if unknown), then the method (405 if the route does not allow it). */
    private static Route resolveRoute(APIGatewayV2HTTPEvent event) {
        Route route = Route.forPath(normalizedPath(event));
        if (route == null) {
            throw new ApiException(404, "NOT_FOUND", "Route not found");
        }
        if (!route.method.equalsIgnoreCase(requestMethod(event))) {
            throw new ApiException(405, "METHOD_NOT_ALLOWED", "Method not allowed; use " + route.method);
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

    private static String requestMethod(APIGatewayV2HTTPEvent event) {
        if (event.getRequestContext() != null && event.getRequestContext().getHttp() != null) {
            return event.getRequestContext().getHttp().getMethod();
        }
        return null;
    }

    /**
     * Identifies the caller. This is the one place that changes when production authentication (a validated
     * Cognito/JWT identity mapped to a recruiter id) replaces the development header.
     *
     * @throws ApiException 403 when dev authentication is not permitted here (any deployed environment),
     *                      400 when the header is missing or invalid
     */
    private long resolveRecruiter(APIGatewayV2HTTPEvent event) {
        if (!devAuthPermitted) {
            // Fail closed: a plain header must never identify a recruiter outside local development.
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

    private static <T> T parseBody(APIGatewayV2HTTPEvent event, Class<T> type) {
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
            return MAPPER.readValue(body, type);
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
        headers.put("Cache-Control", "no-store");
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
