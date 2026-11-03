package org.hmb.dto;

import java.util.List;

/**
 * Success body for {@code GET /api/v1/recruiter/students}.
 *
 * <p>{@code students} is never null: a recruiter with no active assigned students gets an empty list.
 */
public record AssignedStudentsResponse(boolean success, Data data) {

    /**
     * @param recruiterId   the recruiter making the request
     * @param totalStudents the number of entries in {@code students}
     * @param students      active assigned students, alphabetical by first name, then last name, then id
     */
    public record Data(long recruiterId, int totalStudents, List<AssignedStudentDto> students) {
    }

    public static AssignedStudentsResponse of(long recruiterId, List<AssignedStudentDto> students) {
        return new AssignedStudentsResponse(true, new Data(recruiterId, students.size(), List.copyOf(students)));
    }
}
