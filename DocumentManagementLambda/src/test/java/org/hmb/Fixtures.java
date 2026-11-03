package org.hmb;

import org.hmb.dto.DocumentType;
import org.hmb.security.UploadIntent;
import org.hmb.storage.S3DocumentStorageService.StoredObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Shared constants and builders for tests. No real credentials or documents anywhere. */
public final class Fixtures {

    public static final long RECRUITER_ID = 1000000001L;
    public static final long STUDENT_ID = 1000000005L;
    public static final long OTHER_STUDENT_ID = 1000000006L;

    /** SHA-256("abc"): a well-known test vector, in hex and as the base64 S3 expects in x-amz-checksum-sha256. */
    public static final String SHA256_HEX = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    public static final String SHA256_BASE64 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=";

    public static final String OTHER_SHA256_HEX = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public static final String SECRET = "unit-test-signing-secret-0123456789abcdef";

    public static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    public static final String PDF_MIME = "application/pdf";
    public static final long SIZE = 245_760L;

    public static final String UUID_PART = "123e4567-e89b-12d3-a456-426614174000";

    private Fixtures() {
    }

    public static String resumeKey(long studentId) {
        return "students/" + studentId + "/resumes/" + UUID_PART + ".pdf";
    }

    /** What the backend would authorize for a normal resume upload by the standard recruiter. */
    public static UploadIntent intent() {
        return new UploadIntent(RECRUITER_ID, STUDENT_ID, DocumentType.RESUME, resumeKey(STUDENT_ID), SHA256_HEX,
                SIZE, PDF_MIME, NOW.getEpochSecond() + 1800);
    }

    /** The object S3 would report for that authorized upload. */
    public static StoredObject matchingObject() {
        return new StoredObject(SIZE, PDF_MIME, SHA256_BASE64);
    }

    public static byte[] pdfHead() {
        return "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] pdfTail() {
        return "trailer\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }
}
