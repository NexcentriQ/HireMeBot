package org.hmb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.NextStep;

/**
 * Successful answer of {@code GET /api/v1/auth/me}: always HTTP 200 with {@code success: true}; the frontend navigates
 * on {@link #nextStep}. Restricted accounts and conflicts are errors (the {@link ApiResponse} error shape), not this.
 *
 * <pre>
 * registered: {"success":true,"registered":true,"nextStep":"DASHBOARD","user":{...}}
 * pending:    {"success":true,"registered":true,"nextStep":"ACCOUNT_PENDING","user":{...}}
 * new:        {"success":true,"registered":false,"nextStep":"SIGNUP_FORM","message":"..."}
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountLookupResponse(boolean success, boolean registered, NextStep nextStep, String message,
                                    User user) {

    public static final String SIGNUP_MESSAGE = "Complete your HireMeBot student registration.";

    /** The caller's own account. Contains no token, no Cognito subject and no internal-only columns. */
    public record User(long userId, long studentId, String firstName, String lastName, String email,
                       String phoneNumber, boolean emailVerified, boolean phoneVerified, AccountStatus accountStatus,
                       String accountType) {

        @Override
        public String toString() {
            return "User[userId=" + userId + ", accountStatus=" + accountStatus + ", redacted]";
        }
    }

    public static AccountLookupResponse registered(NextStep nextStep, AccountProfile profile) {
        return new AccountLookupResponse(true, true, nextStep, null, new User(profile.userId(), profile.userId(),
                profile.firstName(), profile.lastName(), profile.email(), profile.phone(), profile.emailVerified(),
                profile.phoneVerified(), profile.status(), StudentRegistrationData.ACCOUNT_TYPE_STUDENT));
    }

    public static AccountLookupResponse signupRequired() {
        return new AccountLookupResponse(true, false, NextStep.SIGNUP_FORM, SIGNUP_MESSAGE, null);
    }
}
