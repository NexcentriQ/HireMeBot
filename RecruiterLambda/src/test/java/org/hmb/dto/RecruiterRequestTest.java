package org.hmb.dto;

import org.hmb.TestData;
import org.hmb.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruiterRequestTest {

    private static void assertRejected(RecruiterRequest request, String detailFragment) {
        ApiException e = assertThrows(ApiException.class, request::validated);
        assertEquals(400, e.getStatusCode());
        assertTrue(e.getDetails().stream().anyMatch(d -> d.contains(detailFragment)),
                "expected a detail mentioning '" + detailFragment + "' but got " + e.getDetails());
    }

    @Test
    void validRequestPasses() {
        assertEquals(TestData.STUDENT_ID, TestData.validRequest().validated().studentId());
    }

    @Test
    void missingStudentIdIsRejected() {
        RecruiterRequest base = TestData.validRequest();
        assertRejected(new RecruiterRequest(null, base.application(), base.documents(), base.questions()),
                "studentId is required");
    }

    @Test
    void missingJobTitleIsRejected() {
        var app = new RecruiterRequest.Application(null, "Amazon", "Austin, TX", "https://a.com/1", "Src");
        assertRejected(TestData.withApplication(app), "application.jobTitle is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://amazon.jobs/1"})
    void invalidJobLinkIsRejected(String link) {
        var app = new RecruiterRequest.Application("T", "C", "L", link, "S");
        assertRejected(TestData.withApplication(app), "application.jobLink");
    }
}
