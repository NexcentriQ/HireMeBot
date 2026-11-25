package org.hmb.dto;

import org.hmb.model.AccountStatus;
import org.hmb.model.RecruiterNextStep;

/**
 * The {@code data} of a submitted recruiter registration. Every value reflects what was actually stored: the generated
 * id, the email taken from the verified Cognito identity, and the {@code PENDING} status every new recruiter gets.
 * {@code nextStep} is always {@link RecruiterNextStep#APPROVAL_PENDING}, never a dashboard step.
 */
public record RecruiterRegistrationData(
        long userId,
        long recruiterId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        boolean emailVerified,
        boolean phoneVerified,
        AccountStatus accountStatus,
        String accountType,
        RecruiterNextStep nextStep) {

    public static final String ACCOUNT_TYPE_RECRUITER = "RECRUITER";

    @Override
    public String toString() {
        return "RecruiterRegistrationData[userId=" + userId + ", accountStatus=" + accountStatus + ", redacted]";
    }
}
