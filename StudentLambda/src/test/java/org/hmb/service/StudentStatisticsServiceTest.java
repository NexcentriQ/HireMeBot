package org.hmb.service;

import org.hmb.dto.StudentStatisticsDto;
import org.hmb.dto.WeeklyActivityDto;
import org.hmb.repository.StudentStatisticsRepository;
import org.hmb.repository.StudentStatisticsRepository.CountRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentStatisticsServiceTest {

    private static final long STUDENT_ID = 1000000002L;
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final int DAILY_TARGET = 50;

    @Mock StudentStatisticsRepository repository;
    @Mock Connection connection;
    @Captor ArgumentCaptor<List<CountRange>> rangesCaptor;

    private StudentStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new StudentStatisticsService(repository, CHICAGO, DAILY_TARGET);
    }

    /** counts are returned in service order: [month, today, Mon, Tue, Wed, Thu, Fri]. */
    private void counts(long... values) throws SQLException {
        when(repository.countInRanges(any(), anyLong(), any())).thenReturn(values);
    }

    private StudentStatisticsDto calculate(LocalDate today) throws SQLException {
        return service.calculate(connection, STUDENT_ID, today);
    }

    // ---- monthly progress -----------------------------------------------------------------------------------

    @Test
    void monthlyProgressUsesWeekdayTargetAndCurrentMonth() throws SQLException {
        counts(820, 35, 0, 0, 0, 0, 0);

        var monthly = calculate(LocalDate.of(2026, 10, 29)).monthlyProgress();

        assertEquals("2026-10", monthly.month());
        assertEquals(820, monthly.applied());
        assertEquals(1100, monthly.target());       // October 2026 has 22 weekdays * 50
        assertEquals(280, monthly.remaining());
        assertEquals(75, monthly.percentage());     // 820 / 1100 = 74.5 -> 75
    }

    @Test
    void monthlyProgressAboveTargetIsNotCappedAndRemainingIsZero() throws SQLException {
        counts(1200, 0, 0, 0, 0, 0, 0);

        var monthly = calculate(LocalDate.of(2026, 10, 29)).monthlyProgress();

        assertEquals(1200, monthly.applied());
        assertEquals(0, monthly.remaining());
        assertEquals(109, monthly.percentage());    // 1200 / 1100 = 109.09 -> 109
    }

    // ---- today progress -------------------------------------------------------------------------------------

    @Test
    void todayProgressUsesDailyTarget() throws SQLException {
        counts(820, 35, 0, 0, 0, 0, 0);

        var today = calculate(LocalDate.of(2026, 10, 29)).todayProgress();

        assertEquals("2026-10-29", today.date());
        assertEquals(35, today.applied());
        assertEquals(50, today.target());
        assertEquals(15, today.remaining());
        assertEquals(70, today.percentage());
    }

    @Test
    void todayProgressCountsWeekendApplicationsAndIsNotCapped() throws SQLException {
        counts(0, 60, 0, 0, 0, 0, 0);

        // 2026-10-31 is a Saturday; today's count is still reported.
        var today = calculate(LocalDate.of(2026, 10, 31)).todayProgress();

        assertEquals(60, today.applied());
        assertEquals(0, today.remaining());
        assertEquals(120, today.percentage());
    }

    @Test
    void zeroApplicationsGivesZeroProgress() throws SQLException {
        counts(0, 0, 0, 0, 0, 0, 0);

        StudentStatisticsDto stats = calculate(LocalDate.of(2026, 10, 29));

        assertEquals(0, stats.todayProgress().applied());
        assertEquals(50, stats.todayProgress().remaining());
        assertEquals(0, stats.todayProgress().percentage());
        assertEquals(0, stats.monthlyProgress().applied());
        assertEquals(1100, stats.monthlyProgress().remaining());
    }

    // ---- weekly activity ------------------------------------------------------------------------------------

    @Test
    void weeklyActivityReturnsMondayToFridayWithRealDatesAndZeroFill() throws SQLException {
        counts(0, 38, 50, 44, 50, 38, 0);

        WeeklyActivityDto weekly = calculate(LocalDate.of(2026, 10, 29)).weeklyActivity();

        assertEquals("2026-10-26", weekly.weekStartDate());   // Monday of the week containing Thu 2026-10-29
        assertEquals(50, weekly.targetPerWeekday());
        assertEquals(List.of(
                        new WeeklyActivityDto.DayActivity("Mon", "2026-10-26", 50),
                        new WeeklyActivityDto.DayActivity("Tue", "2026-10-27", 44),
                        new WeeklyActivityDto.DayActivity("Wed", "2026-10-28", 50),
                        new WeeklyActivityDto.DayActivity("Thu", "2026-10-29", 38),
                        new WeeklyActivityDto.DayActivity("Fri", "2026-10-30", 0)),
                weekly.days());
    }

    @Test
    void weeklyActivityHandlesAWeekSpanningTwoMonths() throws SQLException {
        counts(0, 0, 1, 2, 3, 4, 5);

        // Monday 2026-09-28; the week runs into October.
        WeeklyActivityDto weekly = calculate(LocalDate.of(2026, 9, 30)).weeklyActivity();

        assertEquals("2026-09-28", weekly.weekStartDate());
        assertEquals(List.of("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02"),
                weekly.days().stream().map(WeeklyActivityDto.DayActivity::date).toList());
    }

    @Test
    void weeklyActivityHandlesAWeekSpanningTwoYears() throws SQLException {
        counts(0, 0, 1, 2, 3, 4, 5);

        // Monday 2026-12-28; Friday falls in 2027.
        WeeklyActivityDto weekly = calculate(LocalDate.of(2026, 12, 31)).weeklyActivity();

        assertEquals("2026-12-28", weekly.weekStartDate());
        assertEquals(List.of("2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31", "2027-01-01"),
                weekly.days().stream().map(WeeklyActivityDto.DayActivity::date).toList());
    }

    @Test
    void alwaysReturnsExactlyFiveWeekdays() throws SQLException {
        counts(0, 0, 0, 0, 0, 0, 0);
        assertEquals(5, calculate(LocalDate.of(2026, 10, 29)).weeklyActivity().days().size());
    }

    // ---- the ranges handed to the repository ----------------------------------------------------------------

    @Test
    void passesMonthTodayAndWeekdayRangesInUtc() throws SQLException {
        counts(new long[7]);

        calculate(LocalDate.of(2026, 10, 29));

        verify(repository).countInRanges(eq(connection), eq(STUDENT_ID), rangesCaptor.capture());
        List<CountRange> ranges = rangesCaptor.getValue();
        assertEquals(7, ranges.size());
        // Chicago is on CDT (UTC-5) throughout this window.
        assertEquals(new CountRange(LocalDateTime.parse("2026-10-01T05:00:00"),
                LocalDateTime.parse("2026-11-01T05:00:00")), ranges.get(0));       // month
        assertEquals(new CountRange(LocalDateTime.parse("2026-10-29T05:00:00"),
                LocalDateTime.parse("2026-10-30T05:00:00")), ranges.get(1));       // today
        assertEquals(new CountRange(LocalDateTime.parse("2026-10-26T05:00:00"),
                LocalDateTime.parse("2026-10-27T05:00:00")), ranges.get(2));       // Monday
        assertEquals(new CountRange(LocalDateTime.parse("2026-10-30T05:00:00"),
                LocalDateTime.parse("2026-10-31T05:00:00")), ranges.get(6));       // Friday
    }

    /** After the 2026-11-01 fall-back, Chicago is CST (UTC-6): weekday ranges shift by an hour. */
    @Test
    void weekdayRangesFollowDaylightSavingAcrossWeeks() throws SQLException {
        counts(new long[7]);

        calculate(LocalDate.of(2026, 11, 5));   // Thursday; Monday is 2026-11-02 (CST)

        verify(repository).countInRanges(any(), anyLong(), rangesCaptor.capture());
        assertEquals(new CountRange(LocalDateTime.parse("2026-11-02T06:00:00"),
                LocalDateTime.parse("2026-11-03T06:00:00")), rangesCaptor.getValue().get(2));   // Monday, CST
    }

    @Test
    void reportsAsOfDateAndTimezone() throws SQLException {
        counts(new long[7]);

        StudentStatisticsDto stats = calculate(LocalDate.of(2026, 10, 29));

        assertEquals("2026-10-29", stats.asOfDate());
        assertEquals("America/Chicago", stats.timezone());
    }

    // ---- weekday counting (monthly target building block) ---------------------------------------------------

    @Test
    void countWeekdaysCoversTheMonthlyTargetCases() {
        assertEquals(22, StudentStatisticsService.countWeekdays(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)));      // October 2026
        assertEquals(21, StudentStatisticsService.countWeekdays(
                LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1)));        // February 2024 (leap)
        assertEquals(20, StudentStatisticsService.countWeekdays(
                LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1)));        // February 2025 (non-leap)
        assertEquals(5, StudentStatisticsService.countWeekdays(
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)));     // one Mon-Sun week
        // A 31-day span that begins on a Monday has 23 weekdays (4 weeks + Mon, Tue, Wed).
        LocalDate monday = LocalDate.of(2026, 10, 26);
        assertEquals(23, StudentStatisticsService.countWeekdays(monday, monday.plusDays(31)));
    }

    @Test
    void percentageRoundsToWholeNumbersAndGuardsAgainstZeroTarget() {
        assertEquals(0, StudentStatisticsService.percentage(0, 50));
        assertEquals(100, StudentStatisticsService.percentage(50, 50));
        assertEquals(13, StudentStatisticsService.percentage(1, 8));     // 12.5 -> 13
        assertEquals(38, StudentStatisticsService.percentage(3, 8));     // 37.5 -> 38
        assertEquals(150, StudentStatisticsService.percentage(75, 50));  // not capped
        assertEquals(0, StudentStatisticsService.percentage(5, 0));      // no division by zero
    }
}
