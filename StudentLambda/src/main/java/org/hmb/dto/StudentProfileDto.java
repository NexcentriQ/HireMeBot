package org.hmb.dto;

/**
 * The signed-in student's profile, for the dashboard greeting and the Profile page.
 *
 * <p>Deliberately minimal: no id (already in {@code data.studentId}), no deletion flag, no email-verification flag,
 * and nothing secret.
 *
 * <p><b>{@code phoneE164}</b> is the {@code users.phone} value exactly as stored (surrounding whitespace removed),
 * or {@code null} when the column is empty. The field name is kept for the frontend contract, but the API does
 * <em>not</em> validate or reformat the number: the database enforces no format, so a stored number such as
 * {@code 7135550123} or {@code (713) 555-0123} is returned as it is, and the value is E.164 only if it was stored that
 * way. The field is always present in the JSON, even when null.
 *
 * <p><b>{@code phoneVerified}</b> is the {@code users.phone_verified} column as stored. It is never inferred from the
 * presence of a number.
 */
public record StudentProfileDto(
        String firstName,
        String lastName,
        String email,
        String phoneE164,
        boolean phoneVerified) {

    /**
     * Builds a profile from database values. Names are trimmed; the phone is passed through as stored.
     *
     * @param storedPhone the raw {@code users.phone} value; may be null or blank
     */
    public static StudentProfileDto of(String firstName, String lastName, String email, String storedPhone,
                                       boolean phoneVerified) {
        return new StudentProfileDto(
                firstName == null ? null : firstName.trim(),
                lastName == null ? null : lastName.trim(),
                email,
                availablePhone(storedPhone),
                phoneVerified);
    }

    /**
     * @return the stored phone with surrounding whitespace removed, or {@code null} if there is nothing stored.
     *         The number itself is never modified.
     */
    static String availablePhone(String storedPhone) {
        if (storedPhone == null) {
            return null;
        }
        String trimmed = storedPhone.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
