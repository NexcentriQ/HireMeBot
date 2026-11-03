package org.hmb.repository;

import org.hmb.dto.DocumentType;

/** A row of {@code object_store_entries}. Internal: never serialized to a client (it holds the S3 key and owner). */
public record StoredDocument(
        long objectId,
        long studentId,
        DocumentType documentType,
        String objectKey,
        String checksum) {
}
