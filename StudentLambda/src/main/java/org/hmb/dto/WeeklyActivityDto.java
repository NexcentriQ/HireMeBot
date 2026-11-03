package org.hmb.dto;

import java.util.List;

/**
 * Monday-to-Friday application counts for the current week, for the dashboard bar chart.
 *
 * @param weekStartDate    the Monday of the current week, {@code YYYY-MM-DD}
 * @param targetPerWeekday the daily target (default 50)
 * @param days             exactly five entries, Monday first, each with its real date and count
 */
public record WeeklyActivityDto(
        String weekStartDate,
        int targetPerWeekday,
        List<DayActivity> days) {

    /**
     * @param day          short weekday name: {@code Mon}, {@code Tue}, {@code Wed}, {@code Thu} or {@code Fri}
     * @param date         the calendar date of that weekday, {@code YYYY-MM-DD}
     * @param applications applications recorded that day (0 for days with none, including future days)
     */
    public record DayActivity(String day, String date, long applications) {
    }
}
