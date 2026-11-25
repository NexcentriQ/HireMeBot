package org.hmb.dto;

import java.util.Set;

/**
 * The registration body as sent by the client, before validation. Only these four fields are read.
 *
 * <p>Identity is never taken from here: there is deliberately no email, Cognito subject, user id, verification flag or
 * account status. Any other property the client sends (for example a mock OTP result) is ignored and can never
 * influence the stored record; only its <em>name</em> is kept in {@code ignoredFields}, for logging.
 */
public record StudentRegistrationRequest(String firstName, String lastName, String phoneNumber, Boolean acceptedTerms,
                                         Set<String> ignoredFields) {

    public StudentRegistrationRequest {
        ignoredFields = ignoredFields == null ? Set.of() : Set.copyOf(ignoredFields);
    }

    public StudentRegistrationRequest(String firstName, String lastName, String phoneNumber, Boolean acceptedTerms) {
        this(firstName, lastName, phoneNumber, acceptedTerms, Set.of());
    }

    /** Redacted: names and phone numbers are personal data and must not reach the logs through toString. */
    @Override
    public String toString() {
        return "StudentRegistrationRequest[redacted, ignoredFields=" + ignoredFields + "]";
    }
}
