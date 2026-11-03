package org.hmb.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hmb.dto.DocumentType;
import org.hmb.exception.ApiException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * Issues and verifies the signed upload-intent token: {@code base64url(payload) + "." + base64url(HMAC-SHA256)}.
 *
 * <p>It is not a JWT. The payload binds the recruiter, student, document type, object key, SHA-256, size, content
 * type and an expiry. It is <em>signed, not encrypted</em>: the caller already knows every value, so nothing secret
 * is in it, but it cannot be altered or forged without the server-side secret. Registration refuses any object key
 * that this server did not authorize.
 *
 * <p>Failures: a token that is malformed, forged, or bound to different values is {@code 403
 * UPLOAD_TOKEN_INVALID}; a correctly signed but expired token is {@code 400 UPLOAD_TOKEN_EXPIRED} (the client should
 * request a fresh upload URL). The token itself is never logged.
 */
public class UploadTokenService {

    private static final String HMAC = "HmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SigningSecretProvider secrets;
    private final Clock clock;

    public UploadTokenService(SigningSecretProvider secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    public String sign(UploadIntent intent) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("r", intent.recruiterId());
        payload.put("s", intent.studentId());
        payload.put("t", intent.documentType().name());
        payload.put("k", intent.objectKey());
        payload.put("c", intent.checksum());
        payload.put("z", intent.size());
        payload.put("m", intent.contentType());
        payload.put("e", intent.expiresAtEpochSeconds());
        String encodedPayload = base64Url(payload.toString().getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + base64Url(hmac(encodedPayload));
    }

    /**
     * @return the authorized intent
     * @throws ApiException 403 {@code UPLOAD_TOKEN_INVALID} or 400 {@code UPLOAD_TOKEN_EXPIRED}
     */
    public UploadIntent verify(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw invalid();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw invalid();
        }
        byte[] providedSignature;
        try {
            providedSignature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        // Constant-time comparison; the signature is checked before the payload is even parsed.
        if (!MessageDigest.isEqual(hmac(parts[0]), providedSignature)) {
            throw invalid();
        }

        UploadIntent intent;
        try {
            JsonNode node = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            intent = new UploadIntent(
                    node.get("r").longValue(),
                    node.get("s").longValue(),
                    DocumentType.valueOf(node.get("t").textValue()),
                    node.get("k").textValue(),
                    node.get("c").textValue(),
                    node.get("z").longValue(),
                    node.get("m").textValue(),
                    node.get("e").longValue());
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }

        if (clock.instant().getEpochSecond() >= intent.expiresAtEpochSeconds()) {
            throw new ApiException(400, "UPLOAD_TOKEN_EXPIRED",
                    "Upload authorization has expired. Request a new upload URL.");
        }
        return intent;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secrets.secret(), HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalid() {
        return new ApiException(403, "UPLOAD_TOKEN_INVALID",
                "Upload authorization is not valid. Request a new upload URL.");
    }
}
