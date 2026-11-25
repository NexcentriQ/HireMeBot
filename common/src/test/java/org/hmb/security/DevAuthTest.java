package org.hmb.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevAuthTest {

    @Test
    void theRecruiterHeaderNeedsItsFlagAndSamLocal() {
        assertTrue(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "true")));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true")));
        assertFalse(DevAuth.permitted(Map.of("AWS_SAM_LOCAL", "true")));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "false", "AWS_SAM_LOCAL", "true")));
        assertFalse(DevAuth.permitted(Map.of()));
    }

    @Test
    void theStudentHeaderNeedsItsOwnFlagAndSamLocal() {
        assertTrue(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "true", "AWS_SAM_LOCAL", "TRUE"),
                DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "true"), DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("AWS_SAM_LOCAL", "true"), DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "yes", "AWS_SAM_LOCAL", "true"),
                DevAuth.STUDENT_FLAG_VAR));
    }

    @Test
    void oneHeadersFlagNeverEnablesTheOther() {
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_RECRUITER_HEADER", "true", "AWS_SAM_LOCAL", "true"),
                DevAuth.STUDENT_FLAG_VAR));
        assertFalse(DevAuth.permitted(Map.of("ALLOW_DEV_STUDENT_HEADER", "true", "AWS_SAM_LOCAL", "true")));
    }
}
