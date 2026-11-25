package org.hmb.dto;

import org.hmb.model.AccountStatus;
import org.hmb.model.NextStep;

/**
 * The {@code data} of a successful registration. Every value reflects what was actually stored: the generated id,
 * the email taken from the verified Cognito identity, and the account status chosen by the server-side account policy
 * with its {@link NextStep} (the same rules as {@code GET /api/v1/auth/me}).
 */
public record StudentRegistrationData(
        long userId,
        long studentId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        boolean emailVerified,
        boolean phoneVerified,
        AccountStatus accountStatus,
        String accountType,
        NextStep nextStep) {

    public static final String ACCOUNT_TYPE_STUDENT = "STUDENT";

    @Override
    public String toString() {
        return "StudentRegistrationData[userId=" + userId + ", accountStatus=" + accountStatus + ", redacted]";
    }
}
