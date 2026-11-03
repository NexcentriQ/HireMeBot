package org.hmb.dto;

/**
 * Application progress for the current day.
 *
 * @param date       the day in {@code YYYY-MM-DD} form
 * @param applied    applications recorded today (may exceed {@code target}; counted on weekends too)
 * @param target     the daily target (default 50)
 * @param remaining  {@code max(target - applied, 0)}
 * @param percentage {@code round(applied / target * 100)}, not capped at 100
 */
public record TodayProgressDto(
        String date,
        long applied,
        int target,
        long remaining,
        int percentage) {
}
