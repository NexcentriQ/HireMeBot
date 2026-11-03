package org.hmb.dto;

/**
 * Success body of {@code POST /api/v1/documents/register}. Returned with 201 when the document was registered by this
 * call, and with 200 when it was already registered (an idempotent replay, or the same bytes already stored for the
 * same student); the body is the same either way.
 */
public record RegisterResponse(
        boolean success,
        String status,
        long objectId,
        long studentId,
        String documentType,
        String message) {

    public static RegisterResponse success(long objectId, long studentId, DocumentType type) {
        return new RegisterResponse(true, "SUCCESS", objectId, studentId, type.name(),
                type.displayName() + " uploaded successfully");
    }
}
