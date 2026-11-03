package org.hmb.dto;

/**
 * Body of {@code POST /api/v1/documents/register}, sent only after the S3 PUT succeeded.
 *
 * <p>Every field must match what the signed {@code uploadToken} authorized. The checksum here is never trusted on its
 * own: it is compared with the signed value and with the checksum S3 recorded for the object.
 */
public record RegisterRequest(
        Long studentId,
        String documentType,
        String objectKey,
        String checksum,
        String uploadToken) {
}
