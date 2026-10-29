package org.hmb.service;

import org.hmb.dto.StudentApplicationDto;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.dto.StudentStatisticsDto;
import org.hmb.exception.ApiException;
import org.hmb.repository.StudentApplicationRepository;
import org.hmb.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Returns today's applications for a student, split into fixed-size pages, together with dashboard statistics.
 *
 * <p>The reporting date is always "today" in the reporting zone (default America/Chicago), taken from an
 * injectable {@link Clock}; there is no date parameter. The date is captured once per request so the applications
 * and all statistics refer to the same day even around midnight.
 *
 * <p><b>Time handling.</b> {@code applications.applied_at} holds UTC wall-clock values (see
 * {@link StudentApplicationDto}). Today is converted to a half-open UTC range {@code [start, end)}. Daylight-saving
 * days are therefore 23 or 25 hours long, never assumed to be 24.
 *
 * <p><b>Pagination</b> happens here in Java after the full day is loaded; the database is never asked for
 * LIMIT/OFFSET. The student check, the applications query and the statistics aggregate all run on one connection.
 */
public class StudentApplicationService {

    public static final int PAGE_SIZE = 20;

    private static final Logger LOG = LoggerFactory.getLogger(StudentApplicationService.class);

    private final DataSource dataSource;
    private final StudentRepository students;
    private final StudentApplicationRepository applications;
    private final StudentStatisticsService statistics;
    private final ZoneId zone;
    private final Clock clock;

    public StudentApplicationService(DataSource dataSource,
                                     StudentRepository students,
                                     StudentApplicationRepository applications,
                                     StudentStatisticsService statistics,
                                     ZoneId zone,
                                     Clock clock) {
        this.dataSource = dataSource;
        this.students = students;
        this.applications = applications;
        this.statistics = statistics;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * @param studentId the student making the request (dev header today, JWT subject later)
     * @throws ApiException 404 unknown or soft-deleted student, 503 database unavailable, 500 anything else
     */
    public StudentApplicationsResponse getApplications(long studentId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        DayRange range = DayRange.of(today, zone);

        List<StudentApplicationDto> found;
        StudentStatisticsDto stats;
        try (Connection connection = dataSource.getConnection()) {
            if (!students.isActiveStudent(connection, studentId)) {
                throw ApiException.notFound("Student not found");
            }
            found = applications.findByStudentAndRange(connection, studentId, range.startUtc(), range.endUtc(), zone);
            stats = statistics.calculate(connection, studentId, today);
        } catch (ApiException e) {
            LOG.warn("Rejected applications request for student {}: {} {}", studentId, e.getStatusCode(), e.getMessage());
            throw e;
        } catch (SQLException e) {
            throw translate(e);
        } catch (RuntimeException e) {
            LOG.error("Unexpected failure loading applications", e);
            throw ApiException.internal(e);
        }

        Map<Integer, List<StudentApplicationDto>> pages = paginate(found, PAGE_SIZE);
        LOG.info("Student {} has {} application(s) on {} ({} page(s))", studentId, found.size(), today, pages.size());
        return StudentApplicationsResponse.of(new StudentApplicationsResponse.Data(
                studentId, today.toString(), found.size(), PAGE_SIZE, pages.size(), pages, stats));
    }

    /**
     * Splits {@code items} into consecutive pages of at most {@code pageSize}, numbered from 1, keeping order.
     * No empty trailing pages; an empty input gives an empty map.
     */
    static <T> Map<Integer, List<T>> paginate(List<T> items, int pageSize) {
        Map<Integer, List<T>> pages = new LinkedHashMap<>();
        for (int from = 0; from < items.size(); from += pageSize) {
            int to = Math.min(from + pageSize, items.size());
            pages.put(from / pageSize + 1, List.copyOf(items.subList(from, to)));
        }
        return pages;
    }

    /** A calendar day expressed as a half-open range of UTC wall-clock DATETIME values. */
    record DayRange(LocalDateTime startUtc, LocalDateTime endUtc) {

        static DayRange of(LocalDate day, ZoneId zone) {
            // atStartOfDay(zone) is the first instant of the day even when midnight does not exist (DST gap).
            LocalDateTime start = day.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime end = day.plusDays(1).atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            return new DayRange(start, end);
        }
    }

    /** Maps JDBC failures to HTTP errors: connectivity problems become 503, everything else 500. */
    static ApiException translate(SQLException e) {
        if (isConnectivityProblem(e)) {
            LOG.error("Database unavailable: {}", e.toString());
            return ApiException.serviceUnavailable(e);
        }
        LOG.error("Database error (SQLState={}, code={})", e.getSQLState(), e.getErrorCode(), e);
        return ApiException.internal(e);
    }

    private static boolean isConnectivityProblem(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
