package org.hmb.service;

import org.hmb.dto.MonthlyProgressDto;
import org.hmb.dto.StudentStatisticsDto;
import org.hmb.dto.TodayProgressDto;
import org.hmb.dto.WeeklyActivityDto;
import org.hmb.repository.StudentStatisticsRepository;
import org.hmb.repository.StudentStatisticsRepository.CountRange;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes dashboard statistics for a student: monthly progress, today's progress and Monday-to-Friday weekly
 * activity. Read-only; it runs on a connection the caller owns.
 *
 * <p><b>Time handling.</b> Reporting periods are calendar ranges in the reporting zone (default America/Chicago)
 * converted to the half-open UTC ranges that match how {@code applied_at} is stored. Each day's boundary is taken
 * from {@link LocalDate#atStartOfDay(ZoneId)}, so daylight-saving days are 23 or 25 hours, never assumed to be 24.
 */
public class StudentStatisticsService {

    /** Count ranges are passed to the repository in this fixed order. */
    private static final int MONTH = 0;
    private static final int TODAY = 1;
    private static final int FIRST_WEEKDAY = 2;   // Monday; Tuesday..Friday follow

    private static final String[] WEEKDAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri"};

    private final StudentStatisticsRepository repository;
    private final ZoneId zone;
    private final int dailyTarget;

    public StudentStatisticsService(StudentStatisticsRepository repository, ZoneId zone, int dailyTarget) {
        this.repository = repository;
        this.zone = zone;
        this.dailyTarget = dailyTarget;
    }

    /**
     * @param connection a connection the caller owns (not committed or closed here)
     * @param studentId  the student; every count is filtered by it
     * @param today      the reporting date, already resolved in the reporting zone
     */
    public StudentStatisticsDto calculate(Connection connection, long studentId, LocalDate today) throws SQLException {
        YearMonth month = YearMonth.from(today);
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.plusMonths(1).atDay(1);              // exclusive
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<CountRange> ranges = new ArrayList<>();
        ranges.add(MONTH, range(monthStart, monthEnd));
        ranges.add(TODAY, range(today, today.plusDays(1)));
        for (int i = 0; i < WEEKDAY_NAMES.length; i++) {               // Monday..Friday
            LocalDate day = monday.plusDays(i);
            ranges.add(FIRST_WEEKDAY + i, range(day, day.plusDays(1)));
        }

        long[] counts = repository.countInRanges(connection, studentId, ranges);

        return new StudentStatisticsDto(
                today.toString(),
                zone.getId(),
                monthlyProgress(month, monthStart, monthEnd, counts[MONTH]),
                todayProgress(today, counts[TODAY]),
                weeklyActivity(monday, counts));
    }

    private MonthlyProgressDto monthlyProgress(YearMonth month, LocalDate monthStart, LocalDate monthEnd, long applied) {
        int target = countWeekdays(monthStart, monthEnd) * dailyTarget;
        return new MonthlyProgressDto(
                month.toString(), applied, target, remaining(target, applied), percentage(applied, target));
    }

    private TodayProgressDto todayProgress(LocalDate today, long applied) {
        return new TodayProgressDto(
                today.toString(), applied, dailyTarget, remaining(dailyTarget, applied), percentage(applied, dailyTarget));
    }

    private WeeklyActivityDto weeklyActivity(LocalDate monday, long[] counts) {
        List<WeeklyActivityDto.DayActivity> days = new ArrayList<>(WEEKDAY_NAMES.length);
        for (int i = 0; i < WEEKDAY_NAMES.length; i++) {
            LocalDate day = monday.plusDays(i);
            days.add(new WeeklyActivityDto.DayActivity(WEEKDAY_NAMES[i], day.toString(), counts[FIRST_WEEKDAY + i]));
        }
        return new WeeklyActivityDto(monday.toString(), dailyTarget, days);
    }

    /** Monday..Friday count in {@code [startInclusive, endExclusive)}. */
    static int countWeekdays(LocalDate startInclusive, LocalDate endExclusive) {
        int weekdays = 0;
        for (LocalDate day = startInclusive; day.isBefore(endExclusive); day = day.plusDays(1)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                weekdays++;
            }
        }
        return weekdays;
    }

    private static long remaining(int target, long applied) {
        return Math.max(target - applied, 0);
    }

    /** Whole-number percentage, not capped at 100; 0 when the target is non-positive (never divide by zero). */
    static int percentage(long applied, int target) {
        if (target <= 0) {
            return 0;
        }
        return (int) Math.round(applied * 100.0 / target);
    }

    private CountRange range(LocalDate startInclusive, LocalDate endExclusive) {
        return new CountRange(toUtc(startInclusive), toUtc(endExclusive));
    }

    /** Start of the given calendar day in the reporting zone, as a UTC wall-clock value. */
    private LocalDateTime toUtc(LocalDate day) {
        return day.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
