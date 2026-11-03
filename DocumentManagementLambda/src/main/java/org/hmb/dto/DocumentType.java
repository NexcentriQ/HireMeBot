package org.hmb.dto;

import java.util.Locale;
import java.util.Optional;

/**
 * Kinds of document the upload module can store. The names match the {@code object_store_entries.document_type}
 * ENUM values exactly. Whether a type is accepted is configuration, not code (see DocumentUploadConfig): in V1 only
 * {@link #RESUME} is enabled.
 */
public enum DocumentType {

    RESUME("resumes", "Resume"),
    COVER_LETTER("cover-letters", "Cover letter");

    private final String keySegment;
    private final String displayName;

    DocumentType(String keySegment, String displayName) {
        this.keySegment = keySegment;
        this.displayName = displayName;
    }

    /** The S3 key folder for this type, e.g. {@code resumes} in {@code students/{id}/resumes/{uuid}.pdf}. */
    public String keySegment() {
        return keySegment;
    }

    /** Human-readable name used in response messages. */
    public String displayName() {
        return displayName;
    }

    /** Parses a client-supplied value (trimmed, case-insensitive); empty if it is not a known type. */
    public static Optional<DocumentType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DocumentType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
