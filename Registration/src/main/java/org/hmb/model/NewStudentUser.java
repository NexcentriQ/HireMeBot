package org.hmb.model;

import java.time.Instant;

/**
 * The {@code users} row to insert for a new student. Every value is already validated and normalized; the email is
 * the verified Cognito email, the status comes from the account policy and the terms version from configuration.
 * {@code email_verified = TRUE} and {@code phone_verified = FALSE} are fixed in the SQL, not carried here.
 *
 * <p>Despite the name this is the {@code users} row for <b>every</b> role (students and recruiters); only the role
 * row ({@code students} / {@code recruiters}) differs. It is not renamed to keep the student code and tests unchanged.
 */
public record NewStudentUser(String firstName, String lastName, String email, String phone, AccountStatus status,
                             Instant termsAcceptedAt, String termsVersion) {

    @Override
    public String toString() {
        return "NewStudentUser[status=" + status + ", termsVersion=" + termsVersion + ", redacted]";
    }
}
