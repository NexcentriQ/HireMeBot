package org.hmb.service;

import org.hmb.dto.FieldError;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.ValidatedRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationValidatorTest {

    private final RegistrationValidator validator = new RegistrationValidator();

    @Test
    void normalizesAValidRequest() {
        ValidatedRegistration valid = validator.validate(
                new StudentRegistrationRequest("  Alex  ", " Smith ", "+1 (713) 555-0101", true));

        assertEquals("Alex", valid.firstName());
        assertEquals("Smith", valid.lastName());
        assertEquals("+17135550101", valid.phoneE164());
    }

    @Test
    void supportsInternationalNames() {
        for (String name : List.of("José", "Zoë", "O'Brien", "Mary-Jane", "Jean Paul", "St. John", "李", "Nguyễn",
                "Øyvind", "Đặng", "ʻIolani", "D’Angelo", "Ελένη", "Алексей")) {
            List<FieldError> errors = new ArrayList<>();
            assertEquals(name, RegistrationValidator.name("firstName", "First name", name, errors), name);
            assertTrue(errors.isEmpty(), name);
        }
        // Decomposed é (e + combining acute) is stored composed (NFC); runs of spaces collapse.
        assertEquals("José María", RegistrationValidator.name("f", "F", "José   María ",
                new ArrayList<>()));
    }

    @Test
    void missingOrBlankNamesAreRequired() {
        assertFieldError(new StudentRegistrationRequest(null, "Smith", "+17135550101", true), "firstName");
        assertFieldError(new StudentRegistrationRequest("   ", "Smith", "+17135550101", true), "firstName");
        assertFieldError(new StudentRegistrationRequest("Alex", null, "+17135550101", true), "lastName");
        assertFieldError(new StudentRegistrationRequest("Alex", "", "+17135550101", true), "lastName");
    }

    @Test
    void namesAreLimitedTo100Characters() {
        assertEquals("a".repeat(100), RegistrationValidator.name("f", "F", "a".repeat(100), new ArrayList<>()));
        assertFieldError(new StudentRegistrationRequest("a".repeat(101), "Smith", "+17135550101", true), "firstName");
        // 100 characters outside the BMP (2 UTF-16 units each) still count as 100.
        String supplementary100 = new String(Character.toChars(0x20000)).repeat(100); // CJK Ext. B letter
        assertEquals(200, supplementary100.length());
        assertEquals(supplementary100, RegistrationValidator.name("f", "F", supplementary100, new ArrayList<>()));
        assertFieldError(new StudentRegistrationRequest(supplementary100 + "a", "Smith", "+17135550101", true),
                "firstName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alex1", "<script>", "Alex😀", "-Alex", "'Alex", "Al\u0000ex", "Robert); DROP TABLE users;--"})
    void rejectsUnsupportedNameCharacters(String name) {
        assertFieldError(new StudentRegistrationRequest(name, "Smith", "+17135550101", true), "firstName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+17135550101", "+1 713 555 0101", "+1-713-555-0101", "+1.713.555.0101", "+447911123456",
            "+919876543210", "+61412345678", "+2901234"})
    void acceptsE164PhoneNumbers(String phone) {
        List<FieldError> errors = new ArrayList<>();
        String normalized = RegistrationValidator.phone(phone, errors);
        assertTrue(errors.isEmpty(), phone);
        assertTrue(normalized.matches("\\+\\d{7,15}") && normalized.length() <= 16, phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7135550101", "(713) 555-0101", "+0123456789", "+1713555010", "+117135550101",
            "+10135550101", "+17131550101", "+1-800-FLOWERS", "+12", "++17135550101", "+1 713 555 0101 ext 2", "abc"})
    void rejectsInvalidPhoneNumbers(String phone) {
        assertFieldError(new StudentRegistrationRequest("Alex", "Smith", phone, true), "phoneNumber");
    }

    @Test
    void rejectsPhoneNumbersThatExceedTheDatabaseLimit() {
        // 16 digits = 17 characters: longer than E.164 allows and than users.phone VARCHAR(16).
        List<FieldError> errors = new ArrayList<>();
        assertNull(RegistrationValidator.phone("+1234567890123456", errors));
        assertEquals("Phone number must have at most 15 digits.", errors.get(0).message());
        assertFieldError(new StudentRegistrationRequest("Alex", "Smith", "+" + "4".repeat(40), true), "phoneNumber");
        // The longest valid value fits exactly.
        assertEquals(16, RegistrationValidator.phone("+447911123456789", new ArrayList<>()).length());
    }

    @Test
    void missingPhoneIsRequired() {
        assertFieldError(new StudentRegistrationRequest("Alex", "Smith", null, true), "phoneNumber");
        assertFieldError(new StudentRegistrationRequest("Alex", "Smith", "  ", true), "phoneNumber");
    }

    @Test
    void termsMustBeAccepted() {
        assertFieldError(new StudentRegistrationRequest("Alex", "Smith", "+17135550101", false), "acceptedTerms");
        assertFieldError(new StudentRegistrationRequest("Alex", "Smith", "+17135550101", null), "acceptedTerms");
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> validator.validate(new StudentRegistrationRequest(null, null, null, null)));
        assertEquals(400, e.status());
        assertEquals("VALIDATION_FAILED", e.code());
        assertEquals("Please provide valid registration details.", e.getMessage());
        assertEquals(List.of("firstName", "lastName", "phoneNumber", "acceptedTerms"),
                e.fieldErrors().stream().map(FieldError::field).toList());
    }

    private void assertFieldError(StudentRegistrationRequest request, String field) {
        RegistrationException e = assertThrows(RegistrationException.class, () -> validator.validate(request));
        assertEquals("VALIDATION_FAILED", e.code());
        assertEquals(List.of(field), e.fieldErrors().stream().map(FieldError::field).toList());
    }
}
