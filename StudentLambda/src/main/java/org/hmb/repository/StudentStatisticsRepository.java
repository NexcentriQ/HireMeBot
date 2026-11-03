package org.hmb.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregate application counts for the dashboard. Stateless; the caller owns the connection.
 *
 * <p>Counts are produced with {@code COUNT}/{@code SUM} in SQL, never by fetching the rows and counting in Java.
 */
public class StudentStatisticsRepository {

    /** A half-open UTC range to count applications in, matching how {@code applied_at} is stored. */
    public record CountRange(LocalDateTime startUtcInclusive, LocalDateTime endUtcExclusive) {
    }

    /**
     * Counts a student's applications in each of the given UTC ranges, returning the counts in the same order.
     *
     * <p>One row is returned, with one conditional {@code SUM} per range, over a single scan of the smallest
     * window covering every range. Ranges may overlap (e.g. a month range and the days within it); each is counted
     * independently. Computing the ranges in Java keeps day boundaries correct across daylight-saving changes,
     * which {@code GROUP BY DATE(applied_at)} on a UTC column would get wrong near midnight.
     *
     * @return an array of counts, one per range, in input order; empty if {@code ranges} is empty
     */
    public long[] countInRanges(Connection connection, long studentId, List<CountRange> ranges) throws SQLException {
        if (ranges.isEmpty()) {
            return new long[0];
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < ranges.size(); i++) {
            sql.append(i == 0 ? "" : ", ")
                    .append("SUM(CASE WHEN applied_at >= ? AND applied_at < ? THEN 1 ELSE 0 END)");
        }
        sql.append(" FROM applications WHERE student_id = ? AND applied_at >= ? AND applied_at < ?");

        LocalDateTime minStart = ranges.stream().map(CountRange::startUtcInclusive)
                .min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime maxEnd = ranges.stream().map(CountRange::endUtcExclusive)
                .max(Comparator.naturalOrder()).orElseThrow();

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int param = 1;
            for (CountRange range : ranges) {
                statement.setObject(param++, range.startUtcInclusive());
                statement.setObject(param++, range.endUtcExclusive());
            }
            statement.setLong(param++, studentId);
            statement.setObject(param++, minStart);
            statement.setObject(param, maxEnd);

            try (ResultSet rs = statement.executeQuery()) {
                long[] counts = new long[ranges.size()];
                if (rs.next()) {
                    for (int i = 0; i < ranges.size(); i++) {
                        // SUM over no matching rows is NULL; getLong maps that to 0.
                        counts[i] = rs.getLong(i + 1);
                    }
                }
                return counts;
            }
        }
    }
}
