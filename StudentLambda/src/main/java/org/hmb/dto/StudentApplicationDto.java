package org.hmb.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One application as returned to the student.
 *
 * <p>{@code appliedAt} is an ISO-8601 timestamp with the reporting zone's offset, e.g.
 * {@code 2026-10-29T10:30:00-05:00}. {@code applications.applied_at} is a DATETIME filled by
 * {@code CURRENT_TIMESTAMP} on a connection whose session time zone is forced to UTC (see DatabaseConfig), so the
 * stored value is a UTC wall-clock time; it is converted to the reporting zone for display. The instant is
 * preserved; only the rendering changes.
 */
public record StudentApplicationDto(
        long applicationId,
        String jobTitle,
        String companyName,
        String jobLocation,
        String jobLink,
        String jobSource,
        String appliedAt) {

    // Always prints seconds and a numeric offset (e.g. -05:00); the stored DATETIME has no fractional seconds.
    private static final DateTimeFormatter OFFSET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * Renders a stored UTC DATETIME in the reporting zone with its offset, e.g. {@code 2026-10-29T10:30:00-05:00}.
     *
     * @param utcDateTime the value read from {@code applied_at} (UTC wall-clock)
     * @param zone        the reporting zone to render it in
     */
    public static String formatAppliedAt(LocalDateTime utcDateTime, ZoneId zone) {
        return utcDateTime.toInstant(ZoneOffset.UTC).atZone(zone).format(OFFSET_FORMAT);
    }
}
