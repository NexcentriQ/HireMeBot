package org.hmb.dto;

/**
 * Monthly application progress for the current calendar month.
 *
 * @param month      the month in {@code YYYY-MM} form, e.g. {@code 2026-10}
 * @param applied    applications recorded this month (may exceed {@code target})
 * @param target     weekdays in the month times the daily target
 * @param remaining  {@code max(target - applied, 0)}
 * @param percentage {@code round(applied / target * 100)}, not capped at 100
 */
public record MonthlyProgressDto(
        String month,
        long applied,
        int target,
        long remaining,
        int percentage) {
}
