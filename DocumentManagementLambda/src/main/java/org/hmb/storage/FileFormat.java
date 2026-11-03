package org.hmb.storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * The file formats a document may have, with the <em>basic</em> structural check used at registration.
 *
 * <p><b>This is deliberately shallow.</b> It looks only at the first bytes and at the tail of the file (read with two
 * ranged S3 requests, nothing is downloaded in full or parsed):
 * <ul>
 *   <li>PDF: starts with {@code %PDF-} and contains {@code %%EOF} in the last 1 KB (catches truncated uploads).</li>
 *   <li>DOCX: starts with the zip signature {@code PK\x03\x04} and contains the zip end-of-central-directory
 *       signature {@code PK\x05\x06} in the last ~64 KB (catches truncated or non-zip files).</li>
 * </ul>
 * Magic bytes and trailer markers are trivially forgeable. They prove neither that the file is valid nor that it is
 * safe: any zip (XLSX, JAR, ...) passes the DOCX check, and a {@code %PDF-} file can still carry active content or
 * fail to render. There is no malware scanning. See the module README.
 */
public enum FileFormat {

    PDF("pdf", "application/pdf", 1024) {
        @Override
        public boolean matches(byte[] head, byte[] tail) {
            return startsWith(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))
                    && indexOf(tail, "%%EOF".getBytes(StandardCharsets.US_ASCII)) >= 0;
        }
    },

    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 65_557) {
        @Override
        public boolean matches(byte[] head, byte[] tail) {
            return startsWith(head, new byte[] {0x50, 0x4B, 0x03, 0x04})
                    && indexOf(tail, new byte[] {0x50, 0x4B, 0x05, 0x06}) >= 0;
        }
    };

    /** How many leading bytes the check needs. */
    public static final int HEAD_BYTES = 8;

    private final String extension;
    private final String mimeType;
    private final int tailBytes;

    FileFormat(String extension, String mimeType, int tailBytes) {
        this.extension = extension;
        this.mimeType = mimeType;
        this.tailBytes = tailBytes;
    }

    public String extension() {
        return extension;
    }

    /** The only Content-Type accepted for this format. */
    public String mimeType() {
        return mimeType;
    }

    /** How many trailing bytes the check needs (a zip comment can be up to 65535 bytes plus the 22-byte record). */
    public int tailBytes() {
        return tailBytes;
    }

    /**
     * @param head the first {@link #HEAD_BYTES} bytes of the object (or the whole object if it is shorter)
     * @param tail the last {@link #tailBytes()} bytes of the object (or the whole object if it is shorter)
     */
    public abstract boolean matches(byte[] head, byte[] tail);

    /** Finds the format for a file extension (case-insensitive, no dot); empty if it is not supported. */
    public static Optional<FileFormat> forExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        for (FileFormat format : values()) {
            if (format.extension.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** The extension of a file name (text after the last dot), or null if there is none. */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        String trimmed = fileName.trim();
        int dot = trimmed.lastIndexOf('.');
        return (dot < 0 || dot == trimmed.length() - 1) ? null : trimmed.substring(dot + 1);
    }

    static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static int indexOf(byte[] data, byte[] needle) {
        if (data == null || needle.length == 0 || data.length < needle.length) {
            return -1;
        }
        for (int i = 0; i <= data.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
