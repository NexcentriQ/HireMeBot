package org.hmb.service;

import org.hmb.dto.FieldError;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.ValidatedRegistration;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates and normalizes the registration body. All problems are reported together as field errors (400
 * {@code VALIDATION_FAILED}); nothing is written unless every field is valid.
 *
 * <ul>
 *   <li><b>Names</b>: required; trimmed, Unicode NFC-normalized, inner whitespace collapsed; at most 100 characters
 *       ({@code VARCHAR(100)} counts characters); letters of any script, combining marks, spaces, apostrophes,
 *       hyphens and periods, starting with a letter.</li>
 *   <li><b>Phone</b>: required; spaces, dashes, dots and parentheses are removed, then it must be E.164
 *       ({@code +} country code and subscriber number, 7-15 digits), so it always fits {@code VARCHAR(16)}. North
 *       American (+1) numbers must have 10 digits with valid area code and exchange. It is never marked verified.</li>
 *   <li><b>acceptedTerms</b>: must be the JSON boolean {@code true}.</li>
 * </ul>
 */
public class RegistrationValidator {

    static final int MAX_NAME_LENGTH = 100;
    /** {@code users.phone VARCHAR(16)}: "+" and at most 15 digits (the E.164 maximum). */
    static final int MAX_PHONE_LENGTH = 16;
    /** Raw input bound before normalization (formatting characters included). */
    static final int MAX_RAW_PHONE_LENGTH = 32;

    private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L}\\p{M}'’ .\\-]*");
    /** ASCII whitespace and every Unicode space separator (e.g. a pasted non-breaking space). */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern PHONE_FORMATTING = Pattern.compile("[\\s\\p{Z}().\\-]");
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");
    private static final Pattern NANP = Pattern.compile("\\+1[2-9]\\d{2}[2-9]\\d{6}");

    public ValidatedRegistration validate(StudentRegistrationRequest request) {
        List<FieldError> errors = new ArrayList<>();
        String firstName = name("firstName", "First name", request.firstName(), errors);
        String lastName = name("lastName", "Last name", request.lastName(), errors);
        String phone = phone(request.phoneNumber(), errors);
        if (!Boolean.TRUE.equals(request.acceptedTerms())) {
            errors.add(new FieldError("acceptedTerms", "You must accept the Terms to register."));
        }
        if (!errors.isEmpty()) {
            throw RegistrationException.validation(errors);
        }
        return new ValidatedRegistration(firstName, lastName, phone);
    }

    static String name(String field, String label, String raw, List<FieldError> errors) {
        if (raw == null) {
            errors.add(new FieldError(field, label + " is required."));
            return null;
        }
        String value = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll(" ").strip();
        if (value.isEmpty()) {
            errors.add(new FieldError(field, label + " is required."));
            return null;
        }
        if (value.codePointCount(0, value.length()) > MAX_NAME_LENGTH) {
            errors.add(new FieldError(field, label + " must be at most " + MAX_NAME_LENGTH + " characters."));
            return null;
        }
        if (!NAME.matcher(value).matches()) {
            errors.add(new FieldError(field, label + " may contain only letters, spaces, apostrophes, hyphens "
                    + "and periods, and must start with a letter."));
            return null;
        }
        return value;
    }

    static String phone(String raw, List<FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(new FieldError("phoneNumber", "Phone number is required."));
            return null;
        }
        if (raw.length() > MAX_RAW_PHONE_LENGTH) {
            errors.add(new FieldError("phoneNumber", "Phone number is too long."));
            return null;
        }
        String value = PHONE_FORMATTING.matcher(raw.strip()).replaceAll("");
        if (!value.startsWith("+")) {
            errors.add(new FieldError("phoneNumber",
                    "Phone number must include the country code in international format, e.g. +17135550101."));
            return null;
        }
        if (value.length() > MAX_PHONE_LENGTH && value.substring(1).chars().allMatch(Character::isDigit)) {
            errors.add(new FieldError("phoneNumber", "Phone number must have at most 15 digits."));
            return null;
        }
        if (!E164.matcher(value).matches() || (value.startsWith("+1") && !NANP.matcher(value).matches())) {
            errors.add(new FieldError("phoneNumber",
                    "Enter a valid phone number in international format, e.g. +17135550101."));
            return null;
        }
        return value;
    }
}
