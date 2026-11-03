package org.hmb.security;

import org.hmb.dto.DocumentType;

/**
 * What the backend authorized when it handed out an upload URL. It is carried in the signed upload token and is the
 * only proof, at registration, that this exact upload was authorized for this recruiter.
 *
 * @param expiresAtEpochSeconds when the token stops being accepted
 */
public record UploadIntent(
        long recruiterId,
        long studentId,
        DocumentType documentType,
        String objectKey,
        String checksum,
        long size,
        String contentType,
        long expiresAtEpochSeconds) {
}
