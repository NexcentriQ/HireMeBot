package org.hmb.dto;

/**
 * Body of {@code POST /api/v1/documents/upload-url}. The recruiter is never part of the body: it comes from the
 * authenticated identity. Every field is validated by the service; the object key is never accepted from the client.
 *
 * @param checksum SHA-256 of the file's bytes as 64 hexadecimal characters, calculated in the browser
 */
public record UploadUrlRequest(
        Long studentId,
        String documentType,
        String fileName,
        String contentType,
        Long fileSize,
        String checksum) {
}
