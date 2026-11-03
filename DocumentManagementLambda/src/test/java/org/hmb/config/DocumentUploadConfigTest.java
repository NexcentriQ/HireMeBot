package org.hmb.config;

import org.hmb.dto.DocumentType;
import org.hmb.storage.FileFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentUploadConfigTest {

    private static DocumentUploadConfig config(Map<String, String> env) {
        return DocumentUploadConfig.fromEnv(env);
    }

    @Test
    void defaultsEnableResumeAndKeepCoverLetterDisabled() {
        DocumentUploadConfig config = config(Map.of());

        assertTrue(config.policy(DocumentType.RESUME).enabled());
        assertFalse(config.policy(DocumentType.COVER_LETTER).enabled(), "cover letters are defined but disabled in V1");
        assertEquals(5L * 1024 * 1024, config.policy(DocumentType.RESUME).maxSizeBytes());
        assertEquals(300, config.urlExpirySeconds());
        assertEquals(1800, config.tokenTtlSeconds());
        assertEquals(5, config.reconcileLookbackDays());
        assertEquals(500, config.reconcileBatchSize());
    }

    @Test
    void flatEnvironmentVariablesOverrideTheDefaults() {
        DocumentUploadConfig config = config(Map.of(
                "DOCUMENT_RESUME_MAX_SIZE_MB", "2",
                "DOCUMENT_COVER_LETTER_ENABLED", "true",
                "UPLOAD_URL_EXPIRY_SECONDS", "120",
                "UPLOAD_TOKEN_TTL_SECONDS", "600"));

        assertEquals(2L * 1024 * 1024, config.policy(DocumentType.RESUME).maxSizeBytes());
        assertTrue(config.policy(DocumentType.COVER_LETTER).enabled());
        assertEquals(120, config.urlExpirySeconds());
        assertEquals(600, config.tokenTtlSeconds());
    }

    @Test
    void theAllowedFormatsAreCentrallyDefinedPdfAndDocx() {
        DocumentUploadConfig.TypePolicy policy = config(Map.of()).policy(DocumentType.RESUME);

        assertEquals(Optional.of(FileFormat.PDF), policy.formatForFileName("Akhil_Resume.pdf"));
        assertEquals(Optional.of(FileFormat.DOCX), policy.formatForFileName("cv.DOCX"));
        assertEquals(Optional.empty(), policy.formatForFileName("virus.exe"));
        assertEquals(Optional.empty(), policy.formatForFileName("noextension"));
        assertEquals("pdf, docx", policy.allowedExtensions());
    }

    @Test
    void theBucketIsRequiredOnlyWhenUsed() {
        assertEquals("my-bucket", config(Map.of("DOCUMENTS_BUCKET", " my-bucket ")).bucket());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> config(Map.of()).bucket());
        assertTrue(e.getMessage().contains("DOCUMENTS_BUCKET"));
    }

    @Test
    void invalidValuesFailFastNamingTheVariable() {
        for (Map<String, String> bad : java.util.List.of(
                Map.of("DOCUMENT_RESUME_MAX_SIZE_MB", "0"),
                Map.of("DOCUMENT_RESUME_MAX_SIZE_MB", "999"),
                Map.of("UPLOAD_URL_EXPIRY_SECONDS", "abc"),
                Map.of("UPLOAD_URL_EXPIRY_SECONDS", "99999"),
                Map.of("UPLOAD_TOKEN_TTL_SECONDS", "5"),
                Map.of("DOCUMENT_COVER_LETTER_ENABLED", "yes"),
                Map.of("RECONCILE_LOOKBACK_DAYS", "7"),        // must stay below the 7-day pending expiry
                Map.of("RECONCILE_BATCH_SIZE", "0"))) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> config(bad),
                    "should reject " + bad);
            assertTrue(e.getMessage().contains(bad.keySet().iterator().next()));
        }
    }

    @Test
    void anInvalidValueIsNeverEchoedInTheErrorMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config(Map.of("UPLOAD_URL_EXPIRY_SECONDS", "hunter2-not-a-number")));

        assertFalse(e.getMessage().contains("hunter2"));
    }
}
