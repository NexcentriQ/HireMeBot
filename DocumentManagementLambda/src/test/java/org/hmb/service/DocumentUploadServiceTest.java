package org.hmb.service;

import org.hmb.Fixtures;
import org.hmb.config.DocumentUploadConfig;
import org.hmb.dto.DocumentType;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.dto.UploadUrlResponse;
import org.hmb.exception.ApiException;
import org.hmb.repository.DocumentRepository;
import org.hmb.repository.RecruiterAccessRepository;
import org.hmb.repository.StoredDocument;
import org.hmb.security.SigningSecretProvider;
import org.hmb.security.UploadIntent;
import org.hmb.security.UploadTokenService;
import org.hmb.service.DocumentUploadService.RegisterOutcome;
import org.hmb.storage.ObjectMissingException;
import org.hmb.storage.S3DocumentStorageService;
import org.hmb.storage.S3DocumentStorageService.PresignedUpload;
import org.hmb.storage.S3DocumentStorageService.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import software.amazon.awssdk.core.exception.SdkClientException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentUploadServiceTest {

    private static final long NEW_OBJECT_ID = 1000000010L;
    private static final long EXISTING_OBJECT_ID = 1000000001L;
    private static final String KEY = Fixtures.resumeKey(Fixtures.STUDENT_ID);
    private static final Pattern GENERATED_KEY = Pattern.compile(
            "students/" + Fixtures.STUDENT_ID + "/resumes/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.pdf");

    @Mock DataSource dataSource;
    @Mock Connection connection;
    @Mock DocumentRepository documents;
    @Mock RecruiterAccessRepository access;
    @Mock S3DocumentStorageService storage;

    private final Clock clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC);
    private final UploadTokenService tokens =
            new UploadTokenService(new SigningSecretProvider.FixedProvider(Fixtures.SECRET), clock);

    private DocumentUploadService service;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(access.isActiveRecruiter(connection, Fixtures.RECRUITER_ID)).thenReturn(true);
        when(access.isActiveAssignment(connection, Fixtures.RECRUITER_ID, Fixtures.STUDENT_ID)).thenReturn(true);
        service = new DocumentUploadService(dataSource, DocumentUploadConfig.fromEnv(Map.of()), documents, access,
                storage, tokens, clock);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static ApiException assertApi(int status, String code, Executable call) {
        ApiException e = assertThrows(ApiException.class, call);
        assertEquals(status, e.getStatusCode(), e.getMessage());
        assertEquals(code, e.getErrorCode());
        return e;
    }

    private static UploadUrlRequest uploadRequest() {
        return new UploadUrlRequest(Fixtures.STUDENT_ID, "RESUME", "Akhil_Resume.pdf", Fixtures.PDF_MIME, Fixtures.SIZE,
                Fixtures.SHA256_HEX);
    }

    private static StoredDocument row(long id, long studentId, DocumentType type, String key) {
        return new StoredDocument(id, studentId, type, key, Fixtures.SHA256_HEX);
    }

    private RegisterRequest registerRequest(UploadIntent signedIntent) {
        return new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", KEY, Fixtures.SHA256_HEX, tokens.sign(signedIntent));
    }

    private RegisterRequest registerRequest() {
        return registerRequest(Fixtures.intent());
    }

    /** S3 holds exactly the object that was authorized, and the database accepts the insert. */
    private void stubVerifiedUpload() throws SQLException {
        when(storage.head(KEY)).thenReturn(Optional.of(Fixtures.matchingObject()));
        when(storage.readHead(eq(KEY), anyInt())).thenReturn(Fixtures.pdfHead());
        when(storage.readTail(eq(KEY), anyInt())).thenReturn(Fixtures.pdfTail());
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenReturn(NEW_OBJECT_ID);
    }

    // =================================================================================================================
    // upload-url
    // =================================================================================================================

    @Test
    void aNewChecksumGetsAPresignedUrlAndASignedTokenAndNothingIsWritten() throws SQLException {
        when(storage.presignUpload(anyString(), eq(Fixtures.PDF_MIME), eq(Fixtures.SIZE), eq(Fixtures.SHA256_HEX),
                eq(Duration.ofSeconds(300))))
                .thenReturn(new PresignedUpload("https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc",
                        Map.of("Content-Type", Fixtures.PDF_MIME, "x-amz-checksum-sha256", Fixtures.SHA256_BASE64,
                                "x-amz-tagging", "doc-state=pending")));

        UploadUrlResponse response = service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID);

        assertTrue(response.success());
        assertEquals("UPLOAD_REQUIRED", response.status());
        assertTrue(response.uploadRequired());
        assertTrue(GENERATED_KEY.matcher(response.objectKey()).matches(), response.objectKey());
        assertEquals("https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc", response.uploadUrl());
        assertEquals(300, response.expiresIn());
        assertEquals(Fixtures.PDF_MIME, response.requiredHeaders().get("Content-Type"));
        assertEquals(Fixtures.SHA256_BASE64, response.requiredHeaders().get("x-amz-checksum-sha256"));

        // The token binds every authorized value, and is valid for 30 minutes.
        UploadIntent intent = tokens.verify(response.uploadToken());
        assertEquals(Fixtures.RECRUITER_ID, intent.recruiterId());
        assertEquals(Fixtures.STUDENT_ID, intent.studentId());
        assertEquals(DocumentType.RESUME, intent.documentType());
        assertEquals(response.objectKey(), intent.objectKey());
        assertEquals(Fixtures.SHA256_HEX, intent.checksum());
        assertEquals(Fixtures.SIZE, intent.size());
        assertEquals(Fixtures.PDF_MIME, intent.contentType());
        assertEquals(Fixtures.NOW.getEpochSecond() + 1800, intent.expiresAtEpochSeconds());

        // No MySQL write and no S3 tag change in this call.
        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
        verify(storage, never()).markRegistered(anyString());
    }

    @Test
    void theKeyIsAlwaysChosenByTheServerAndIsNeverTheSameTwice() {
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PresignedUpload("https://x", Map.of()));

        String first = service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID).objectKey();
        String second = service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID).objectKey();

        assertTrue(first.startsWith("students/" + Fixtures.STUDENT_ID + "/resumes/"));
        assertFalse(first.equals(second), "each upload needs its own key");
    }

    @Test
    void aDocxGetsADocxKeyAndTheDocxContentType() {
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PresignedUpload("https://x", Map.of()));
        UploadUrlRequest docx = new UploadUrlRequest(Fixtures.STUDENT_ID, "RESUME", "cv.DOCX",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1000L, Fixtures.SHA256_HEX);

        UploadUrlResponse response = service.requestUploadUrl(docx, Fixtures.RECRUITER_ID);

        assertTrue(response.objectKey().endsWith(".docx"));
        verify(storage).presignUpload(anyString(),
                eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                eq(1000L), anyString(), any());
    }

    @Test
    void anUppercaseChecksumIsNormalizedToLowercaseBeforeTheLookup() throws SQLException {
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PresignedUpload("https://x", Map.of()));
        UploadUrlRequest upper = new UploadUrlRequest(Fixtures.STUDENT_ID, "RESUME", "a.pdf", Fixtures.PDF_MIME,
                Fixtures.SIZE, Fixtures.SHA256_HEX.toUpperCase());

        service.requestUploadUrl(upper, Fixtures.RECRUITER_ID);

        verify(documents).findByChecksum(connection, Fixtures.SHA256_HEX);
    }

    @Test
    void theSameStudentAndTypeAlreadyHavingTheFileReusesItWithoutAnUpload() throws SQLException {
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX))
                .thenReturn(Optional.of(row(EXISTING_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, "students/x/y.pdf")));
        when(storage.head("students/x/y.pdf")).thenReturn(Optional.of(Fixtures.matchingObject()));

        UploadUrlResponse response = service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID);

        assertEquals("ALREADY_EXISTS", response.status());
        assertFalse(response.uploadRequired());
        assertEquals(EXISTING_OBJECT_ID, response.objectId());
        assertEquals(null, response.uploadUrl());
        assertEquals(null, response.uploadToken());
        verify(storage, never()).presignUpload(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void aRegisteredDocumentWhoseFileIsGoneIsReportedUnavailableNotReused() throws SQLException {
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX))
                .thenReturn(Optional.of(row(EXISTING_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, "students/x/y.pdf")));
        when(storage.head("students/x/y.pdf")).thenReturn(Optional.empty());

        assertApi(409, "DOCUMENT_UNAVAILABLE", () -> service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void identicalBytesOwnedByAnotherStudentAreRejectedWithoutRevealingAnything() throws SQLException {
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX)).thenReturn(Optional.of(
                row(EXISTING_OBJECT_ID, Fixtures.OTHER_STUDENT_ID, DocumentType.RESUME,
                        Fixtures.resumeKey(Fixtures.OTHER_STUDENT_ID))));

        ApiException e = assertApi(409, "DUPLICATE_DOCUMENT",
                () -> service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID));

        assertEquals("This document is already registered.", e.getMessage());
        assertFalse(e.getMessage().contains(String.valueOf(Fixtures.OTHER_STUDENT_ID)));
        assertFalse(e.getMessage().contains(String.valueOf(EXISTING_OBJECT_ID)));
        verifyNoInteractions(storage);   // not even a HEAD on the other student's object
    }

    @Test
    void theSameBytesUnderAnotherDocumentTypeAreAlsoADuplicate() throws SQLException {
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX)).thenReturn(Optional.of(
                row(EXISTING_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.COVER_LETTER,
                        "students/5/cover-letters/a.pdf")));

        assertApi(409, "DUPLICATE_DOCUMENT", () -> service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID));
        verifyNoInteractions(storage);
    }

    @Test
    void coverLettersAreDefinedButDisabledInV1() {
        UploadUrlRequest cover = new UploadUrlRequest(Fixtures.STUDENT_ID, "COVER_LETTER", "cl.pdf", Fixtures.PDF_MIME,
                Fixtures.SIZE, Fixtures.SHA256_HEX);

        ApiException e = assertApi(400, "DOCUMENT_TYPE_NOT_ENABLED",
                () -> service.requestUploadUrl(cover, Fixtures.RECRUITER_ID));

        assertTrue(e.getMessage().contains("COVER_LETTER"));
        verifyNoInteractions(dataSource, storage);
    }

    @Test
    void coverLettersCanBeEnabledByConfigurationAlone() {
        service = new DocumentUploadService(dataSource,
                DocumentUploadConfig.fromEnv(Map.of("DOCUMENT_COVER_LETTER_ENABLED", "true")), documents, access,
                storage, tokens, clock);
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PresignedUpload("https://x", Map.of()));
        UploadUrlRequest cover = new UploadUrlRequest(Fixtures.STUDENT_ID, "COVER_LETTER", "cl.pdf", Fixtures.PDF_MIME,
                Fixtures.SIZE, Fixtures.SHA256_HEX);

        UploadUrlResponse response = service.requestUploadUrl(cover, Fixtures.RECRUITER_ID);

        assertTrue(response.objectKey().startsWith("students/" + Fixtures.STUDENT_ID + "/cover-letters/"));
    }

    // ---- upload-url: validation ---------------------------------------------------------------------------------

    private void assertInvalidUpload(UploadUrlRequest request, String detailFragment) {
        ApiException e = assertApi(400, "VALIDATION_ERROR", () -> service.requestUploadUrl(request, Fixtures.RECRUITER_ID));
        assertTrue(e.getDetails().stream().anyMatch(d -> d.contains(detailFragment)),
                "expected a detail containing '" + detailFragment + "' but got " + e.getDetails());
        verifyNoInteractions(dataSource);
    }

    @Test
    void invalidUploadRequestsAreRejectedWithClearDetailsBeforeTouchingAnything() {
        assertInvalidUpload(new UploadUrlRequest(null, "RESUME", "a.pdf", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "studentId is required");
        assertInvalidUpload(new UploadUrlRequest(-1L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "studentId must be a positive number");
        assertInvalidUpload(new UploadUrlRequest(5L, null, "a.pdf", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "documentType is required");
        assertInvalidUpload(new UploadUrlRequest(5L, "PASSPORT", "a.pdf", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "documentType must be one of");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", null, Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "fileName is required");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "virus.exe", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "fileName must end with one of: pdf, docx");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "noextension", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "fileName must end with one of");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", "text/html", 10L, Fixtures.SHA256_HEX),
                "contentType must be application/pdf");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.docx", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX),
                "contentType must be application/vnd.openxmlformats");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 0L, Fixtures.SHA256_HEX),
                "fileSize must be between 1 and");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 5L * 1024 * 1024 + 1,
                Fixtures.SHA256_HEX), "fileSize must be between 1 and 5242880");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", Fixtures.PDF_MIME, null, Fixtures.SHA256_HEX),
                "fileSize is required");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 10L, null),
                "checksum is required");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 10L, "abc123"),
                "checksum must be a SHA-256 value");
        assertInvalidUpload(new UploadUrlRequest(5L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 10L, "z".repeat(64)),
                "checksum must be a SHA-256 value");
    }

    @Test
    void theMaximumAllowedSizeIsAccepted() {
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PresignedUpload("https://x", Map.of()));
        UploadUrlRequest max = new UploadUrlRequest(Fixtures.STUDENT_ID, "RESUME", "a.pdf", Fixtures.PDF_MIME,
                5L * 1024 * 1024, Fixtures.SHA256_HEX);

        assertEquals("UPLOAD_REQUIRED", service.requestUploadUrl(max, Fixtures.RECRUITER_ID).status());
    }

    @Test
    void aMissingBodyIsABadRequest() {
        assertApi(400, "BAD_REQUEST", () -> service.requestUploadUrl(null, Fixtures.RECRUITER_ID));
        assertApi(400, "BAD_REQUEST", () -> service.registerDocument(null, Fixtures.RECRUITER_ID));
    }

    // ---- upload-url: authorization and infrastructure -----------------------------------------------------------

    @Test
    void aRecruiterNotAssignedToTheStudentIsForbiddenAndNothingIsLookedUp() throws SQLException {
        when(access.isActiveAssignment(connection, Fixtures.RECRUITER_ID, Fixtures.STUDENT_ID)).thenReturn(false);

        ApiException e = assertApi(403, "FORBIDDEN", () -> service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID));

        assertEquals("Recruiter is not assigned to this student", e.getMessage());
        verify(documents, never()).findByChecksum(any(), anyString());
        verifyNoInteractions(storage);
    }

    @Test
    void aCallerWhoIsNotAnActiveRecruiterIsForbidden() throws SQLException {
        // A student or manager id, a deleted recruiter, or an unknown id: none is an active recruiter.
        when(access.isActiveRecruiter(connection, 1000000099L)).thenReturn(false);

        ApiException e = assertApi(403, "FORBIDDEN", () -> service.requestUploadUrl(uploadRequest(), 1000000099L));

        assertEquals("Recruiter does not exist or is not active", e.getMessage());
        verify(access, never()).isActiveAssignment(any(), anyLong(), anyLong());
        verifyNoInteractions(storage);
    }

    @Test
    void anUnknownStudentIsSimplyNotAssigned() throws SQLException {
        when(access.isActiveAssignment(connection, Fixtures.RECRUITER_ID, 999L)).thenReturn(false);
        UploadUrlRequest request = new UploadUrlRequest(999L, "RESUME", "a.pdf", Fixtures.PDF_MIME, 10L, Fixtures.SHA256_HEX);

        assertApi(403, "FORBIDDEN", () -> service.requestUploadUrl(request, Fixtures.RECRUITER_ID));
    }

    @Test
    void theDatabaseBeingDownIsARetryable503() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool timeout", "08001"));

        assertApi(503, "SERVICE_UNAVAILABLE", () -> service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void s3BeingUnreachableWhilePresigningIsARetryable503() {
        when(storage.presignUpload(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenThrow(SdkClientException.create("connection reset"));

        assertApi(503, "SERVICE_UNAVAILABLE", () -> service.requestUploadUrl(uploadRequest(), Fixtures.RECRUITER_ID));
    }

    // =================================================================================================================
    // register
    // =================================================================================================================

    @Test
    void aVerifiedUploadIsInsertedThenTaggedAndReturns201Data() throws SQLException {
        stubVerifiedUpload();

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertTrue(outcome.created());
        assertTrue(outcome.body().success());
        assertEquals("SUCCESS", outcome.body().status());
        assertEquals(NEW_OBJECT_ID, outcome.body().objectId());
        assertEquals(Fixtures.STUDENT_ID, outcome.body().studentId());
        assertEquals("RESUME", outcome.body().documentType());
        assertEquals("Resume uploaded successfully", outcome.body().message());
    }

    /** The order is the heart of the consistency design: verify, then INSERT, and only then tag. */
    @Test
    void registrationIsVerifyThenInsertThenTagInThatOrder() throws SQLException {
        stubVerifiedUpload();

        service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        InOrder order = inOrder(storage, documents);
        order.verify(storage).head(KEY);
        order.verify(storage).readHead(eq(KEY), anyInt());
        order.verify(storage).readTail(eq(KEY), anyInt());
        order.verify(documents).insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX);
        order.verify(storage).markRegistered(KEY);
    }

    @Test
    void aTaggingFailureAfterASuccessfulInsertDoesNotFailTheRequest() throws SQLException {
        stubVerifiedUpload();
        doThrow(SdkClientException.create("S3 went away")).when(storage).markRegistered(KEY);

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        // The row exists, so the client is told it worked; replay or the reconciler completes the tag.
        assertTrue(outcome.created());
        assertEquals(NEW_OBJECT_ID, outcome.body().objectId());
        verify(documents).insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX);
    }

    @Test
    void aFailedInsertLeavesTheObjectUntaggedSoItStillExpires() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(new SQLTransientConnectionException("db down", "08001"));

        assertApi(503, "SERVICE_UNAVAILABLE", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verify(storage, never()).markRegistered(anyString());   // still `pending`: safe to retry, expires if never retried
    }

    @Test
    void retryingAfterAFailedInsertSucceeds() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(new SQLTransientConnectionException("db down", "08001"))
                .thenReturn(NEW_OBJECT_ID);

        assertThrows(ApiException.class, () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
        RegisterOutcome retry = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertTrue(retry.created());
        assertEquals(NEW_OBJECT_ID, retry.body().objectId());
        verify(storage).markRegistered(KEY);
    }

    // ---- register: replay and idempotency -----------------------------------------------------------------------

    @Test
    void registeringTheSameUploadAgainReturnsTheSameObjectAndCompletesTheTag() throws SQLException {
        when(documents.findByObjectKey(connection, KEY))
                .thenReturn(Optional.of(row(NEW_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY)));

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertFalse(outcome.created(), "a replay is a 200, not a second creation");
        assertEquals(NEW_OBJECT_ID, outcome.body().objectId());
        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
        verify(storage).markRegistered(KEY);          // heals a row that was inserted but never tagged
        verify(storage, never()).head(anyString());   // no need to re-verify an already registered upload
    }

    @Test
    void aReplayWhoseFileHasVanishedIsReportedUnavailable() throws SQLException {
        when(documents.findByObjectKey(connection, KEY))
                .thenReturn(Optional.of(row(NEW_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY)));
        doThrow(new ObjectMissingException()).when(storage).markRegistered(KEY);

        assertApi(409, "DOCUMENT_UNAVAILABLE", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void aReplayWhileS3IsDownStillSucceedsBecauseTheRowIsAuthoritative() throws SQLException {
        when(documents.findByObjectKey(connection, KEY))
                .thenReturn(Optional.of(row(NEW_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY)));
        doThrow(SdkClientException.create("S3 went away")).when(storage).markRegistered(KEY);

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertFalse(outcome.created());
        assertEquals(NEW_OBJECT_ID, outcome.body().objectId());
    }

    @Test
    void theSameBytesAlreadyRegisteredForThisStudentReturnTheExistingObjectNotTheNewUpload() throws SQLException {
        String earlierKey = "students/1000000005/resumes/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.pdf";
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX))
                .thenReturn(Optional.of(row(EXISTING_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, earlierKey)));

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertFalse(outcome.created());
        assertEquals(EXISTING_OBJECT_ID, outcome.body().objectId());
        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
        verify(storage).markRegistered(earlierKey);                 // the registered object, not the redundant upload
        verify(storage, never()).markRegistered(KEY);               // the redundant upload stays pending and expires
    }

    @Test
    void identicalBytesRegisteredByAnotherStudentAreAConflictWithoutVerifyingS3() throws SQLException {
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX)).thenReturn(Optional.of(
                row(EXISTING_OBJECT_ID, Fixtures.OTHER_STUDENT_ID, DocumentType.RESUME,
                        Fixtures.resumeKey(Fixtures.OTHER_STUDENT_ID))));

        assertApi(409, "DUPLICATE_DOCUMENT", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verifyNoInteractions(storage);
        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void identicalBytesRegisteredUnderAnotherTypeAreAConflict() throws SQLException {
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX)).thenReturn(Optional.of(
                row(EXISTING_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.COVER_LETTER, "students/5/cover-letters/a.pdf")));

        assertApi(409, "DUPLICATE_DOCUMENT", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
    }

    // ---- register: the duplicate-key race (UNIQUE(hashed_value) is the authority) -------------------------------

    private static SQLIntegrityConstraintViolationException duplicateKey() {
        return new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062);
    }

    @Test
    void losingARaceToTheSameStudentReturnsTheWinnersObject() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(duplicateKey());
        // First read (pre-check) saw nothing; by the re-read the winner's row exists.
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(EXISTING_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME,
                        "students/1000000005/resumes/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.pdf")));

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertFalse(outcome.created());
        assertEquals(EXISTING_OBJECT_ID, outcome.body().objectId());
        verify(storage, never()).markRegistered(KEY);   // our redundant upload stays pending and expires
    }

    @Test
    void losingARaceToAnotherStudentIsAConflict() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(duplicateKey());
        when(documents.findByChecksum(connection, Fixtures.SHA256_HEX)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(EXISTING_OBJECT_ID, Fixtures.OTHER_STUDENT_ID, DocumentType.RESUME,
                        Fixtures.resumeKey(Fixtures.OTHER_STUDENT_ID))));

        assertApi(409, "DUPLICATE_DOCUMENT", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
        verify(storage, never()).markRegistered(anyString());
    }

    @Test
    void aConcurrentRetryOfTheSameUploadResolvesToTheSameObject() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(duplicateKey());
        // The duplicate is our own key, inserted a moment ago by the concurrent request.
        when(documents.findByObjectKey(connection, KEY)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(NEW_OBJECT_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY)));

        RegisterOutcome outcome = service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID);

        assertFalse(outcome.created());
        assertEquals(NEW_OBJECT_ID, outcome.body().objectId());
    }

    @Test
    void aDuplicateKeyErrorWithNothingFoundOnReReadIsAnInternalError() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(duplicateKey());

        assertApi(500, "INTERNAL_ERROR", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void anyOtherIntegrityViolationIsAGenericInternalError() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(new SQLIntegrityConstraintViolationException("FK failed near secret_table", "23000", 1452));

        ApiException e = assertApi(500, "INTERNAL_ERROR",
                () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        assertFalse(e.getMessage().contains("secret_table"));
    }

    // ---- register: the upload token -----------------------------------------------------------------------------

    @Test
    void anExpiredTokenIsRejectedSoTheClientRequestsAFreshUrl() {
        UploadIntent expired = new UploadIntent(Fixtures.RECRUITER_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY,
                Fixtures.SHA256_HEX, Fixtures.SIZE, Fixtures.PDF_MIME, Fixtures.NOW.getEpochSecond() - 1);

        assertApi(400, "UPLOAD_TOKEN_EXPIRED", () -> service.registerDocument(registerRequest(expired), Fixtures.RECRUITER_ID));
        verifyNoInteractions(storage);
    }

    @Test
    void aTamperedOrForeignTokenIsRejected() {
        RegisterRequest good = registerRequest();
        RegisterRequest tampered = new RegisterRequest(good.studentId(), good.documentType(), good.objectKey(),
                good.checksum(), good.uploadToken().substring(0, good.uploadToken().length() - 2) + "xx");
        RegisterRequest notAToken = new RegisterRequest(good.studentId(), good.documentType(), good.objectKey(),
                good.checksum(), "not-a-token");

        assertApi(403, "UPLOAD_TOKEN_INVALID", () -> service.registerDocument(tampered, Fixtures.RECRUITER_ID));
        assertApi(403, "UPLOAD_TOKEN_INVALID", () -> service.registerDocument(notAToken, Fixtures.RECRUITER_ID));
        verifyNoInteractions(storage);
    }

    @Test
    void aTokenIssuedToAnotherRecruiterCannotBeUsedByThisOne() {
        UploadIntent otherRecruiter = new UploadIntent(1000000002L, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY,
                Fixtures.SHA256_HEX, Fixtures.SIZE, Fixtures.PDF_MIME, Fixtures.NOW.getEpochSecond() + 1800);

        assertApi(403, "UPLOAD_TOKEN_INVALID",
                () -> service.registerDocument(registerRequest(otherRecruiter), Fixtures.RECRUITER_ID));
    }

    @Test
    void everyRequestFieldMustMatchWhatTheTokenAuthorized() {
        String token = tokens.sign(Fixtures.intent());

        // different object key (even one in the right place), checksum, type or student than the token authorized
        String sneakyKey = "students/1000000005/resumes/cccccccc-cccc-cccc-cccc-cccccccccccc.pdf";
        assertApi(403, "UPLOAD_TOKEN_INVALID", () -> service.registerDocument(
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", sneakyKey, Fixtures.SHA256_HEX, token), Fixtures.RECRUITER_ID));
        assertApi(403, "UPLOAD_TOKEN_INVALID", () -> service.registerDocument(
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", KEY, Fixtures.OTHER_SHA256_HEX, token), Fixtures.RECRUITER_ID));
        verifyNoInteractions(storage);
    }

    @Test
    void aTokenAuthorizedForOneDocumentTypeCannotRegisterAnother() {
        // With cover letters enabled, a RESUME token must not be usable to register a COVER_LETTER.
        DocumentUploadService withCoverLetters = new DocumentUploadService(dataSource,
                DocumentUploadConfig.fromEnv(Map.of("DOCUMENT_COVER_LETTER_ENABLED", "true")), documents, access,
                storage, tokens, clock);
        String resumeToken = tokens.sign(Fixtures.intent());

        assertApi(403, "UPLOAD_TOKEN_INVALID", () -> withCoverLetters.registerDocument(
                new RegisterRequest(Fixtures.STUDENT_ID, "COVER_LETTER", KEY, Fixtures.SHA256_HEX, resumeToken),
                Fixtures.RECRUITER_ID));
        verifyNoInteractions(storage);
    }

    @Test
    void anObjectKeyThatIsNotExactlyWhatTheServerGeneratesIsRefusedEvenWithAMatchingToken() {
        // The token is genuine and matches the request, but the key itself is not a server-generated key for this student.
        for (String badKey : new String[] {
                Fixtures.resumeKey(Fixtures.OTHER_STUDENT_ID),                                  // another student's folder
                "students/1000000005/resumes/evil.pdf",                                          // not a uuid
                "students/1000000005/resumes/../1000000006/resumes/" + Fixtures.UUID_PART + ".pdf",  // traversal
                "students/1000000005/cover-letters/" + Fixtures.UUID_PART + ".pdf",              // wrong type folder
                "other/1000000005/resumes/" + Fixtures.UUID_PART + ".pdf",                       // wrong root
                "students/1000000005/resumes/" + Fixtures.UUID_PART + ".exe",                    // wrong extension
                KEY + "/extra"}) {
            UploadIntent intent = new UploadIntent(Fixtures.RECRUITER_ID, Fixtures.STUDENT_ID, DocumentType.RESUME,
                    badKey, Fixtures.SHA256_HEX, Fixtures.SIZE, Fixtures.PDF_MIME, Fixtures.NOW.getEpochSecond() + 1800);
            RegisterRequest request = new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", badKey, Fixtures.SHA256_HEX,
                    tokens.sign(intent));

            assertApi(403, "UPLOAD_TOKEN_INVALID", () -> service.registerDocument(request, Fixtures.RECRUITER_ID));
        }
        verifyNoInteractions(storage);
    }

    // ---- register: verifying what S3 holds ----------------------------------------------------------------------

    @Test
    void anObjectThatWasNeverUploadedIsRejected() throws SQLException {
        when(storage.head(KEY)).thenReturn(Optional.empty());

        assertApi(400, "UPLOAD_NOT_FOUND", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
        verify(storage, never()).markRegistered(anyString());
    }

    private void assertVerificationFails(StoredObject object) throws SQLException {
        when(storage.head(KEY)).thenReturn(Optional.of(object));

        assertApi(400, "UPLOAD_VERIFICATION_FAILED", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
        verify(storage, never()).markRegistered(anyString());
    }

    @Test
    void aSizeMismatchIsRejected() throws SQLException {
        assertVerificationFails(new StoredObject(Fixtures.SIZE + 1, Fixtures.PDF_MIME, Fixtures.SHA256_BASE64));
    }

    @Test
    void aContentTypeMismatchIsRejected() throws SQLException {
        assertVerificationFails(new StoredObject(Fixtures.SIZE, "text/html", Fixtures.SHA256_BASE64));
    }

    @Test
    void aChecksumMismatchBetweenS3AndTheAuthorizationIsRejected() throws SQLException {
        // The checksum S3 recorded differs from the one the backend authorized: never trust the request's claim.
        assertVerificationFails(new StoredObject(Fixtures.SIZE, Fixtures.PDF_MIME, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }

    @Test
    void anObjectWithoutAnyStoredSha256IsRejected() throws SQLException {
        assertVerificationFails(new StoredObject(Fixtures.SIZE, Fixtures.PDF_MIME, null));
    }

    @Test
    void theCheckSumInTheRequestBodyAloneIsNeverEnough() throws SQLException {
        // Request, token and claims all agree, yet S3 recorded different bytes: still rejected.
        assertVerificationFails(new StoredObject(Fixtures.SIZE, Fixtures.PDF_MIME,
                java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(Fixtures.OTHER_SHA256_HEX))));
    }

    @Test
    void aContentTypeWithAParameterIsStillTheSameType() throws SQLException {
        stubVerifiedUpload();
        when(storage.head(KEY)).thenReturn(Optional.of(new StoredObject(Fixtures.SIZE, "Application/PDF; charset=binary",
                Fixtures.SHA256_BASE64)));

        assertTrue(service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID).created());
    }

    @Test
    void aFileThatDoesNotLookLikeItsDeclaredFormatIsRejected() throws SQLException {
        when(storage.head(KEY)).thenReturn(Optional.of(Fixtures.matchingObject()));
        when(storage.readHead(eq(KEY), anyInt())).thenReturn("<html>".getBytes(StandardCharsets.US_ASCII));
        when(storage.readTail(eq(KEY), anyInt())).thenReturn(Fixtures.pdfTail());

        assertApi(400, "INVALID_FILE_FORMAT", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void aTruncatedPdfIsRejected() throws SQLException {
        when(storage.head(KEY)).thenReturn(Optional.of(Fixtures.matchingObject()));
        when(storage.readHead(eq(KEY), anyInt())).thenReturn(Fixtures.pdfHead());
        when(storage.readTail(eq(KEY), anyInt())).thenReturn("cut off mid-stream".getBytes(StandardCharsets.US_ASCII));

        assertApi(400, "INVALID_FILE_FORMAT", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void aDocxIsRegisteredWhenItHasTheZipSignatureAndEndRecord() throws SQLException {
        String docxKey = "students/1000000005/resumes/" + Fixtures.UUID_PART + ".docx";
        String docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        UploadIntent intent = new UploadIntent(Fixtures.RECRUITER_ID, Fixtures.STUDENT_ID, DocumentType.RESUME, docxKey,
                Fixtures.SHA256_HEX, 2048L, docxMime, Fixtures.NOW.getEpochSecond() + 1800);
        when(storage.head(docxKey)).thenReturn(Optional.of(new StoredObject(2048L, docxMime, Fixtures.SHA256_BASE64)));
        when(storage.readHead(eq(docxKey), anyInt())).thenReturn(new byte[] {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0});
        when(storage.readTail(eq(docxKey), anyInt())).thenReturn(new byte[] {0, 0x50, 0x4B, 0x05, 0x06, 0, 0});
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, docxKey, Fixtures.SHA256_HEX))
                .thenReturn(NEW_OBJECT_ID);
        RegisterRequest request = new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", docxKey, Fixtures.SHA256_HEX,
                tokens.sign(intent));

        assertTrue(service.registerDocument(request, Fixtures.RECRUITER_ID).created());
    }

    // ---- register: authorization, validation, infrastructure ----------------------------------------------------

    @Test
    void aRecruiterNoLongerAssignedCannotRegister() throws SQLException {
        when(access.isActiveAssignment(connection, Fixtures.RECRUITER_ID, Fixtures.STUDENT_ID)).thenReturn(false);

        assertApi(403, "FORBIDDEN", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verifyNoInteractions(storage);
        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void aNonRecruiterCannotRegister() throws SQLException {
        when(access.isActiveRecruiter(connection, Fixtures.RECRUITER_ID)).thenReturn(false);

        assertApi(403, "FORBIDDEN", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void registeringACoverLetterIsRejectedWhileItIsDisabled() {
        RegisterRequest request = new RegisterRequest(Fixtures.STUDENT_ID, "COVER_LETTER", KEY, Fixtures.SHA256_HEX, "x");

        assertApi(400, "DOCUMENT_TYPE_NOT_ENABLED", () -> service.registerDocument(request, Fixtures.RECRUITER_ID));
        verifyNoInteractions(dataSource, storage);
    }

    @Test
    void invalidRegisterRequestsAreRejectedBeforeTouchingAnything() {
        for (RegisterRequest bad : new RegisterRequest[] {
                new RegisterRequest(null, "RESUME", KEY, Fixtures.SHA256_HEX, "t"),
                new RegisterRequest(Fixtures.STUDENT_ID, "NOPE", KEY, Fixtures.SHA256_HEX, "t"),
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", " ", Fixtures.SHA256_HEX, "t"),
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", "k".repeat(513), Fixtures.SHA256_HEX, "t"),
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", KEY, "short", "t"),
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", KEY, Fixtures.SHA256_HEX, null),
                new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", KEY, Fixtures.SHA256_HEX, " ")}) {
            assertApi(400, "VALIDATION_ERROR", () -> service.registerDocument(bad, Fixtures.RECRUITER_ID));
        }
        verifyNoInteractions(dataSource, storage);
    }

    @Test
    void anUppercaseChecksumInTheRegisterRequestStillMatchesTheToken() throws SQLException {
        stubVerifiedUpload();
        RegisterRequest request = new RegisterRequest(Fixtures.STUDENT_ID, "RESUME", KEY,
                Fixtures.SHA256_HEX.toUpperCase(), tokens.sign(Fixtures.intent()));

        assertTrue(service.registerDocument(request, Fixtures.RECRUITER_ID).created());
    }

    @Test
    void s3BeingDownWhileVerifyingIsARetryable503AndNothingIsRecorded() throws SQLException {
        when(storage.head(KEY)).thenThrow(SdkClientException.create("connection reset"));

        assertApi(503, "SERVICE_UNAVAILABLE", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        verify(documents, never()).insert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void registeringWhileTheDatabaseIsDownIsARetryable503() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool timeout", "08001"));

        assertApi(503, "SERVICE_UNAVAILABLE", () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));
    }

    @Test
    void errorsNeverLeakSqlOrS3Detail() throws SQLException {
        stubVerifiedUpload();
        when(documents.insert(connection, Fixtures.STUDENT_ID, DocumentType.RESUME, KEY, Fixtures.SHA256_HEX))
                .thenThrow(new SQLException("Unknown column 'secret_col' in field list", "42S22", 1054));

        ApiException e = assertApi(500, "INTERNAL_ERROR",
                () -> service.registerDocument(registerRequest(), Fixtures.RECRUITER_ID));

        assertFalse(e.getMessage().contains("secret_col"));
        assertEquals("An unexpected error occurred.", e.getMessage());
    }
}
