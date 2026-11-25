package org.hmb.model;

/** Registration input after validation: trimmed, Unicode-normalized names and an E.164 phone number (max 16 chars). */
public record ValidatedRegistration(String firstName, String lastName, String phoneE164) {

    @Override
    public String toString() {
        return "ValidatedRegistration[redacted]";
    }
}
