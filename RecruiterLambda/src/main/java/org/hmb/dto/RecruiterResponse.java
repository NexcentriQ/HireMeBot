package org.hmb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Success body for {@code POST /api/v1/recruiter/applications}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecruiterResponse(boolean success, String message, Long applicationId) {

    public static RecruiterResponse created(long applicationId) {
        return new RecruiterResponse(true, "Application recorded successfully", applicationId);
    }
}
