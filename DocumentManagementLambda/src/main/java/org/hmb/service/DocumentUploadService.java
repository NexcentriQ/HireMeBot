package org.hmb.service;

import org.hmb.config.DocumentUploadConfig;
import org.hmb.config.DocumentUploadConfig.TypePolicy;
import org.hmb.dto.DocumentType;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.RegisterResponse;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.dto.UploadUrlResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.RecruiterAccessRepository;
import org.hmb.repository.StoredDocument;
import org.hmb.security.UploadIntent;
import org.hmb.security.UploadTokenService;
import org.hmb.storage.FileFormat;
import org.hmb.storage.ObjectMissingException;
import org.hmb.storage.S3DocumentStorageService;
import org.hmb.storage.S3DocumentStorageService.PresignedUpload;
import org.hmb.storage.S3DocumentStorageService.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The two-step document upload: {@link #requestUploadUrl} (duplicate check + presigned PUT, no writes) and
 * {@link #registerDocument} (verify the uploaded object, then insert the metadata row).
 *
 * <p><b>Consistency between S3 and MySQL.</b> MySQL is the source of truth; the S3 {@code doc-state} tag is only a
 * derived marker. Registration is {@code verify -> INSERT -> tag registered}. If anything fails after the insert, the
 * request still succeeds (the row is authoritative) and the tag is completed by a client retry (the replay path
 * below) or by the scheduled reconciler. If it fails before the insert, nothing is recorded and the unregistered
 * object simply expires. No code here deletes an S3 object or updates or deletes a row.
 *
 * <p>Authorization on both calls: the caller is an active recruiter, assigned to the student. The recruiter id is the
 * authenticated identity passed in by the handler, never a field of the request.
 */
public class DocumentUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentUploadService.class);

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /** Result of {@link #registerDocument}: {@code created} is true only if this call inserted the row (HTTP 201). */
    public record RegisterOutcome(RegisterResponse body, boolean created) {
    }

    /** The request after validation: normalized, never null. */
    private record ValidUpload(long studentId, DocumentType type, FileFormat format, long size, String checksum) {
    }

    private record ValidRegister(long studentId, DocumentType type, String objectKey, String checksum, String token) {
    }

    private final DataSource dataSource;
    private final DocumentUploadConfig config;
    private final DocumentRepository documents;
    private final RecruiterAccessRepository access;
    private final S3DocumentStorageService storage;
    private final UploadTokenService tokens;
    private final Clock clock;

    public DocumentUploadService(DataSource dataSource, DocumentUploadConfig config, DocumentRepository documents,
                                 RecruiterAccessRepository access, S3DocumentStorageService storage,
                                 UploadTokenService tokens, Clock clock) {
        this.dataSource = dataSource;
        this.config = config;
        this.documents = documents;
        this.access = access;
        this.storage = storage;
        this.tokens = tokens;
        this.clock = clock;
    }

    // ---- step 1: duplicate check and presigned URL ------------------------------------------------------------

    /**
     * @throws ApiException 400 invalid request or disabled type, 403 not an active recruiter or not assigned,
     *                      409 duplicate or unavailable document, 503/500 infrastructure
     */
    public UploadUrlResponse requestUploadUrl(UploadUrlRequest request, long recruiterId) {
        ValidUpload valid = validate(request);

        StoredDocument existing;
        try (Connection connection = dataSource.getConnection()) {
            authorize(connection, recruiterId, valid.studentId());
            existing = documents.findByChecksum(connection, valid.checksum()).orElse(null);
        } catch (SQLException e) {
            throw FailureMapper.fromSql(e);
        }

        if (existing != null) {
            if (!ownedBy(existing, valid.studentId(), valid.type())) {
                // Another student's file, or the same bytes under another type: generic, reveals nothing about it.
                throw duplicate();
            }
            // Same student, same type: reuse it, but only if the file is really still there.
            Optional<StoredObject> object = s3(() -> storage.head(existing.objectKey()));
            if (object.isEmpty()) {
                LOG.error("Registered document {} has no S3 object", existing.objectId());
                throw unavailable();
            }
            return UploadUrlResponse.alreadyExists(existing.objectId());
        }

        // The key is always chosen here; a client can never influence it.
        String key = "students/%d/%s/%s.%s".formatted(valid.studentId(), valid.type().keySegment(),
                UUID.randomUUID(), valid.format().extension());
        String contentType = valid.format().mimeType();
        PresignedUpload presigned = s3(() -> storage.presignUpload(key, contentType, valid.size(), valid.checksum(),
                Duration.ofSeconds(config.urlExpirySeconds())));
        String token = tokens.sign(new UploadIntent(recruiterId, valid.studentId(), valid.type(), key,
                valid.checksum(), valid.size(), contentType,
                clock.instant().getEpochSecond() + config.tokenTtlSeconds()));

        LOG.info("Issued an upload URL for student {} ({})", valid.studentId(), valid.type());
        return UploadUrlResponse.uploadRequired(key, presigned.url(), token, config.urlExpirySeconds(),
                presigned.requiredHeaders());
    }

    // ---- step 2: verify and register --------------------------------------------------------------------------

    /**
     * @throws ApiException 400 invalid request / expired token / missing or mismatching upload, 403 not authorized
     *                      or invalid token, 409 duplicate or unavailable document, 503/500 infrastructure
     */
    public RegisterOutcome registerDocument(RegisterRequest request, long recruiterId) {
        ValidRegister valid = validate(request);
        TypePolicy policy = enabledPolicy(valid.type());

        UploadIntent intent;
        StoredDocument known;
        try (Connection connection = dataSource.getConnection()) {
            authorize(connection, recruiterId, valid.studentId());
            intent = tokens.verify(valid.token());
            checkClaims(intent, recruiterId, valid);
            checkKey(valid, policy);
            known = findKnown(connection, valid.objectKey(), valid.checksum());
        } catch (SQLException e) {
            throw FailureMapper.fromSql(e);
        }

        // Already registered (a retry, a lost response, or the same bytes already stored for this student).
        if (known != null) {
            return replay(known, valid);
        }

        verifyUploadedObject(intent);

        long objectId;
        try (Connection connection = dataSource.getConnection()) {
            objectId = documents.insert(connection, valid.studentId(), valid.type(), valid.objectKey(), valid.checksum());
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() != MYSQL_DUPLICATE_ENTRY) {
                throw FailureMapper.fromSql(e);
            }
            // Lost a race (or a concurrent retry). The pre-check was not atomic with the insert, so decide by re-reading.
            return resolveDuplicate(valid);
        } catch (SQLException e) {
            throw FailureMapper.fromSql(e);
        }

        // The row now exists, so the request has succeeded whatever happens to the tag below.
        markRegisteredTolerantly(valid.objectKey(), objectId);
        LOG.info("Registered document {} for student {}", objectId, valid.studentId());
        return new RegisterOutcome(RegisterResponse.success(objectId, valid.studentId(), valid.type()), true);
    }

    // ---- registration helpers ---------------------------------------------------------------------------------

    private StoredDocument findKnown(Connection connection, String objectKey, String checksum) throws SQLException {
        Optional<StoredDocument> byKey = documents.findByObjectKey(connection, objectKey);
        return byKey.isPresent() ? byKey.get() : documents.findByChecksum(connection, checksum).orElse(null);
    }

    /** A row already matches this key or checksum: success if it is this student's same type, otherwise a conflict. */
    private RegisterOutcome replay(StoredDocument known, ValidRegister valid) {
        if (!ownedBy(known, valid.studentId(), valid.type())) {
            throw duplicate();
        }
        // Complete the tag on the registered object (a no-op if already done); a missing file is a real problem.
        try {
            s3(() -> {
                storage.markRegistered(known.objectKey());
                return null;
            });
        } catch (ObjectMissingException e) {
            LOG.error("Registered document {} has no S3 object", known.objectId());
            throw unavailable();
        } catch (ApiException e) {
            // S3 itself is unavailable: the row is authoritative, the reconciler will finish the tag.
            LOG.warn("Could not confirm the registered tag for document {}", known.objectId());
        }
        return new RegisterOutcome(RegisterResponse.success(known.objectId(), valid.studentId(), valid.type()), false);
    }

    private RegisterOutcome resolveDuplicate(ValidRegister valid) {
        StoredDocument known;
        try (Connection connection = dataSource.getConnection()) {
            known = findKnown(connection, valid.objectKey(), valid.checksum());
        } catch (SQLException e) {
            throw FailureMapper.fromSql(e);
        }
        if (known == null) {
            LOG.error("Duplicate-key error but no matching row was found on re-read");
            throw ApiException.internal(null);
        }
        return replay(known, valid);
    }

    /** Verifies what S3 holds against what was authorized. Never trusts the checksum in the request body. */
    private void verifyUploadedObject(UploadIntent intent) {
        StoredObject object = s3(() -> storage.head(intent.objectKey()))
                .orElseThrow(() -> new ApiException(400, "UPLOAD_NOT_FOUND",
                        "The uploaded file was not found. Upload it again."));

        // S3 validated the signed SHA-256 against the received bytes at upload time and stored it with the object.
        String expectedChecksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(intent.checksum()));
        boolean matches = object.size() == intent.size()
                && sameContentType(object.contentType(), intent.contentType())
                && expectedChecksum.equals(object.checksumSha256Base64());
        if (!matches) {
            LOG.warn("Uploaded object does not match its authorization (student {})", intent.studentId());
            throw new ApiException(400, "UPLOAD_VERIFICATION_FAILED",
                    "The uploaded file does not match what was authorized. Upload it again.");
        }

        FileFormat format = FileFormat.forExtension(FileFormat.extensionOf(intent.objectKey()))
                .orElseThrow(() -> new ApiException(400, "INVALID_FILE_FORMAT", "Unsupported file type."));
        byte[] head = s3(() -> storage.readHead(intent.objectKey(), FileFormat.HEAD_BYTES));
        byte[] tail = s3(() -> storage.readTail(intent.objectKey(), format.tailBytes()));
        if (!format.matches(head, tail)) {
            LOG.warn("Uploaded object failed the {} format check (student {})", format, intent.studentId());
            throw new ApiException(400, "INVALID_FILE_FORMAT",
                    "The file does not look like a valid " + format.extension().toUpperCase(Locale.ROOT) + " document.");
        }
    }

    private void markRegisteredTolerantly(String objectKey, long objectId) {
        try {
            s3(() -> {
                storage.markRegistered(objectKey);
                return null;
            });
        } catch (RuntimeException e) {
            // Do not fail the request: the row exists. A retry or the reconciler completes the tag.
            LOG.warn("Registered document {} but could not set its registered tag; it will be reconciled", objectId);
        }
    }

    // ---- authorization and claim checks -----------------------------------------------------------------------

    private void authorize(Connection connection, long recruiterId, long studentId) throws SQLException {
        if (!access.isActiveRecruiter(connection, recruiterId)) {
            throw ApiException.forbidden("Recruiter does not exist or is not active");
        }
        if (!access.isActiveAssignment(connection, recruiterId, studentId)) {
            throw ApiException.forbidden("Recruiter is not assigned to this student");
        }
    }

    private void checkClaims(UploadIntent intent, long recruiterId, ValidRegister valid) {
        boolean bound = intent.recruiterId() == recruiterId
                && intent.studentId() == valid.studentId()
                && intent.documentType() == valid.type()
                && intent.objectKey().equals(valid.objectKey())
                && intent.checksum().equals(valid.checksum());
        if (!bound) {
            throw new ApiException(403, "UPLOAD_TOKEN_INVALID",
                    "Upload authorization is not valid. Request a new upload URL.");
        }
    }

    /** The key must be exactly what this server would have generated for this student and type. */
    private void checkKey(ValidRegister valid, TypePolicy policy) {
        String prefix = "students/%d/%s/".formatted(valid.studentId(), valid.type().keySegment());
        Pattern shape = Pattern.compile(Pattern.quote(prefix) + UUID_PATTERN + "\\.(pdf|docx)");
        boolean ok = shape.matcher(valid.objectKey()).matches()
                && policy.formatForFileName(valid.objectKey()).isPresent();
        if (!ok) {
            throw new ApiException(403, "UPLOAD_TOKEN_INVALID",
                    "Upload authorization is not valid. Request a new upload URL.");
        }
    }

    // ---- validation -------------------------------------------------------------------------------------------

    private ValidUpload validate(UploadUrlRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Request body is required");
        }
        List<String> errors = new ArrayList<>();
        requirePositive(request.studentId(), "studentId", errors);
        DocumentType type = parseType(request.documentType(), errors);
        if (type != null) {
            TypePolicy policy = enabledPolicy(type);
            FileFormat format = null;
            String fileName = request.fileName();
            if (fileName == null || fileName.isBlank()) {
                errors.add("fileName is required");
            } else if (fileName.length() > 255) {
                errors.add("fileName must be at most 255 characters");
            } else {
                format = policy.formatForFileName(fileName).orElse(null);
                if (format == null) {
                    errors.add("fileName must end with one of: " + policy.allowedExtensions());
                }
            }
            String contentType = request.contentType();
            if (contentType == null || contentType.isBlank()) {
                errors.add("contentType is required");
            } else if (format != null && !sameContentType(contentType, format.mimeType())) {
                errors.add("contentType must be " + format.mimeType() + " for a ." + format.extension() + " file");
            }
            Long size = request.fileSize();
            if (size == null) {
                errors.add("fileSize is required");
            } else if (size < 1 || size > policy.maxSizeBytes()) {
                errors.add("fileSize must be between 1 and " + policy.maxSizeBytes() + " bytes");
            }
            String checksum = checkChecksum(request.checksum(), errors);
            if (errors.isEmpty()) {
                return new ValidUpload(request.studentId(), type, format, size, checksum);
            }
        }
        throw ApiException.validation(errors);
    }

    private ValidRegister validate(RegisterRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Request body is required");
        }
        List<String> errors = new ArrayList<>();
        requirePositive(request.studentId(), "studentId", errors);
        DocumentType type = parseType(request.documentType(), errors);
        String key = request.objectKey();
        if (key == null || key.isBlank()) {
            errors.add("objectKey is required");
        } else if (key.length() > 512) {
            errors.add("objectKey must be at most 512 characters");
        }
        String checksum = checkChecksum(request.checksum(), errors);
        if (request.uploadToken() == null || request.uploadToken().isBlank()) {
            errors.add("uploadToken is required");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new ValidRegister(request.studentId(), type, key.trim(), checksum, request.uploadToken().trim());
    }

    private TypePolicy enabledPolicy(DocumentType type) {
        TypePolicy policy = config.policy(type);
        if (!policy.enabled()) {
            throw new ApiException(400, "DOCUMENT_TYPE_NOT_ENABLED", "Document type " + type.name() + " is not enabled");
        }
        return policy;
    }

    private static DocumentType parseType(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("documentType is required");
            return null;
        }
        Optional<DocumentType> type = DocumentType.parse(value);
        if (type.isEmpty()) {
            errors.add("documentType must be one of: RESUME, COVER_LETTER");
            return null;
        }
        return type.get();
    }

    private static void requirePositive(Long value, String field, List<String> errors) {
        if (value == null) {
            errors.add(field + " is required");
        } else if (value <= 0) {
            errors.add(field + " must be a positive number");
        }
    }

    /** @return the checksum normalized to lowercase, or null after recording an error */
    private static String checkChecksum(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("checksum is required");
            return null;
        }
        String trimmed = value.trim();
        if (!SHA256_HEX.matcher(trimmed).matches()) {
            errors.add("checksum must be a SHA-256 value: 64 hexadecimal characters");
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    // ---- small helpers ----------------------------------------------------------------------------------------

    private static boolean ownedBy(StoredDocument document, long studentId, DocumentType type) {
        return document.studentId() == studentId && document.documentType() == type;
    }

    /** Compares media types ignoring case and any parameters such as charset. */
    private static boolean sameContentType(String a, String b) {
        return bareType(a).equals(bareType(b));
    }

    private static String bareType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException duplicate() {
        return new ApiException(409, "DUPLICATE_DOCUMENT", "This document is already registered.");
    }

    private static ApiException unavailable() {
        return new ApiException(409, "DOCUMENT_UNAVAILABLE",
                "This document is registered but its file is currently unavailable. Contact support.");
    }

    /** Runs an S3 call, turning SDK failures into a retryable 503 or a generic 500. */
    private static <T> T s3(Supplier<T> call) {
        try {
            return call.get();
        } catch (SdkException e) {
            throw FailureMapper.fromS3(e);
        }
    }
}
