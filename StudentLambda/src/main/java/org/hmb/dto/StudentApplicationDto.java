package org.hmb.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One application as returned to the student.
 *
 * <p>{@code appliedAt} is an ISO-8601 instant ending in {@code Z}. That is accurate because
 * {@code applications.applied_at} is a DATETIME filled by {@code CURRENT_TIMESTAMP} on a connection whose session
 * time zone is forced to UTC (see DatabaseConfig), so the stored wall-clock value is UTC.
 */
public record StudentApplicationDto(
        long applicationId,
        String jobTitle,
        String companyName,
        String jobLocation,
        String jobLink,
        String jobSource,
        String appliedAt) {

    /** Formats a UTC wall-clock DATETIME as e.g. {@code 2026-10-25T15:30:00Z}. */
    public static String formatUtc(LocalDateTime utcDateTime) {
        return DateTimeFormatter.ISO_INSTANT.format(utcDateTime.toInstant(ZoneOffset.UTC));
    }
}
