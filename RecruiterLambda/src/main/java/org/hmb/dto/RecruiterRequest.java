package org.hmb.dto;

import org.hmb.exception.ApiException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Request body for {@code POST /api/v1/recruiter/applications}.
 *
 * <p>The recruiter id is intentionally absent: it comes from the (temporary) dev header, never the body.
 * Unknown JSON properties are rejected by the handler's ObjectMapper, which also covers a smuggled
 * {@code recruiterId}.
 *
 * <p>Call {@link #validated()} before using the data; it returns a trimmed copy or throws a 400.
 */
public record RecruiterRequest(
        Long studentId,
        Application application,
        Documents documents,
        List<Question> questions) {

    static final int MAX_JOB_TITLE = 255;
    static final int MAX_COMPANY_NAME = 255;
    static final int MAX_JOB_LOCATION = 255;
    static final int MAX_JOB_LINK = 1000;
    static final int MAX_JOB_SOURCE = 100;
    static final int MAX_QUESTIONS = 50;
    static final int MAX_QUESTION_TEXT = 5000;

    public record Application(String jobTitle, String companyName, String jobLocation, String jobLink, String jobSource) {
    }

    /** {@code coverLetterObjectId} is optional (the column is nullable). */
    public record Documents(Long resumeObjectId, Long coverLetterObjectId) {
    }

    public record Question(String question, String answer) {
    }

    /**
     * Validates all fields and returns a copy with user-provided strings trimmed.
     *
     * @throws ApiException 400 listing every problem found
     */
    public RecruiterRequest validated() {
        List<String> errors = new ArrayList<>();

        requirePositive(studentId, "studentId", errors);

        Application cleanApplication = null;
        if (application == null) {
            errors.add("application is required");
        } else {
            String jobTitle = requireText(application.jobTitle(), "application.jobTitle", MAX_JOB_TITLE, errors);
            String companyName = requireText(application.companyName(), "application.companyName", MAX_COMPANY_NAME, errors);
            String jobLocation = requireText(application.jobLocation(), "application.jobLocation", MAX_JOB_LOCATION, errors);
            String jobLink = requireText(application.jobLink(), "application.jobLink", MAX_JOB_LINK, errors);
            String jobSource = requireText(application.jobSource(), "application.jobSource", MAX_JOB_SOURCE, errors);
            if (jobLink != null && jobLink.length() <= MAX_JOB_LINK && !isHttpUrl(jobLink)) {
                errors.add("application.jobLink must be an absolute http or https URL");
            }
            cleanApplication = new Application(jobTitle, companyName, jobLocation, jobLink, jobSource);
        }

        if (documents == null) {
            errors.add("documents is required");
        } else {
            requirePositive(documents.resumeObjectId(), "documents.resumeObjectId", errors);
            if (documents.coverLetterObjectId() != null && documents.coverLetterObjectId() <= 0) {
                errors.add("documents.coverLetterObjectId must be a positive number when provided");
            }
        }

        List<Question> cleanQuestions = new ArrayList<>();
        if (questions != null) {
            if (questions.size() > MAX_QUESTIONS) {
                errors.add("questions must contain at most " + MAX_QUESTIONS + " entries");
            } else {
                for (int i = 0; i < questions.size(); i++) {
                    Question q = questions.get(i);
                    if (q == null) {
                        errors.add("questions[" + i + "] must not be null");
                        continue;
                    }
                    String question = requireText(q.question(), "questions[" + i + "].question", MAX_QUESTION_TEXT, errors);
                    String answer = requireText(q.answer(), "questions[" + i + "].answer", MAX_QUESTION_TEXT, errors);
                    cleanQuestions.add(new Question(question, answer));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new RecruiterRequest(studentId, cleanApplication, documents, List.copyOf(cleanQuestions));
    }

    private static void requirePositive(Long value, String field, List<String> errors) {
        if (value == null) {
            errors.add(field + " is required");
        } else if (value <= 0) {
            errors.add(field + " must be a positive number");
        }
    }

    /** Returns the trimmed value, or null (after recording an error) if missing, blank or too long. */
    private static String requireText(String value, String field, int maxLength, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " is required");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            errors.add(field + " must be at most " + maxLength + " characters");
            return null;
        }
        return trimmed;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && scheme != null
                    && (scheme.toLowerCase(Locale.ROOT).equals("http") || scheme.toLowerCase(Locale.ROOT).equals("https"))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
