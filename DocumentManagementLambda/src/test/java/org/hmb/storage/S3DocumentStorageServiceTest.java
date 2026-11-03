package org.hmb.storage;

import org.hmb.Fixtures;
import org.hmb.storage.S3DocumentStorageService.ObjectState;
import org.hmb.storage.S3DocumentStorageService.PresignedUpload;
import org.hmb.storage.S3DocumentStorageService.StoredObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Presigning runs for real, offline with fake credentials; everything else uses a mocked S3 client. */
@ExtendWith(MockitoExtension.class)
class S3DocumentStorageServiceTest {

    private static final String BUCKET = "hmb-docs-test";
    private static final String KEY = Fixtures.resumeKey(Fixtures.STUDENT_ID);

    @Mock S3Client s3;

    private S3Presigner presigner;
    private S3DocumentStorageService storage;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIAFAKEFAKEFAKEFAKE", "fake-secret-key-for-unit-tests")))
                .build();
        storage = new S3DocumentStorageService(s3, presigner, BUCKET);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    private static S3Exception s3Error(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("test").build();
    }

    // ---- presigned PUT (real SDK, no network) -------------------------------------------------------------------

    @Test
    void thePresignedPutTargetsExactlyTheServerChosenKeyInTheDocumentsBucket() {
        PresignedUpload upload = storage.presignUpload(KEY, Fixtures.PDF_MIME, Fixtures.SIZE, Fixtures.SHA256_HEX,
                Duration.ofSeconds(300));

        URI uri = URI.create(upload.url());
        assertTrue(uri.getHost().startsWith(BUCKET + "."), uri.getHost());
        assertEquals("/" + KEY, uri.getPath());
        assertTrue(uri.getQuery().contains("X-Amz-Expires=300"), "the URL lives five minutes");
        assertTrue(uri.getQuery().contains("X-Amz-Signature="));
    }

    @Test
    void theRequiredHeadersAreExactlyWhatTheSdkSignedMinusBrowserManagedOnes() {
        PresignedUpload upload = storage.presignUpload(KEY, Fixtures.PDF_MIME, Fixtures.SIZE, Fixtures.SHA256_HEX,
                Duration.ofSeconds(300));

        Map<String, String> headers = upload.requiredHeaders();
        assertEquals(List.of("Content-Type", "x-amz-checksum-sha256", "x-amz-tagging"),
                headers.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        assertEquals("application/pdf", headers.get("Content-Type"));
        assertEquals("doc-state=pending", headers.get("x-amz-tagging"));
        // The browser must not be told to set Host or Content-Length (it manages both).
        assertFalse(headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("host")));
        assertFalse(headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("content-length")));
    }

    @Test
    void theChecksumHeaderCarriesTheBase64OfTheHexDigestNotTheHex() {
        PresignedUpload upload = storage.presignUpload(KEY, Fixtures.PDF_MIME, Fixtures.SIZE, Fixtures.SHA256_HEX,
                Duration.ofSeconds(300));

        assertEquals(Fixtures.SHA256_BASE64, upload.requiredHeaders().get("x-amz-checksum-sha256"));
    }

    @Test
    void theSizeContentTypeChecksumAndTagAreAllBoundIntoTheSignature() {
        PresignedUpload upload = storage.presignUpload(KEY, Fixtures.PDF_MIME, Fixtures.SIZE, Fixtures.SHA256_HEX,
                Duration.ofSeconds(300));

        String signedHeaders = URI.create(upload.url()).getQuery();
        assertTrue(signedHeaders.contains("X-Amz-SignedHeaders="));
        for (String signed : List.of("content-length", "content-type", "x-amz-checksum-sha256", "x-amz-tagging")) {
            assertTrue(signedHeaders.toLowerCase().contains(signed), "should be signed: " + signed);
        }
    }

    @Test
    void thePresignedUrlNeverContainsTheChecksumOrTheSecret() {
        PresignedUpload upload = storage.presignUpload(KEY, Fixtures.PDF_MIME, Fixtures.SIZE, Fixtures.SHA256_HEX,
                Duration.ofSeconds(300));

        assertFalse(upload.url().contains("fake-secret-key-for-unit-tests"));
        assertFalse(upload.url().contains(Fixtures.SHA256_HEX));
    }

    // ---- head ---------------------------------------------------------------------------------------------------

    @Test
    void headReturnsWhatS3RecordedIncludingTheStoredSha256() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(Fixtures.SIZE).contentType(Fixtures.PDF_MIME).checksumSHA256(Fixtures.SHA256_BASE64).build());

        Optional<StoredObject> head = storage.head(KEY);

        assertEquals(Optional.of(new StoredObject(Fixtures.SIZE, Fixtures.PDF_MIME, Fixtures.SHA256_BASE64)), head);
        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(request.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(KEY, request.getValue().key());
        assertEquals("ENABLED", request.getValue().checksumModeAsString(), "must ask S3 to return the stored checksum");
    }

    @Test
    void aMissingObjectIsEmptyForBothNoSuchKeyAndA404() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build())
                .thenThrow(s3Error(404));

        assertEquals(Optional.empty(), storage.head(KEY));
        assertEquals(Optional.empty(), storage.head(KEY));
    }

    @Test
    void otherS3ErrorsOnHeadAreNotSwallowed() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(403));

        assertThrows(S3Exception.class, () -> storage.head(KEY));
    }

    // ---- ranged reads -------------------------------------------------------------------------------------------

    @Test
    void readHeadAsksForTheFirstBytesOnly() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {1, 2, 3}));

        assertArrayEquals(new byte[] {1, 2, 3}, storage.readHead(KEY, 8));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertEquals("bytes=0-7", request.getValue().range());
    }

    @Test
    void readTailAsksForTheLastBytesOnly() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {9}));

        storage.readTail(KEY, 1024);

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertEquals("bytes=-1024", request.getValue().range());
    }

    @Test
    void anEmptyObjectYieldsNoBytesInsteadOfAnError() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(s3Error(416));

        assertEquals(0, storage.readHead(KEY, 8).length);
    }

    // ---- state tag ----------------------------------------------------------------------------------------------

    @Test
    void markRegisteredReplacesTheTagSetWithDocStateRegistered() {
        storage.markRegistered(KEY);

        ArgumentCaptor<PutObjectTaggingRequest> request = ArgumentCaptor.forClass(PutObjectTaggingRequest.class);
        verify(s3).putObjectTagging(request.capture());
        assertEquals(BUCKET, request.getValue().bucket());
        assertEquals(KEY, request.getValue().key());
        List<Tag> tags = request.getValue().tagging().tagSet();
        assertEquals(1, tags.size());
        assertEquals("doc-state", tags.get(0).key());
        assertEquals("registered", tags.get(0).value());
    }

    @Test
    void markRegisteredOnAMissingObjectSaysSoWithoutLeakingTheKey() {
        when(s3.putObjectTagging(any(PutObjectTaggingRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        ObjectMissingException e = assertThrows(ObjectMissingException.class, () -> storage.markRegistered(KEY));

        assertFalse(e.getMessage().contains(KEY));
    }

    @Test
    void stateReadsTheDocStateTag() {
        when(s3.getObjectTagging(any(GetObjectTaggingRequest.class)))
                .thenReturn(tags("doc-state", "pending"))
                .thenReturn(tags("doc-state", "registered"))
                .thenReturn(tags("something-else", "x"))
                .thenReturn(tags("doc-state", "unexpected-value"))
                .thenThrow(NoSuchKeyException.builder().build());

        assertEquals(ObjectState.PENDING, storage.state(KEY));
        assertEquals(ObjectState.REGISTERED, storage.state(KEY));
        assertEquals(ObjectState.UNTAGGED, storage.state(KEY));
        assertEquals(ObjectState.UNTAGGED, storage.state(KEY));
        assertEquals(ObjectState.MISSING, storage.state(KEY));
    }

    private static GetObjectTaggingResponse tags(String key, String value) {
        return GetObjectTaggingResponse.builder().tagSet(Tag.builder().key(key).value(value).build()).build();
    }
}
