package org.hmb.dto;

import java.util.List;
import java.util.Map;

/**
 * Success body for {@code GET /api/v1/student/applications}.
 *
 * <p>{@code pages} maps 1-based page numbers to that page's applications, in insertion (= page) order. Jackson
 * writes the integer keys as JSON strings ({@code "1"}, {@code "2"}, ...). It is empty when there are no
 * applications.
 */
public record StudentApplicationsResponse(boolean success, Data data) {

    public record Data(
            long studentId,
            String date,
            int totalApplications,
            int pageSize,
            int totalPages,
            Map<Integer, List<StudentApplicationDto>> pages) {
    }

    public static StudentApplicationsResponse of(Data data) {
        return new StudentApplicationsResponse(true, data);
    }
}
