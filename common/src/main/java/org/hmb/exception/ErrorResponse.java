package org.hmb.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** JSON error body returned for every non-2xx response. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(boolean success, String error, String message, List<String> details) {

    public static ErrorResponse from(ApiException e) {
        return new ErrorResponse(false, e.getErrorCode(), e.getMessage(), e.getDetails());
    }
}
