package org.hmb.service;

import org.hmb.config.DatabaseConfig;
import org.hmb.dto.StudentApplicationDto;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.dto.StudentStatisticsDto;
import org.hmb.dto.WeeklyActivityDto;
import org.hmb.exception.ApiException;
import org.hmb.repository.StudentApplicationRepository;
import org.hmb.repository.StudentRepository;
import org.hmb.repository.StudentStatisticsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real service against a real MySQL that already contains hiremebot_db_V1.sql.
 *
 * <p>Skipped unless {@code RUN_DB_TESTS=true} and the {@code DB_*} variables point at a database that is
 * safe to write to. Every test creates its own users and deletes them afterwards; nothing else is touched.
 *
 * <p>There is no date parameter: the service reports "today" from an injectable clock. Each test builds a service
 * pinned to a chosen day with {@link #serviceOn(LocalDate)} and seeds applications with explicit UTC
 * {@code applied_at} values, so day boundaries and the timezone bucketing can be checked to the second.
 */
class StudentApplicationFlowIntegrationTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final int DAILY_TARGET = 50;

    private record Fixture(long managerId, long recruiterId, List<Long> studentIds) {
    }

    private record Seeded(long id, LocalDateTime appliedAtUtc) {
    }

    private static DataSource dataSource;

    private final List<Fixture> created = new ArrayList<>();

    @BeforeAll
    static void connect() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_DB_TESTS")),
                "Set RUN_DB_TESTS=true and the DB_* variables to run database integration tests");
        dataSource = DatabaseConfig.getDataSource();
    }

    /** A service whose "today" is {@code today} in Chicago (clock pinned to local noon that day). */
    private static StudentApplicationService serviceOn(LocalDate today) {
        Instant noon = today.atTime(12, 0).atZone(CHICAGO).toInstant();
        StudentStatisticsService statistics =
                new StudentStatisticsService(new StudentStatisticsRepository(), CHICAGO, DAILY_TARGET);
        return new StudentApplicationService(dataSource, new StudentRepository(),
                new StudentApplicationRepository(), statistics, CHICAGO, Clock.fixed(noon, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (Fixture f : created) {
                execute(c, "DELETE FROM applications WHERE recruiter_id = ?", f.recruiterId());
                execute(c, "DELETE FROM recruiter_student_assignments WHERE recruiter_id = ?", f.recruiterId());
                for (long studentId : f.studentIds()) {
                    execute(c, "DELETE FROM users WHERE user_id = ?", studentId);
                }
                // Recruiters reference managers (ON DELETE RESTRICT), so recruiter first.
                execute(c, "DELETE FROM users WHERE user_id = ?", f.recruiterId());
                execute(c, "DELETE FROM users WHERE user_id = ?", f.managerId());
            }
        }
        created.clear();
    }

    // ---- timestamp strategy ---------------------------------------------------------------------------------

    /**
     * applications.applied_at defaults to CURRENT_TIMESTAMP, which MySQL evaluates in the session time zone.
     * The service reads assume that value is UTC, so verify that the pooled connections really run in UTC
     * regardless of the server's or the JVM's own zone.
     */
    @Test
    void connectionsRunInUtcSoTheAppliedAtDefaultIsUtc() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), CURRENT_TIMESTAMP())");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "CURRENT_TIMESTAMP must equal UTC_TIMESTAMP() on the app's connections");
        }
    }

    // ---- pagination against the real database ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} applications -> pages {1}")
    @CsvSource(delimiter = '|', value = {
            "10 | 10",
            "20 | 20",
            "21 | 20,1",
            "35 | 20,15",
            "50 | 20,20,10"})
    void paginatesRealRowsNewestFirst(int count, String expectedPageSizes) throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        // One application per minute, starting at local midnight (05:00Z) of 2026-10-25.
        List<Seeded> seeded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seeded.add(insertApplication(f, student, LocalDateTime.parse("2026-10-25T05:00:00").plusMinutes(i)));
        }

        StudentApplicationsResponse.Data data = serviceOn(LocalDate.of(2026, 10, 25)).getApplications(student).data();

        String[] sizes = expectedPageSizes.split(",");
        assertEquals(count, data.totalApplications());
        assertEquals(sizes.length, data.totalPages());
        for (int page = 1; page <= sizes.length; page++) {
            assertEquals(Integer.parseInt(sizes[page - 1].trim()), data.pages().get(page).size(), "page " + page);
        }
        List<Long> returned = flatten(data);
        List<Long> expected = seeded.stream()
                .sorted(Comparator.comparing(Seeded::appliedAtUtc).thenComparing(Seeded::id).reversed())
                .map(Seeded::id).toList();
        assertEquals(expected, returned, "concatenated pages must be newest first");
        assertEquals(count, Set.copyOf(returned).size(), "no application may appear on two pages");
        // Today's list size and today's statistic agree.
        assertEquals(count, data.statistics().todayProgress().applied());
    }

    @Test
    void studentWithNoApplicationsTodayGetsEmptyPagesButRealStatistics() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        // Nothing today (2026-10-29) but history earlier this month and week.
        insertOnChicagoDate(f, student, LocalDate.of(2026, 10, 1), 3);     // this month, earlier
        insertOnChicagoDate(f, student, LocalDate.of(2026, 10, 26), 2);    // Monday this week

        StudentApplicationsResponse.Data data = serviceOn(LocalDate.of(2026, 10, 29)).getApplications(student).data();

        assertEquals(0, data.totalApplications());
        assertEquals(0, data.totalPages());
        assertTrue(data.pages().isEmpty());
        assertEquals(0, data.statistics().todayProgress().applied());
        assertEquals(5, data.statistics().monthlyProgress().applied());   // history still counted
        assertEquals(2, weekdayCount(data.statistics(), "Mon"));
    }

    // ---- filtering ------------------------------------------------------------------------------------------

    @Test
    void returnsOnlyTheRequestedStudentsApplications() throws SQLException {
        Fixture f = fixture(2);
        long alice = f.studentIds().get(0);
        long bob = f.studentIds().get(1);
        LocalDateTime when = LocalDateTime.parse("2026-10-25T15:30:00");
        Set<Long> aliceIds = Set.of(insertApplication(f, alice, when).id(),
                insertApplication(f, alice, when.plusMinutes(1)).id(),
                insertApplication(f, alice, when.plusMinutes(2)).id());
        insertApplication(f, bob, when);
        insertApplication(f, bob, when.plusMinutes(5));

        StudentApplicationsResponse.Data data = serviceOn(LocalDate.of(2026, 10, 25)).getApplications(alice).data();

        assertEquals(3, data.totalApplications());
        assertEquals(aliceIds, Set.copyOf(flatten(data)));
    }

    @Test
    void returnsOnlyTodayAndIncludesExactBoundaries() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        // Chicago is on CDT (UTC-5) on these dates, so local 2026-10-25 is [05:00:00Z, 05:00:00Z next day).
        long earlierThatDay = insertApplication(f, student, LocalDateTime.parse("2026-10-24T15:00:00")).id();
        long lastOfPreviousDay = insertApplication(f, student, LocalDateTime.parse("2026-10-25T04:59:59")).id();
        long firstOfDay = insertApplication(f, student, LocalDateTime.parse("2026-10-25T05:00:00")).id();
        long noon = insertApplication(f, student, LocalDateTime.parse("2026-10-25T17:00:00")).id();
        long lastOfDay = insertApplication(f, student, LocalDateTime.parse("2026-10-26T04:59:59")).id();
        long firstOfNextDay = insertApplication(f, student, LocalDateTime.parse("2026-10-26T05:00:00")).id();

        assertEquals(List.of(lastOfDay, noon, firstOfDay),
                flatten(serviceOn(LocalDate.of(2026, 10, 25)).getApplications(student).data()));
        assertEquals(List.of(lastOfPreviousDay, earlierThatDay),
                flatten(serviceOn(LocalDate.of(2026, 10, 24)).getApplications(student).data()));
        assertEquals(List.of(firstOfNextDay),
                flatten(serviceOn(LocalDate.of(2026, 10, 26)).getApplications(student).data()));
    }

    @Test
    void identicalTimestampsAreOrderedByApplicationIdDescending() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        LocalDateTime same = LocalDateTime.parse("2026-10-25T15:30:00");
        long first = insertApplication(f, student, same).id();
        long second = insertApplication(f, student, same).id();
        long third = insertApplication(f, student, same).id();

        List<Long> returned = flatten(serviceOn(LocalDate.of(2026, 10, 25)).getApplications(student).data());

        assertEquals(List.of(third, second, first), returned);
        assertTrue(third > second && second > first);
    }

    @Test
    void appliedAtIsReturnedInTheReportingZoneOffset() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        insertApplication(f, student, LocalDateTime.parse("2026-10-25T15:30:00"));   // 15:30 UTC = 10:30 CDT

        StudentApplicationDto dto = serviceOn(LocalDate.of(2026, 10, 25)).getApplications(student)
                .data().pages().get(1).get(0);

        assertEquals("2026-10-25T10:30:00-05:00", dto.appliedAt());
        assertEquals("Integration Test Engineer", dto.jobTitle());
    }

    // ---- daylight saving (applications) ---------------------------------------------------------------------

    /** 2026-11-01 (clocks go back) is 25 hours long in Chicago: [05:00Z, 06:00Z on Nov 2). */
    @Test
    void fallBackDayCoversTwentyFiveHours() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        insertApplication(f, student, LocalDateTime.parse("2026-11-01T04:59:59"));
        long start = insertApplication(f, student, LocalDateTime.parse("2026-11-01T05:00:00")).id();
        long repeatedHour = insertApplication(f, student, LocalDateTime.parse("2026-11-01T07:30:00")).id();
        long end = insertApplication(f, student, LocalDateTime.parse("2026-11-02T05:59:59")).id();
        insertApplication(f, student, LocalDateTime.parse("2026-11-02T06:00:00"));

        List<Long> returned = flatten(serviceOn(LocalDate.of(2026, 11, 1)).getApplications(student).data());

        assertEquals(List.of(end, repeatedHour, start), returned);
    }

    /** 2026-03-08 (clocks go forward) is 23 hours long in Chicago: [06:00Z, 05:00Z on Mar 9). */
    @Test
    void springForwardDayCoversTwentyThreeHours() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        insertApplication(f, student, LocalDateTime.parse("2026-03-08T05:59:59"));
        long start = insertApplication(f, student, LocalDateTime.parse("2026-03-08T06:00:00")).id();
        long end = insertApplication(f, student, LocalDateTime.parse("2026-03-09T04:59:59")).id();
        insertApplication(f, student, LocalDateTime.parse("2026-03-09T05:00:00"));

        List<Long> returned = flatten(serviceOn(LocalDate.of(2026, 3, 8)).getApplications(student).data());

        assertEquals(List.of(end, start), returned);
    }

    // ---- statistics -----------------------------------------------------------------------------------------

    /**
     * Seeds a mix of history and checks monthly/today/weekly together, including that:
     * previous and next month are excluded from the month count; an evening application is bucketed to its
     * Chicago weekday (not the UTC one); and a future weekday reports zero.
     */
    @Test
    void computesMonthlyTodayAndWeeklyStatisticsFromRealRows() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        LocalDate today = LocalDate.of(2026, 10, 29);                  // Thursday; week is Mon 10-26 .. Fri 10-30

        insertOnChicagoDate(f, student, LocalDate.of(2026, 9, 30), 2);  // previous month -> excluded
        insertOnChicagoDate(f, student, LocalDate.of(2026, 10, 1), 3);  // this month, before this week
        insertOnChicagoDate(f, student, LocalDate.of(2026, 10, 26), 2); // Monday
        insertApplication(f, student, LocalDateTime.parse("2026-10-29T04:30:00")); // Wed 23:30 CDT -> Chicago Oct 28
        insertOnChicagoDate(f, student, today, 4);                      // Thursday (today)
        insertOnChicagoDate(f, student, LocalDate.of(2026, 11, 2), 1);  // next month and next week -> excluded

        StudentStatisticsDto stats = serviceOn(today).getApplications(student).data().statistics();

        assertEquals("2026-10-29", stats.asOfDate());
        assertEquals("America/Chicago", stats.timezone());

        // Monthly: 3 + 2 + 1 (the evening app, Oct 28) + 4 = 10; Sep and Nov excluded.
        assertEquals("2026-10", stats.monthlyProgress().month());
        assertEquals(10, stats.monthlyProgress().applied());
        assertEquals(1100, stats.monthlyProgress().target());
        assertEquals(1090, stats.monthlyProgress().remaining());
        assertEquals(1, stats.monthlyProgress().percentage());         // round(10/1100*100)

        // Today: only the 4 Thursday-noon rows; the 04:30Z row is the previous Chicago day.
        assertEquals(4, stats.todayProgress().applied());
        assertEquals(50, stats.todayProgress().target());
        assertEquals(46, stats.todayProgress().remaining());

        // Weekly Mon-Fri with the evening app bucketed to Wednesday and Friday (future) zero.
        assertEquals("2026-10-26", stats.weeklyActivity().weekStartDate());
        assertEquals(2, weekdayCount(stats, "Mon"));
        assertEquals(0, weekdayCount(stats, "Tue"));
        assertEquals(1, weekdayCount(stats, "Wed"));
        assertEquals(4, weekdayCount(stats, "Thu"));
        assertEquals(0, weekdayCount(stats, "Fri"));
    }

    // ---- whole request path: event -> handler -> service -> MySQL -> JSON -------------------------------------

    @Test
    void handlerReturnsAllPagesAndStatisticsInOneJsonResponse() throws Exception {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        LocalDate today = LocalDate.of(2026, 10, 29);
        for (int i = 0; i < 21; i++) {
            insertApplication(f, student, chicagoTime(today, 9, 0).plusMinutes(i));
        }
        var handler = new org.hmb.handler.StudentLambdaHandler(serviceOn(today), true);
        var event = com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.builder()
                .withRawPath("/api/v1/student/applications")
                .withHeaders(Map.of("x-dev-student-id", String.valueOf(student)))
                .withRequestContext(com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod("GET").withPath("/api/v1/student/applications").build())
                        .build())
                .build();

        var response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody()).get("data");
        assertEquals(student, data.get("studentId").asLong());
        assertEquals("2026-10-29", data.get("date").asText());
        assertEquals(21, data.get("totalApplications").asInt());
        assertEquals(2, data.get("totalPages").asInt());
        assertEquals(20, data.get("pages").get("1").size());
        assertEquals(1, data.get("pages").get("2").size());
        assertEquals(21, data.get("statistics").get("todayProgress").get("applied").asInt());
        assertEquals(5, data.get("statistics").get("weeklyActivity").get("days").size());
    }

    // ---- student validation ---------------------------------------------------------------------------------

    @Test
    void nonexistentStudentIs404() {
        ApiException e = assertThrows(ApiException.class,
                () -> serviceOn(LocalDate.of(2026, 10, 25)).getApplications(Long.MAX_VALUE));
        assertEquals(404, e.getStatusCode());
    }

    @Test
    void softDeletedStudentIs404() throws SQLException {
        Fixture f = fixture(1);
        long student = f.studentIds().get(0);
        insertApplication(f, student, LocalDateTime.parse("2026-10-25T15:30:00"));
        try (Connection c = dataSource.getConnection()) {
            execute(c, "UPDATE users SET is_deleted = TRUE WHERE user_id = ?", student);
        }

        ApiException e = assertThrows(ApiException.class,
                () -> serviceOn(LocalDate.of(2026, 10, 25)).getApplications(student));

        assertEquals(404, e.getStatusCode());
    }

    @Test
    void aUserWhoIsNotAStudentIs404() throws SQLException {
        Fixture f = fixture(1);

        ApiException e = assertThrows(ApiException.class,
                () -> serviceOn(LocalDate.of(2026, 10, 25)).getApplications(f.recruiterId()));

        assertEquals(404, e.getStatusCode());
    }

    // ---- fixtures and helpers -------------------------------------------------------------------------------

    private static long weekdayCount(StudentStatisticsDto stats, String day) {
        return stats.weeklyActivity().days().stream()
                .filter(d -> d.day().equals(day))
                .map(WeeklyActivityDto.DayActivity::applications)
                .findFirst().orElseThrow();
    }

    private static List<Long> flatten(StudentApplicationsResponse.Data data) {
        return data.pages().values().stream().flatMap(List::stream)
                .map(StudentApplicationDto::applicationId).collect(Collectors.toList());
    }

    /** The UTC wall-clock value for a given Chicago local date and time. */
    private static LocalDateTime chicagoTime(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(CHICAGO).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Inserts {@code count} applications at noon Chicago on the given calendar day. */
    private void insertOnChicagoDate(Fixture f, long studentId, LocalDate day, int count) throws SQLException {
        for (int i = 0; i < count; i++) {
            insertApplication(f, studentId, chicagoTime(day, 12, i));
        }
    }

    /** Creates a manager, a recruiter and {@code students} students, all assigned to that recruiter. */
    private Fixture fixture(int students) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            long manager = insertUser(c);
            execute(c, "INSERT INTO managers (manager_id) VALUES (?)", manager);
            long recruiter = insertUser(c);
            execute(c, "INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, ?)", recruiter, manager);
            List<Long> studentIds = new ArrayList<>();
            Fixture f = new Fixture(manager, recruiter, studentIds);
            created.add(f);   // registered first so cleanUp() also runs if a later insert fails
            for (int i = 0; i < students; i++) {
                long student = insertUser(c);
                studentIds.add(student);
                execute(c, "INSERT INTO students (student_id) VALUES (?)", student);
                execute(c, "INSERT INTO recruiter_student_assignments (recruiter_id, student_id, assigned_by) VALUES (?, ?, ?)",
                        recruiter, student, manager);
            }
            return f;
        }
    }

    private Seeded insertApplication(Fixture f, long studentId, LocalDateTime appliedAtUtc) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO applications (student_id, recruiter_id, job_title, company_name, job_location, "
                             + "job_link, job_source, applied_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, studentId);
            ps.setLong(2, f.recruiterId());
            ps.setString(3, "Integration Test Engineer");
            ps.setString(4, "Test Co");
            ps.setString(5, "Remote");
            ps.setString(6, "https://example.test/jobs/" + UUID.randomUUID());
            ps.setString(7, "IT");
            ps.setObject(8, appliedAtUtc);   // sent as-is; the session time zone is UTC (see DatabaseConfig)
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return new Seeded(keys.getLong(1), appliedAtUtc);
            }
        }
    }

    private static long insertUser(Connection c) throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String phone = "9" + (100000000 + ThreadLocalRandom.current().nextInt(900000000));
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (first_name, last_name, email, phone) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "it" + suffix);
            ps.setString(2, "user" + suffix);
            ps.setString(3, "it-" + suffix + "@example.test");
            ps.setString(4, phone);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void execute(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setLong(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
