package org.hmb.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StudentProfileDtoTest {

    private static String phoneOf(String storedPhone) {
        return StudentProfileDto.of("A", "B", "a@example.test", storedPhone, false).phoneE164();
    }

    /** Whatever is stored is returned exactly as stored, in any format. Nothing is reformatted or prefixed. */
    @ParameterizedTest
    @ValueSource(strings = {
            "+17135550123",           // E.164
            "+919876543210",          // E.164, non-US
            "7135550123",             // national number: NOT given a +1
            "9123456789",             // plain 10 digits, as stored by manual onboarding
            "17135550123",
            "(713) 555-0123",
            "713-555-0123",
            "+1 713 555 0123",
            "00442071838750",
            "ext. 5"})
    void aStoredPhoneIsReturnedExactlyAsStored(String phone) {
        assertEquals(phone, phoneOf(phone));
    }

    @Test
    void surroundingWhitespaceIsTrimmedButTheNumberIsOtherwiseUntouched() {
        assertEquals("+17135550123", phoneOf("  +17135550123 "));
        assertEquals("(713) 555-0123", phoneOf(" (713) 555-0123"));
    }

    /** Nothing stored means nothing to return. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void anEmptyPhoneIsNull(String phone) {
        assertNull(phoneOf(phone));
    }

    @Test
    void phoneVerifiedIsReportedAsGivenAndIsIndependentOfThePhone() {
        assertEquals(true, StudentProfileDto.of("A", "B", "e", "+17135550123", true).phoneVerified());
        assertEquals(false, StudentProfileDto.of("A", "B", "e", "+17135550123", false).phoneVerified());
        // The flag is reported as stored even when no number is available...
        assertEquals(true, StudentProfileDto.of("A", "B", "e", null, true).phoneVerified());
        // ...and an unverified flag is never upgraded because a number exists.
        assertEquals(false, StudentProfileDto.of("A", "B", "e", "7135550123", false).phoneVerified());
    }

    @Test
    void namesAreTrimmedAndEmailIsKept() {
        StudentProfileDto dto = StudentProfileDto.of("  John ", " Smith  ", "john.smith@example.com", null, false);

        assertEquals("John", dto.firstName());
        assertEquals("Smith", dto.lastName());
        assertEquals("john.smith@example.com", dto.email());
    }
}
