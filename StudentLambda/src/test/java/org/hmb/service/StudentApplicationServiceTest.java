package org.hmb.service;

import org.hmb.dto.StudentApplicationDto;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.StudentApplicationRepository;
import org.hmb.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentApplicationServiceTest {

    private static final long STUDENT_ID = 1000000002L;
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock StudentRepository students;
    @Mock StudentApplicationRepository applications;

    private StudentApplicationService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(Instant.parse("2026-10-25T18:00:00Z"));
    }

    private StudentApplicationService serviceAt(Instant now) {
        return new StudentApplicationService(dataSource, students, applications, CHICAGO,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void studentExists() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(students.isActiveStudent(connection, STUDENT_ID)).thenReturn(true);
    }

    /** Newest first, exactly as the repository's ORDER BY would return them. */
    private static List<StudentApplicationDto> applicationsNewestFirst(int count) {
        List<StudentApplicationDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = 1000000000L + count - i;
            list.add(new StudentApplicationDto(id, "Title " + id, "Co", "Austin, TX", "https://example.test/" + id,
                    "Src", "2026-10-25T15:30:00Z"));
        }
        return list;
    }

    @ParameterizedTest(name = "{0} applications -> pages {1}")
    @CsvSource(delimiter = '|', value = {
            "0  | ''",
            "10 | 10",
            "20 | 20",
            "21 | 20,1",
            "35 | 20,15",
            "50 | 20,20,10"})
    void splitsIntoPagesOfTwentyPreservingOrder(int count, String expectedPageSizes) throws SQLException {
        studentExists();
        List<StudentApplicationDto> all = applicationsNewestFirst(count);
        when(applications.findByStudentAndRange(any(), anyLong(), any(), any())).thenReturn(all);

        StudentApplicationsResponse.Data data = service.getApplications(STUDENT_ID, DAY).data();

        List<Integer> expectedSizes = expectedPageSizes.isBlank() ? List.of()
                : Arrays.stream(expectedPageSizes.split(",")).map(String::trim).map(Integer::parseInt).toList();
        assertEquals(count, data.totalApplications());
        assertEquals(20, data.pageSize());
        assertEquals(expectedSizes.size(), data.totalPages());
        assertEquals(expectedSizes.size(), data.pages().size());
        for (int page = 1; page <= expectedSizes.size(); page++) {
            assertEquals(expectedSizes.get(page - 1), data.pages().get(page).size(), "size of page " + page);
        }
        // Pages are numbered 1..n with no gaps, in order.
        assertEquals(java.util.stream.IntStream.rangeClosed(1, expectedSizes.size()).boxed().toList(),
                new ArrayList<>(data.pages().keySet()));

        // Concatenating the pages gives back the original order, and no application is on two pages.
        List<StudentApplicationDto> flattened = data.pages().values().stream().flatMap(List::stream).toList();
        assertEquals(all, flattened);
        Set<Long> ids = flattened.stream().map(StudentApplicationDto::applicationId).collect(Collectors.toSet());
        assertEquals(count, ids.size());
    }

    @Test
    void responseEchoesStudentAndDate() throws SQLException {
        studentExists();
        when(applications.findByStudentAndRange(any(), anyLong(), any(), any())).thenReturn(List.of());

        StudentApplicationsResponse response = service.getApplications(STUDENT_ID, DAY);

        assertTrue(response.success());
        assertEquals(STUDENT_ID, response.data().studentId());
        assertEquals("2026-10-25", response.data().date());
        assertTrue(response.data().pages().isEmpty());
    }

    @Test
    void unknownOrSoftDeletedStudentIs404AndNoApplicationsAreRead() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(students.isActiveStudent(connection, STUDENT_ID)).thenReturn(false);

        ApiException e = assertThrows(ApiException.class, () -> service.getApplications(STUDENT_ID, DAY));

        assertEquals(404, e.getStatusCode());
        verifyNoInteractions(applications);
        verify(connection).close();
    }

    @Test
    void databaseUnavailableIs503() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool timeout", "08001"));

        assertEquals(503, assertThrows(ApiException.class, () -> service.getApplications(STUDENT_ID, DAY)).getStatusCode());
        verifyNoInteractions(students, applications);
    }

    @Test
    void sqlErrorIs500AndDoesNotLeakDetails() throws SQLException {
        studentExists();
        when(applications.findByStudentAndRange(any(), anyLong(), any(), any()))
                .thenThrow(new SQLException("Unknown column 'secret_col' in 'field list'", "42S22", 1054));

        ApiException e = assertThrows(ApiException.class, () -> service.getApplications(STUDENT_ID, DAY));

        assertEquals(500, e.getStatusCode());
        assertTrue(!e.getMessage().contains("secret_col"));
    }

    /**
     * 2026-10-26T03:00Z is still the evening of Oct 25 in Chicago (UTC-5), even though the UTC date is Oct 26.
     * Omitting the date must follow the configured zone, not UTC.
     */
    @Test
    void omittedDateMeansTodayInTheConfiguredTimeZone() throws SQLException {
        service = serviceAt(Instant.parse("2026-10-26T03:00:00Z"));
        studentExists();
        when(applications.findByStudentAndRange(any(), anyLong(), any(), any())).thenReturn(List.of());

        StudentApplicationsResponse response = service.getApplications(STUDENT_ID, null);

        assertEquals("2026-10-25", response.data().date());
        verify(applications).findByStudentAndRange(connection, STUDENT_ID,
                LocalDateTime.parse("2026-10-25T05:00:00"), LocalDateTime.parse("2026-10-26T05:00:00"));
    }

    @Test
    void queriesTheUtcRangeOfTheRequestedLocalDay() throws SQLException {
        studentExists();
        when(applications.findByStudentAndRange(any(), anyLong(), any(), any())).thenReturn(List.of());

        service.getApplications(STUDENT_ID, DAY);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(applications).findByStudentAndRange(any(), anyLong(), start.capture(), end.capture());
        assertEquals(LocalDateTime.parse("2026-10-25T05:00:00"), start.getValue());   // CDT = UTC-5
        assertEquals(LocalDateTime.parse("2026-10-26T05:00:00"), end.getValue());
    }

    // ---- calendar-day boundaries around daylight saving -------------------------------------------------------

    @Test
    void ordinaryDayIsTwentyFourHours() {
        var range = StudentApplicationService.DayRange.of(LocalDate.of(2026, 10, 25), CHICAGO);
        assertEquals(LocalDateTime.parse("2026-10-25T05:00:00"), range.startUtc());
        assertEquals(LocalDateTime.parse("2026-10-26T05:00:00"), range.endUtc());
    }

    /** US clocks go back on 2026-11-01: that Chicago day lasts 25 hours. */
    @Test
    void fallBackDayIsTwentyFiveHours() {
        var range = StudentApplicationService.DayRange.of(LocalDate.of(2026, 11, 1), CHICAGO);
        assertEquals(LocalDateTime.parse("2026-11-01T05:00:00"), range.startUtc());   // midnight CDT
        assertEquals(LocalDateTime.parse("2026-11-02T06:00:00"), range.endUtc());     // midnight CST
    }

    /** US clocks go forward on 2026-03-08: that Chicago day lasts 23 hours. */
    @Test
    void springForwardDayIsTwentyThreeHours() {
        var range = StudentApplicationService.DayRange.of(LocalDate.of(2026, 3, 8), CHICAGO);
        assertEquals(LocalDateTime.parse("2026-03-08T06:00:00"), range.startUtc());   // midnight CST
        assertEquals(LocalDateTime.parse("2026-03-09T05:00:00"), range.endUtc());     // midnight CDT
    }

    @Test
    void daysAreContiguousSoNoApplicationFallsBetweenTwoDays() {
        for (LocalDate d : List.of(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 1))) {
            var today = StudentApplicationService.DayRange.of(d, CHICAGO);
            var tomorrow = StudentApplicationService.DayRange.of(d.plusDays(1), CHICAGO);
            assertEquals(today.endUtc(), tomorrow.startUtc(), "gap or overlap after " + d);
        }
    }

    @Test
    void timeZoneIsConfigurable() {
        var range = StudentApplicationService.DayRange.of(LocalDate.of(2026, 10, 25), ZoneId.of("UTC"));
        assertEquals(LocalDateTime.parse("2026-10-25T00:00:00"), range.startUtc());
        assertEquals(LocalDateTime.parse("2026-10-26T00:00:00"), range.endUtc());
    }
}
