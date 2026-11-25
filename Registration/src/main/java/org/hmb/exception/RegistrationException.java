package org.hmb.exception;

import org.hmb.dto.FieldError;

import java.util.List;

/**
 * A registration failure that maps directly to an HTTP status and a stable {@code code}. The message and field errors
 * are returned to the client, so they never contain SQL, tokens, stack traces or credentials; the technical cause (if
 * any) is kept only for server-side logging.
 */
public class RegistrationException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public RegistrationException(int status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    private RegistrationException(int status, String code, String message, List<FieldError> fieldErrors,
                                  Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    // ---- 400 ---------------------------------------------------------------------------------------------------

    public static RegistrationException validation(List<FieldError> fieldErrors) {
        return new RegistrationException(400, "VALIDATION_FAILED", "Please provide valid registration details.",
                fieldErrors, null);
    }

    public static RegistrationException invalidRequest(String message) {
        return new RegistrationException(400, "INVALID_REQUEST", message);
    }

    // ---- 401 / 403 ---------------------------------------------------------------------------------------------

    /** One generic answer for every authentication failure; the specific reason is only logged. */
    public static RegistrationException unauthorized() {
        return new RegistrationException(401, "UNAUTHORIZED", "Authentication is required.");
    }

    public static RegistrationException emailNotVerified() {
        return new RegistrationException(403, "EMAIL_NOT_VERIFIED", "A verified Google email is required.");
    }

    public static RegistrationException googleAccountRequired() {
        return new RegistrationException(403, "GOOGLE_ACCOUNT_REQUIRED",
                "Please use Continue with Google to sign in as a student.");
    }

    public static RegistrationException accountSuspended() {
        return new RegistrationException(403, "ACCOUNT_SUSPENDED", "Your account is currently suspended.");
    }

    /** Soft-deleted account. Same answer on sign-in and registration; never recreated or reactivated here. */
    public static RegistrationException accountDeleted() {
        return new RegistrationException(403, "ACCOUNT_DELETED", "This account is unavailable.");
    }

    /** The identity belongs to a recruiter or manager, not a student. */
    public static RegistrationException accountTypeMismatch() {
        return new RegistrationException(403, "ACCOUNT_TYPE_MISMATCH",
                "This sign-in belongs to an account that is not a student account.");
    }

    // Recruiter-portal wording. The codes are the same as for students; only the messages differ, so a student never
    // sees "recruiter" text and the recruiter portal never says "student".

    public static RegistrationException recruiterGoogleAccountRequired() {
        return new RegistrationException(403, "GOOGLE_ACCOUNT_REQUIRED",
                "Please use Continue with Google to sign in as a recruiter.");
    }

    public static RegistrationException recruiterAccountSuspended() {
        return new RegistrationException(403, "ACCOUNT_SUSPENDED", "Your recruiter account has been suspended.");
    }

    /** The Google identity belongs to a student, manager or role-less user rather than a recruiter. */
    public static RegistrationException recruiterTypeMismatch() {
        return new RegistrationException(403, "ACCOUNT_TYPE_MISMATCH",
                "This Google account is already associated with another HireMeBot account type.");
    }

    public static RegistrationException recruiterIdentityConflict() {
        return new RegistrationException(409, "ACCOUNT_IDENTITY_CONFLICT",
                "This email is already associated with another HireMeBot account.");
    }

    // ---- 404 / 405 / 409 / 413 / 415 ---------------------------------------------------------------------------

    public static RegistrationException notFound() {
        return new RegistrationException(404, "NOT_FOUND", "Route not found.");
    }

    public static RegistrationException methodNotAllowed(String allowedMethod) {
        return new RegistrationException(405, "METHOD_NOT_ALLOWED", "Method not allowed; use " + allowedMethod + ".");
    }

    public static RegistrationException alreadyRegistered() {
        return new RegistrationException(409, "ACCOUNT_ALREADY_REGISTERED",
                "An account already exists. Please sign in.");
    }

    public static RegistrationException identityConflict() {
        return new RegistrationException(409, "ACCOUNT_IDENTITY_CONFLICT",
                "An existing account requires a different sign-in or account recovery method.");
    }

    /** The {@code UNIQUE (first_name, last_name)} index rejected the insert. Reveals nothing about the other user. */
    public static RegistrationException nameAlreadyRegistered() {
        return new RegistrationException(409, "NAME_ALREADY_REGISTERED",
                "An account with this name already exists. Please contact support.");
    }

    public static RegistrationException payloadTooLarge() {
        return new RegistrationException(413, "PAYLOAD_TOO_LARGE", "The request body is too large.");
    }

    public static RegistrationException unsupportedMediaType() {
        return new RegistrationException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json.");
    }

    // ---- 5xx ---------------------------------------------------------------------------------------------------

    public static RegistrationException serviceUnavailable(Throwable cause) {
        return new RegistrationException(503, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable. Please try again later.", List.of(), cause);
    }

    public static RegistrationException internal(Throwable cause) {
        return new RegistrationException(500, "INTERNAL_ERROR", "An unexpected error occurred.", List.of(), cause);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
