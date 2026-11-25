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
import org.hmb.logging.RequestIds;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.RecruiterAccessRepository;
import org.hmb.security.RecruiterAuthenticator;
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
 * <p><b>Authentication.</b> The acting recruiter comes only from the verified Cognito <em>access token</em>
 * ({@code Authorization: Bearer ...}), resolved to an approved internal recruiter by {@link RecruiterAuthenticator};
 * nothing the client sends (a recruiter id, a user id, an email) can pick the actor. The service then checks, with that
 * id, that the recruiter is active and assigned to the student, and registers objects under that student only.
 *
 * <p>The {@code X-Dev-Recruiter-Id} header is a local-only fallback: it is consulted only when the request carries no
 * {@code Authorization} header at all <em>and</em> {@code ALLOW_DEV_RECRUITER_HEADER=true} under {@code sam local}
 * ({@link org.hmb.security.DevAuth}). A token, valid or not, always wins, and a deployed stack ignores the header.
 */
public class DocumentLambdaHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

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
    private static volatile RecruiterAuthenticator defaultAuthenticator;

    private final Supplier<DocumentUploadService> serviceSupplier;
    private final Supplier<RecruiterAuthenticator> authenticatorSupplier;

    /** Used by the Lambda runtime. Resources are created lazily and reused while the environment is warm. */
    public DocumentLambdaHandler() {
        this(DocumentLambdaHandler::defaultService, DocumentLambdaHandler::defaultAuthenticator);
    }

    /**
     * For tests of the <em>local header-only</em> path: no token verification and no database for authentication.
     *
     * @param devAuthPermitted whether the dev recruiter header may identify a caller (a request that carries an
     *                         {@code Authorization} header is refused with 401 in this mode)
     */
    public DocumentLambdaHandler(DocumentUploadService service, boolean devAuthPermitted) {
        this(service, RecruiterAuthenticator.devHeaderOnly(devAuthPermitted));
    }

    /** For tests: a fully wired authenticator (token verification, account lookup, dev-header gate). */
    public DocumentLambdaHandler(DocumentUploadService service, RecruiterAuthenticator authenticator) {
        this(() -> service, () -> authenticator);
    }

    private DocumentLambdaHandler(Supplier<DocumentUploadService> serviceSupplier,
                                  Supplier<RecruiterAuthenticator> authenticatorSupplier) {
        this.serviceSupplier = serviceSupplier;
        this.authenticatorSupplier = authenticatorSupplier;
    }

    /** Built from the environment ({@code COGNITO_*}, the dev-header gate) on first use; a bad setting fails closed. */
    private static RecruiterAuthenticator defaultAuthenticator() {
        RecruiterAuthenticator local = defaultAuthenticator;
        if (local == null) {
            synchronized (DocumentLambdaHandler.class) {
                local = defaultAuthenticator;
                if (local == null) {
                    local = RecruiterAuthenticator.fromEnvironment(System.getenv(), DatabaseConfig.getDataSource(),
                            Clock.systemUTC());
                    defaultAuthenticator = local;
                }
            }
        }
        return local;
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
        String requestId = RequestIds.of(
                event.getRequestContext() == null ? null : event.getRequestContext().getRequestId(),
                context == null ? null : context.getAwsRequestId());
        long started = System.nanoTime();
        Route route = null;
        try {
            route = resolveRoute(event);
            // The only source of the acting recruiter: the verified token (or, local only, the header when no token).
            long recruiterId = authenticatorSupplier.get()
                    .authenticate(event.getHeaders(), authorizerClaims(event)).recruiterId();

            APIGatewayV2HTTPResponse response = switch (route) {
                case UPLOAD_URL -> json(200, serviceSupplier.get()
                        .requestUploadUrl(parseBody(event, UploadUrlRequest.class), recruiterId), Map.of());
                case REGISTER -> {
                    RegisterOutcome outcome = serviceSupplier.get()
                            .registerDocument(parseBody(event, RegisterRequest.class), recruiterId);
                    yield json(outcome.created() ? 201 : 200, outcome.body(), Map.of());
                }
            };
            log(route, response.getStatusCode(), "OK", started, requestId);
            return response;
        } catch (ApiException e) {
            log(route, e.getStatusCode(), e.getErrorCode(), started, requestId);
            Map<String, String> extra = e.getStatusCode() == 405 ? Map.of("Allow", allowedMethod(event)) : Map.of();
            return json(e.getStatusCode(), ErrorResponse.from(e), extra);
        } catch (Exception e) {
            LOG.error("Unhandled error [requestId={}]", requestId, e);
            return json(500, ErrorResponse.from(ApiException.internal(e)), Map.of());
        }
    }

    /** The claims of API Gateway's JWT authorizer when it ran (deployed); empty under {@code sam local}. */
    private static Map<String, String> authorizerClaims(APIGatewayV2HTTPEvent event) {
        var context = event.getRequestContext();
        if (context == null || context.getAuthorizer() == null || context.getAuthorizer().getJwt() == null
                || context.getAuthorizer().getJwt().getClaims() == null) {
            return Map.of();
        }
        return context.getAuthorizer().getJwt().getClaims();
    }

    /** One line per request: method, path, outcome, time and request id. Never headers, bodies, URLs or tokens. */
    private static void log(Route route, int status, String outcome, long started, String requestId) {
        long millis = (System.nanoTime() - started) / 1_000_000;
        String target = route == null ? "(unmatched route)" : route.method + " " + route.path;
        if (status >= 500) {
            LOG.error("{} -> {} {} in {} ms [requestId={}]", target, status, outcome, millis, requestId);
        } else {
            LOG.info("{} -> {} {} in {} ms [requestId={}]", target, status, outcome, millis, requestId);
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
