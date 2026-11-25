package org.hmb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hmb.exception.RegistrationException;

import java.util.List;

/**
 * The single response envelope of the registration API.
 *
 * <pre>
 * success: {"success":true,"code":"STUDENT_REGISTERED","message":"...","data":{...}}
 * error:   {"success":false,"code":"VALIDATION_FAILED","message":"...","fieldErrors":[...],"requestId":"..."}
 * </pre>
 *
 * Empty fields are omitted. {@code requestId} is present on every error so support can correlate it with the logs.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiResponse(boolean success, String code, String message, Object data, List<FieldError> fieldErrors,
                          String requestId) {

    public static ApiResponse registered(StudentRegistrationData data) {
        return new ApiResponse(true, "STUDENT_REGISTERED", "Student registered successfully", data, List.of(), null);
    }

    /** 201 for a recruiter registration: it is only <em>submitted</em>, the account waits for manual approval. */
    public static ApiResponse recruiterRegistered(RecruiterRegistrationData data) {
        return new ApiResponse(true, "RECRUITER_REGISTRATION_SUBMITTED",
                "Your recruiter registration is pending approval.", data, List.of(), null);
    }

    public static ApiResponse error(RegistrationException e, String requestId) {
        return new ApiResponse(false, e.code(), e.getMessage(), null, e.fieldErrors(), requestId);
    }
}
