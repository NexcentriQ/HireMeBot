package org.hmb.dto;

/**
 * Dashboard statistics for a student, all computed from the database for the current reporting date.
 *
 * @param asOfDate        the reporting date these statistics were computed for, {@code YYYY-MM-DD}
 * @param timezone        the reporting time zone id, e.g. {@code America/Chicago}
 * @param monthlyProgress progress for the current calendar month
 * @param todayProgress   progress for {@code asOfDate}
 * @param weeklyActivity  Monday-to-Friday counts for the current week
 */
public record StudentStatisticsDto(
        String asOfDate,
        String timezone,
        MonthlyProgressDto monthlyProgress,
        TodayProgressDto todayProgress,
        WeeklyActivityDto weeklyActivity) {
}
