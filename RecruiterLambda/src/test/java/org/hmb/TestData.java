package org.hmb;

import org.hmb.dto.RecruiterRequest;

import java.util.List;

/** Shared fixtures for tests. */
public final class TestData {

    public static final long RECRUITER_ID = 1000000001L;
    public static final long STUDENT_ID = 1000000002L;
    public static final long RESUME_ID = 1000000011L;
    public static final long COVER_LETTER_ID = 1000000012L;

    private TestData() {
    }

    public static RecruiterRequest.Application application() {
        return new RecruiterRequest.Application(
                "Senior Java Backend Developer", "Amazon", "Austin, TX",
                "https://www.amazon.jobs/en/jobs/1234567", "Amazon Careers");
    }

    public static RecruiterRequest validRequest() {
        return new RecruiterRequest(
                STUDENT_ID,
                application(),
                new RecruiterRequest.Documents(RESUME_ID, COVER_LETTER_ID),
                List.of(new RecruiterRequest.Question("Are you willing to relocate?", "Yes")));
    }

    public static RecruiterRequest withApplication(RecruiterRequest.Application application) {
        RecruiterRequest base = validRequest();
        return new RecruiterRequest(base.studentId(), application, base.documents(), base.questions());
    }

    /** The full example payload from the API specification. */
    public static final String VALID_JSON = """
            {
              "studentId": 1000000002,
              "application": {
                "jobTitle": "Senior Java Backend Developer",
                "companyName": "Amazon",
                "jobLocation": "Austin, TX",
                "jobLink": "https://www.amazon.jobs/en/jobs/1234567",
                "jobSource": "Amazon Careers"
              },
              "documents": {
                "resumeObjectId": 1000000011,
                "coverLetterObjectId": 1000000012
              },
              "questions": [
                {"question": "Are you legally authorized to work in the United States?", "answer": "Yes"},
                {"question": "Will you now or in the future require employment visa sponsorship?", "answer": "Yes"},
                {"question": "How many years of Java experience do you have?", "answer": "3 years"},
                {"question": "Are you willing to relocate?", "answer": "Yes"},
                {"question": "What is your expected salary?", "answer": "$110,000 annually"}
              ]
            }
            """;
}
