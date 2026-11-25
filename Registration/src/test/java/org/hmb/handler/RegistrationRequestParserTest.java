package org.hmb.handler;

import org.hmb.dto.FieldError;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationRequestParserTest {

    @Test
    void parsesTheFourFields() {
        StudentRegistrationRequest r = RegistrationRequestParser.parse(
                "{\"firstName\":\"Alex\",\"lastName\":\"Smith\",\"phoneNumber\":\"+17135550101\",\"acceptedTerms\":true}");

        assertEquals("Alex", r.firstName());
        assertEquals("Smith", r.lastName());
        assertEquals("+17135550101", r.phoneNumber());
        assertTrue(r.acceptedTerms());
        assertTrue(r.ignoredFields().isEmpty());
    }

    @Test
    void identityAndVerificationClaimsFromTheClientAreIgnored() {
        StudentRegistrationRequest r = RegistrationRequestParser.parse("""
                {"firstName":"Alex","lastName":"Smith","phoneNumber":"+17135550101","acceptedTerms":true,
                 "email":"attacker@example.com","cognitoSub":"forged","userId":1,"emailVerified":true,
                 "phoneVerified":true,"otpVerified":true,"otp":"123456","accountStatus":"ACTIVE",
                 "termsVersion":"0.0"}""");

        assertEquals(Set.of("email", "cognitoSub", "userId", "emailVerified", "phoneVerified", "otpVerified", "otp",
                "accountStatus", "termsVersion"), r.ignoredFields());
        // The request type has no place to carry any of them.
        assertFalse(r.toString().contains("attacker"));
    }

    @Test
    void missingFieldsAreNullForTheValidator() {
        StudentRegistrationRequest r = RegistrationRequestParser.parse("{\"firstName\":null}");
        assertNull(r.firstName());
        assertNull(r.acceptedTerms());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{", "[]", "[{\"firstName\":\"Alex\"}]", "\"text\"", "42", "null",
            "{\"firstName\":\"A\"} {\"x\":1}", "{\"firstName\":\"A\",\"firstName\":\"B\"}"})
    void malformedOrNonObjectBodiesAreInvalidRequests(String body) {
        RegistrationException e = assertThrows(RegistrationException.class, () -> RegistrationRequestParser.parse(body));
        assertEquals(400, e.status());
        assertEquals("INVALID_REQUEST", e.code());
    }

    @Test
    void wrongTypesAreValidationErrorsWithoutCoercion() {
        RegistrationException e = assertThrows(RegistrationException.class, () -> RegistrationRequestParser.parse(
                "{\"firstName\":123,\"lastName\":[\"Smith\"],\"phoneNumber\":17135550101,\"acceptedTerms\":\"true\"}"));

        assertEquals("VALIDATION_FAILED", e.code());
        assertEquals(List.of("firstName", "lastName", "phoneNumber", "acceptedTerms"),
                e.fieldErrors().stream().map(FieldError::field).toList());
    }

    @Test
    void acceptedTermsMustBeABooleanNotANumber() {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> RegistrationRequestParser.parse("{\"acceptedTerms\":1}"));
        assertEquals("acceptedTerms", e.fieldErrors().get(0).field());
    }

    @Test
    void ignoredFieldNamesAreBoundedAndSanitizedForLogs() {
        StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < 50; i++) {
            body.append("\"x").append(i).append("\":1,");
        }
        body.append("\"bad\\nname\":1}");
        StudentRegistrationRequest r = RegistrationRequestParser.parse(body.toString());
        assertEquals(10, r.ignoredFields().size());
        assertTrue(r.ignoredFields().stream().noneMatch(n -> n.contains("\n")));
    }
}
