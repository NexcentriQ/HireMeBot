package org.hmb.config;

import org.hmb.dto.DocumentType;
import org.hmb.storage.FileFormat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All document-upload policy in one place.
 *
 * <p>Lambda environment variables are flat strings, so nested YAML is not used: each setting has a safe default in
 * code and an optional flat override. Which formats a type accepts, and their MIME types, are fixed in code
 * ({@link FileFormat}); the knobs that can sensibly change per deployment are overridable:
 * <pre>
 * DOCUMENTS_BUCKET                    required to upload (the private documents bucket)
 * UPLOAD_URL_EXPIRY_SECONDS           presigned PUT lifetime            (default 300, 1..3600)
 * UPLOAD_TOKEN_TTL_SECONDS            upload-intent token lifetime      (default 1800, 60..86400)
 * DOCUMENT_RESUME_ENABLED             (default true)
 * DOCUMENT_RESUME_MAX_SIZE_MB         (default 5, 1..50)
 * DOCUMENT_COVER_LETTER_ENABLED       (default false: defined but not enabled in V1)
 * DOCUMENT_COVER_LETTER_MAX_SIZE_MB   (default 5, 1..50)
 * RECONCILE_LOOKBACK_DAYS             (default 5, 1..6; must stay below the 7-day pending-object expiry)
 * RECONCILE_BATCH_SIZE                (default 500, 1..1000)
 * </pre>
 * Invalid values fail fast with an {@link IllegalStateException} whose message names the variable but never a value.
 */
public final class DocumentUploadConfig {

    public static final String BUCKET_VAR = "DOCUMENTS_BUCKET";

    /** What one document type accepts. */
    public record TypePolicy(DocumentType type, boolean enabled, long maxSizeBytes, List<FileFormat> allowedFormats) {

        /** The format a file name's extension selects, if that format is allowed for this type. */
        public Optional<FileFormat> formatForFileName(String fileName) {
            return FileFormat.forExtension(FileFormat.extensionOf(fileName)).filter(allowedFormats::contains);
        }

        /** Comma-separated allowed extensions, for error messages. */
        public String allowedExtensions() {
            return String.join(", ", allowedFormats.stream().map(FileFormat::extension).toList());
        }
    }

    private final String bucket;
    private final int urlExpirySeconds;
    private final int tokenTtlSeconds;
    private final Map<DocumentType, TypePolicy> policies;
    private final int reconcileLookbackDays;
    private final int reconcileBatchSize;

    private DocumentUploadConfig(String bucket, int urlExpirySeconds, int tokenTtlSeconds,
                                 Map<DocumentType, TypePolicy> policies, int reconcileLookbackDays,
                                 int reconcileBatchSize) {
        this.bucket = bucket;
        this.urlExpirySeconds = urlExpirySeconds;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.policies = policies;
        this.reconcileLookbackDays = reconcileLookbackDays;
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public static DocumentUploadConfig fromEnv(Map<String, String> env) {
        Map<DocumentType, TypePolicy> policies = new EnumMap<>(DocumentType.class);
        policies.put(DocumentType.RESUME, policy(env, DocumentType.RESUME, "DOCUMENT_RESUME", true));
        policies.put(DocumentType.COVER_LETTER, policy(env, DocumentType.COVER_LETTER, "DOCUMENT_COVER_LETTER", false));
        return new DocumentUploadConfig(
                blankToNull(env.get(BUCKET_VAR)),
                intVar(env, "UPLOAD_URL_EXPIRY_SECONDS", 300, 1, 3600),
                intVar(env, "UPLOAD_TOKEN_TTL_SECONDS", 1800, 60, 86_400),
                policies,
                intVar(env, "RECONCILE_LOOKBACK_DAYS", 5, 1, 6),
                intVar(env, "RECONCILE_BATCH_SIZE", 500, 1, 1000));
    }

    private static TypePolicy policy(Map<String, String> env, DocumentType type, String prefix, boolean enabledByDefault) {
        boolean enabled = boolVar(env, prefix + "_ENABLED", enabledByDefault);
        int maxMb = intVar(env, prefix + "_MAX_SIZE_MB", 5, 1, 50);
        return new TypePolicy(type, enabled, maxMb * 1024L * 1024L, List.of(FileFormat.PDF, FileFormat.DOCX));
    }

    /** @throws IllegalStateException if {@code DOCUMENTS_BUCKET} is not set */
    public String bucket() {
        if (bucket == null) {
            throw new IllegalStateException("Missing required environment variable " + BUCKET_VAR);
        }
        return bucket;
    }

    public int urlExpirySeconds() {
        return urlExpirySeconds;
    }

    public int tokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public TypePolicy policy(DocumentType type) {
        return policies.get(type);
    }

    public int reconcileLookbackDays() {
        return reconcileLookbackDays;
    }

    public int reconcileBatchSize() {
        return reconcileBatchSize;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean boolVar(Map<String, String> env, String name, boolean defaultValue) {
        String value = blankToNull(env.get(name));
        if (value == null) {
            return defaultValue;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalStateException(name + " must be true or false");
    }

    private static int intVar(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String value = blankToNull(env.get(name));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer between " + min + " and " + max);
        }
    }
}
