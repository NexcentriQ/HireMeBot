package org.hmb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileFormatTest {

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte[] ZIP_HEAD = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
    private static final byte[] ZIP_TAIL = {0x00, 0x00, 0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00};

    // ---- PDF ----------------------------------------------------------------------------------------------------

    @Test
    void aWellFormedPdfPasses() {
        assertTrue(FileFormat.PDF.matches(ascii("%PDF-1.7\n"), ascii("...trailer\n%%EOF\n")));
    }

    @Test
    void aPdfWithoutTheHeaderFails() {
        assertFalse(FileFormat.PDF.matches(ascii("<html>no"), ascii("%%EOF")));
        assertFalse(FileFormat.PDF.matches(ascii(" %PDF-1.7"), ascii("%%EOF")));   // must start at byte 0
    }

    @Test
    void aTruncatedPdfWithoutTheEndMarkerFails() {
        assertFalse(FileFormat.PDF.matches(ascii("%PDF-1.7\n"), ascii("half of the file and then nothing")));
    }

    @Test
    void emptyOrMissingBytesFail() {
        assertFalse(FileFormat.PDF.matches(new byte[0], new byte[0]));
        assertFalse(FileFormat.PDF.matches(null, null));
        assertFalse(FileFormat.DOCX.matches(new byte[0], new byte[0]));
    }

    // ---- DOCX ---------------------------------------------------------------------------------------------------

    @Test
    void aCompleteZipPassesTheDocxCheck() {
        assertTrue(FileFormat.DOCX.matches(ZIP_HEAD, ZIP_TAIL));
    }

    @Test
    void aZipWithoutItsEndRecordIsTruncatedAndFails() {
        assertFalse(FileFormat.DOCX.matches(ZIP_HEAD, new byte[] {0x01, 0x02, 0x03, 0x04}));
    }

    @Test
    void aNonZipFailsTheDocxCheck() {
        assertFalse(FileFormat.DOCX.matches(ascii("%PDF-1.7\n"), ZIP_TAIL));
    }

    /** Documents the known limitation: the check is only a signature check, so ANY complete zip passes. */
    @Test
    void anyCompleteZipPassesBecauseOnlyTheSignatureIsChecked() {
        // An XLSX or a JAR has the same first and last bytes as a DOCX; the check cannot tell them apart.
        assertTrue(FileFormat.DOCX.matches(ZIP_HEAD, ZIP_TAIL));
    }

    /** Documents the known limitation: a PDF header and trailer say nothing about the content in between. */
    @Test
    void aPdfIsAcceptedWhateverIsBetweenTheMarkers() {
        assertTrue(FileFormat.PDF.matches(ascii("%PDF-"), ascii("/JavaScript (app.alert(1)) %%EOF")));
    }

    // ---- extensions and mime types ------------------------------------------------------------------------------

    @Test
    void extensionsMapToFormatsAndMimeTypes() {
        assertEquals(Optional.of(FileFormat.PDF), FileFormat.forExtension("pdf"));
        assertEquals(Optional.of(FileFormat.PDF), FileFormat.forExtension(" PDF "));
        assertEquals(Optional.of(FileFormat.DOCX), FileFormat.forExtension("Docx"));
        assertEquals(Optional.empty(), FileFormat.forExtension("exe"));
        assertEquals(Optional.empty(), FileFormat.forExtension("doc"));
        assertEquals("application/pdf", FileFormat.PDF.mimeType());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                FileFormat.DOCX.mimeType());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "noextension", "trailingdot."})
    void fileNamesWithoutAnExtensionYieldNone(String name) {
        assertNull(FileFormat.extensionOf(name));
    }

    @Test
    void theExtensionIsTheTextAfterTheLastDot() {
        assertEquals("pdf", FileFormat.extensionOf("Akhil_Resume.pdf"));
        assertEquals("pdf", FileFormat.extensionOf("my.resume.v2.PDF".toLowerCase()));
        assertEquals("exe", FileFormat.extensionOf("resume.pdf.exe"));   // the last extension wins, so this is rejected
    }
}
