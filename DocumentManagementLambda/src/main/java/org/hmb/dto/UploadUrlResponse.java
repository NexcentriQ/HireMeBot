package org.hmb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Success body of {@code POST /api/v1/documents/upload-url}. Fields that do not apply are omitted.
 *
 * <ul>
 *   <li>{@code ALREADY_EXISTS}: {@code uploadRequired=false}, {@code objectId} is the existing document.</li>
 *   <li>{@code UPLOAD_REQUIRED}: upload the file with an HTTP PUT to {@code uploadUrl} sending exactly
 *       {@code requiredHeaders}, then call register with {@code objectKey} and {@code uploadToken}.</li>
 * </ul>
 *
 * <p>{@code uploadUrl} and {@code uploadToken} are credentials: never log them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadUrlResponse(
        boolean success,
        String status,
        boolean uploadRequired,
        Long objectId,
        String objectKey,
        String uploadUrl,
        String uploadToken,
        Integer expiresIn,
        Map<String, String> requiredHeaders) {

    public static UploadUrlResponse alreadyExists(long objectId) {
        return new UploadUrlResponse(true, "ALREADY_EXISTS", false, objectId, null, null, null, null, null);
    }

    public static UploadUrlResponse uploadRequired(String objectKey, String uploadUrl, String uploadToken,
                                                   int expiresIn, Map<String, String> requiredHeaders) {
        return new UploadUrlResponse(true, "UPLOAD_REQUIRED", true, null, objectKey, uploadUrl, uploadToken,
                expiresIn, requiredHeaders);
    }
}
