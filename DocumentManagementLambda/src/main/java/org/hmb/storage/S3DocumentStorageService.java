package org.hmb.storage;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the module does with S3, behind one thin class so the services can be tested with a mock.
 *
 * <p><b>Object state tag.</b> Every upload is signed with {@code x-amz-tagging: doc-state=pending}; a bucket lifecycle
 * rule expires only {@code pending} objects, after 7 days. After a document is registered in MySQL the object is
 * retagged {@code registered}, which the rule ignores. MySQL is the source of truth: the tag is only a derived
 * marker, and no code here ever deletes an object.
 *
 * <p>The presigned PUT signs the content type, the content length, the SHA-256 and the state tag, so S3 itself
 * rejects an upload that differs from what the backend authorized. Credentials come from the Lambda execution role.
 */
public class S3DocumentStorageService {

    public static final String STATE_TAG = "doc-state";
    public static final String PENDING = "pending";
    public static final String REGISTERED = "registered";

    /** What the reconciler needs to know about an object's tag. */
    public enum ObjectState { PENDING, REGISTERED, UNTAGGED, MISSING }

    /** A presigned PUT: the URL and the headers the browser must send exactly (Content-Length is set by the browser). */
    public record PresignedUpload(String url, Map<String, String> requiredHeaders) {
    }

    /** What S3 recorded for an object. {@code checksumSha256Base64} is null if the object has no SHA-256. */
    public record StoredObject(long size, String contentType, String checksumSha256Base64) {
    }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3DocumentStorageService(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    /** Builds the clients with the default region and credential chain and the lightweight URL-connection HTTP client. */
    public static S3DocumentStorageService create(String bucket) {
        return new S3DocumentStorageService(
                S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                S3Presigner.create(),
                bucket);
    }

    /**
     * Presigns a PUT for exactly this key, type, size and SHA-256.
     *
     * @param sha256Hex 64 hex characters; sent to S3 as base64 in {@code x-amz-checksum-sha256}
     */
    public PresignedUpload presignUpload(String key, String contentType, long size, String sha256Hex, Duration expiry) {
        String sha256Base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .checksumSHA256(sha256Base64)
                .tagging(STATE_TAG + "=" + PENDING)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(put)
                .build());

        // Derived from what the SDK actually signed, so the response can never disagree with the signature.
        // Host is implied by the URL; Content-Length is set by the browser from the body.
        Map<String, String> required = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")) {
                return;
            }
            required.put(name.equalsIgnoreCase("content-type") ? "Content-Type" : name, String.join(",", values));
        });
        return new PresignedUpload(presigned.url().toString(), required);
    }

    /** @return the object's metadata, or empty if there is no such object */
    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build());
            return Optional.of(new StoredObject(response.contentLength(), response.contentType(),
                    response.checksumSHA256()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The first {@code count} bytes (fewer for a shorter object; empty for an empty object). */
    public byte[] readHead(String key, int count) {
        return readRange(key, "bytes=0-" + (count - 1));
    }

    /** The last {@code count} bytes (the whole object if it is shorter; empty for an empty object). */
    public byte[] readTail(String key, int count) {
        return readRange(key, "bytes=-" + count);
    }

    private byte[] readRange(String key, String range) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).range(range).build())
                    .asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                return new byte[0];   // empty object: nothing in range
            }
            throw e;
        }
    }

    /**
     * Sets {@code doc-state=registered}, replacing the tag set. Idempotent, so retries and the reconciler can overlap.
     *
     * @throws ObjectMissingException if the object does not exist
     */
    public void markRegistered(String key) {
        try {
            s3.putObjectTagging(PutObjectTaggingRequest.builder()
                    .bucket(bucket).key(key)
                    .tagging(Tagging.builder().tagSet(Tag.builder().key(STATE_TAG).value(REGISTERED).build()).build())
                    .build());
        } catch (NoSuchKeyException e) {
            throw new ObjectMissingException();
        }
    }

    /** Reads the {@code doc-state} tag. A missing object is {@link ObjectState#MISSING}. */
    public ObjectState state(String key) {
        try {
            List<Tag> tags = s3.getObjectTagging(GetObjectTaggingRequest.builder().bucket(bucket).key(key).build())
                    .tagSet();
            for (Tag tag : tags) {
                if (STATE_TAG.equals(tag.key())) {
                    if (REGISTERED.equals(tag.value())) {
                        return ObjectState.REGISTERED;
                    }
                    if (PENDING.equals(tag.value())) {
                        return ObjectState.PENDING;
                    }
                }
            }
            return ObjectState.UNTAGGED;
        } catch (NoSuchKeyException e) {
            return ObjectState.MISSING;
        }
    }
}
