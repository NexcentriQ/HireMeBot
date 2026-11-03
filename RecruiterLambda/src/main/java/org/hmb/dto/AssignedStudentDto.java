package org.hmb.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One student assigned to the requesting recruiter, as shown in the Apply Job form's student dropdown.
 *
 * <p>Deliberately minimal: no phone, verification flags, deletion flag or manager details.
 *
 * <p>{@code assignedAt} is an ISO-8601 UTC instant ending in {@code Z}, e.g. {@code 2026-10-20T14:30:00Z}. That is
 * accurate: {@code recruiter_student_assignments.assigned_at} is a TIMESTAMP filled by {@code CURRENT_TIMESTAMP},
 * and every pooled connection runs with its session time zone forced to UTC (see DatabaseConfig), so the value is
 * read back as UTC. The frontend can localize it.
 *
 * @param fullName the trimmed first and last name joined with a single space
 */
public record AssignedStudentDto(
        long studentId,
        String firstName,
        String lastName,
        String fullName,
        String email,
        String assignedAt) {

    /**
     * Builds a DTO from database values, trimming the names and deriving {@code fullName}.
     *
     * @param assignedAtUtc the stored UTC wall-clock value, or null if the column is empty
     */
    public static AssignedStudentDto of(long studentId, String firstName, String lastName, String email,
                                        LocalDateTime assignedAtUtc) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String fullName = (first + " " + last).trim();
        return new AssignedStudentDto(studentId, first, last, fullName, email, formatUtc(assignedAtUtc));
    }

    /** Formats a UTC wall-clock value as e.g. {@code 2026-10-20T14:30:00Z}; null stays null. */
    static String formatUtc(LocalDateTime utcDateTime) {
        return utcDateTime == null ? null : DateTimeFormatter.ISO_INSTANT.format(utcDateTime.toInstant(ZoneOffset.UTC));
    }
}
