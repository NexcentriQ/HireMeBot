package org.hmb.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.hmb.dto.FieldError;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the JSON body into a {@link StudentRegistrationRequest} with strict typing:
 * <ul>
 *   <li>the body must be one JSON object (no array, scalar, trailing content or duplicate keys) &rarr; otherwise 400
 *       {@code INVALID_REQUEST};</li>
 *   <li>{@code firstName}, {@code lastName}, {@code phoneNumber} must be strings and {@code acceptedTerms} a boolean
 *       when present (no {@code "true"} string or number coercion) &rarr; otherwise 400 {@code VALIDATION_FAILED};</li>
 *   <li>any other property is <b>ignored</b> and cannot influence what is stored; this includes identity or
 *       verification claims such as {@code email}, {@code cognitoSub}, {@code userId}, {@code phoneVerified} or a mock
 *       OTP result. Only the property names are kept, for logging.</li>
 * </ul>
 */
final class RegistrationRequestParser {

    static final Set<String> KNOWN_FIELDS = Set.of("firstName", "lastName", "phoneNumber", "acceptedTerms");
    /** Bounded so a hostile body cannot fill the logs with property names. */
    private static final int MAX_REPORTED_IGNORED_FIELDS = 10;
    private static final int MAX_REPORTED_NAME_LENGTH = 40;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private RegistrationRequestParser() {
    }

    static StudentRegistrationRequest parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw RegistrationException.invalidRequest("The request body is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw RegistrationException.invalidRequest("The request body must be a JSON object.");
        }

        List<FieldError> typeErrors = new ArrayList<>();
        String firstName = string(root, "firstName", typeErrors);
        String lastName = string(root, "lastName", typeErrors);
        String phoneNumber = string(root, "phoneNumber", typeErrors);
        Boolean acceptedTerms = bool(root, "acceptedTerms", typeErrors);
        if (!typeErrors.isEmpty()) {
            throw RegistrationException.validation(typeErrors);
        }
        return new StudentRegistrationRequest(firstName, lastName, phoneNumber, acceptedTerms, ignoredFields(root));
    }

    private static String string(JsonNode root, String field, List<FieldError> errors) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(new FieldError(field, field + " must be a string."));
            return null;
        }
        return node.textValue();
    }

    private static Boolean bool(JsonNode root, String field, List<FieldError> errors) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            errors.add(new FieldError(field, field + " must be the boolean true."));
            return null;
        }
        return node.booleanValue();
    }

    private static Set<String> ignoredFields(JsonNode root) {
        Set<String> ignored = new LinkedHashSet<>();
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!KNOWN_FIELDS.contains(name) && ignored.size() < MAX_REPORTED_IGNORED_FIELDS) {
                String safe = name.replaceAll("[^A-Za-z0-9_.-]", "?");
                ignored.add(safe.length() > MAX_REPORTED_NAME_LENGTH ? safe.substring(0, MAX_REPORTED_NAME_LENGTH)
                        : safe);
            }
        }
        return ignored;
    }
}
