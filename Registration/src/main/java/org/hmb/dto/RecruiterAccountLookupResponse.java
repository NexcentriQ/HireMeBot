package org.hmb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.RecruiterNextStep;

/**
 * Successful answer of {@code GET /api/v1/recruiters/auth/me}: always HTTP 200 with {@code success: true}; the recruiter
 * portal navigates on {@link #nextStep}. Restricted accounts and conflicts are errors (the {@link ApiResponse} error
 * shape), not this.
 *
 * <pre>
 * active:  {"success":true,"registered":true,"nextStep":"RECRUITER_DASHBOARD","user":{ full profile }}
 * pending: {"success":true,"registered":true,"nextStep":"APPROVAL_PENDING","message":"...","user":{userId,recruiterId,accountType,accountStatus}}
 * new:     {"success":true,"registered":false,"nextStep":"RECRUITER_SIGNUP_FORM","message":"..."}
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecruiterAccountLookupResponse(boolean success, boolean registered, RecruiterNextStep nextStep,
                                             String message, User user) {

    public static final String ACCOUNT_TYPE_RECRUITER = "RECRUITER";
    public static final String SIGNUP_MESSAGE = "Complete your HireMeBot recruiter registration.";
    public static final String PENDING_MESSAGE = "Your recruiter account is waiting for approval.";

    /**
     * The caller's own account. A pending recruiter only gets the four identifying fields (the other attributes are
     * {@code null} and omitted from the JSON; the annotation is needed here too, it does not reach a nested type from
     * the outer record). Contains no token, no Cognito subject and no manager id.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record User(Long userId, Long recruiterId, String firstName, String lastName, String email,
                       String phoneNumber, Boolean emailVerified, Boolean phoneVerified, AccountStatus accountStatus,
                       String accountType) {

        @Override
        public String toString() {
            return "User[userId=" + userId + ", accountStatus=" + accountStatus + ", redacted]";
        }
    }

    /** An approved ({@code ACTIVE}) recruiter: the full profile. */
    public static RecruiterAccountLookupResponse dashboard(AccountProfile profile) {
        return new RecruiterAccountLookupResponse(true, true, RecruiterNextStep.RECRUITER_DASHBOARD, null,
                new User(profile.userId(), profile.userId(), profile.firstName(), profile.lastName(), profile.email(),
                        profile.phone(), profile.emailVerified(), profile.phoneVerified(), profile.status(),
                        ACCOUNT_TYPE_RECRUITER));
    }

    /** A recruiter waiting for manual approval: identifiers and status only. */
    public static RecruiterAccountLookupResponse pending(AccountProfile profile) {
        return new RecruiterAccountLookupResponse(true, true, RecruiterNextStep.APPROVAL_PENDING, PENDING_MESSAGE,
                new User(profile.userId(), profile.userId(), null, null, null, null, null, null, profile.status(),
                        ACCOUNT_TYPE_RECRUITER));
    }

    public static RecruiterAccountLookupResponse signupRequired() {
        return new RecruiterAccountLookupResponse(true, false, RecruiterNextStep.RECRUITER_SIGNUP_FORM, SIGNUP_MESSAGE,
                null);
    }
}
