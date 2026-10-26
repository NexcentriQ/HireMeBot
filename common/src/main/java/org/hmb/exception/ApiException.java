package org.hmb.exception;

import java.util.List;

/**
 * A failure that maps directly to an HTTP status code. The message and details are returned to the
 * client, so they must never contain SQL, stack traces or credentials.
 */
public class ApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final List<String> details;

    public ApiException(int statusCode, String errorCode, String message) {
        this(statusCode, errorCode, message, List.of(), null);
    }

    public ApiException(int statusCode, String errorCode, String message, List<String> details) {
        this(statusCode, errorCode, message, details, null);
    }

    public ApiException(int statusCode, String errorCode, String message, Throwable cause) {
        this(statusCode, errorCode, message, List.of(), cause);
    }

    private ApiException(int statusCode, String errorCode, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException validation(List<String> details) {
        return new ApiException(400, "VALIDATION_ERROR", "Request validation failed", details);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message, Throwable cause) {
        return new ApiException(409, "CONFLICT", message, cause);
    }

    public static ApiException serviceUnavailable(Throwable cause) {
        return new ApiException(503, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable. Please try again later.", cause);
    }

    public static ApiException internal(Throwable cause) {
        return new ApiException(500, "INTERNAL_ERROR", "An unexpected error occurred.", cause);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }
}
