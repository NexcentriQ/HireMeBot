package org.hmb.dto;

/** One invalid request field: {@code field} is the JSON property name, {@code message} is safe to show to the user. */
public record FieldError(String field, String message) {
}
