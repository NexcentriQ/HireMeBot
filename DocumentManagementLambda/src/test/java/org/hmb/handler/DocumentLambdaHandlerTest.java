package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.Fixtures;
import org.hmb.dto.DocumentType;
import org.hmb.dto.RegisterRequest;
import org.hmb.dto.RegisterResponse;
import org.hmb.dto.UploadUrlRequest;
import org.hmb.dto.UploadUrlResponse;
import org.hmb.exception.ApiException;
import org.hmb.service.DocumentUploadService;
import org.hmb.service.DocumentUploadService.RegisterOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentLambdaHandlerTest {

    private static final String UPLOAD_URL_PATH = "/api/v1/documents/upload-url";
    private static final String REGISTER_PATH = "/api/v1/documents/register";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String UPLOAD_BODY = """
            {"studentId": 1000000005, "documentType": "RESUME", "fileName": "Akhil_Resume.pdf",
             "contentType": "application/pdf", "fileSize": 245760,
             "checksum": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}
            """;

    private static final String REGISTER_BODY = """
            {"studentId": 1000000005, "documentType": "RESUME",
             "objectKey": "students/1000000005/resumes/123e4567-e89b-12d3-a456-426614174000.pdf",
             "checksum": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
             "uploadToken": "token-value"}
            """;

    @Mock DocumentUploadService service;

    private DocumentLambdaHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DocumentLambdaHandler(service, true);
    }

    private static Map<String, String> recruiterHeader(String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dev-recruiter-id", value);
        return headers;
    }

    private static APIGatewayV2HTTPEvent event(String method, String path, Map<String, String> headers, String body) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method).withPath(path).build())
                        .build())
                .build();
    }

    private APIGatewayV2HTTPResponse post(String path, Map<String, String> headers, String body) {
        return handler.handleRequest(event("POST", path, headers, body), null);
    }

    private static JsonNode body(APIGatewayV2HTTPResponse response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    // ---- upload-url ---------------------------------------------------------------------------------------------

    @Test
    void uploadUrlReturnsTheContractWhenAnUploadIsRequired() throws Exception {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), eq(Fixtures.RECRUITER_ID)))
                .thenReturn(UploadUrlResponse.uploadRequired("students/1000000005/resumes/u.pdf",
                        "https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc", "the-upload-token", 300,
                        Map.of("Content-Type", "application/pdf", "x-amz-checksum-sha256", Fixtures.SHA256_BASE64,
                                "x-amz-tagging", "doc-state=pending")));

        APIGatewayV2HTTPResponse response = post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), UPLOAD_BODY);

        assertEquals(200, response.getStatusCode());
        JsonNode json = body(response);
        assertTrue(json.get("success").asBoolean());
        assertEquals("UPLOAD_REQUIRED", json.get("status").asText());
        assertTrue(json.get("uploadRequired").asBoolean());
        assertEquals("students/1000000005/resumes/u.pdf", json.get("objectKey").asText());
        assertEquals("https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc", json.get("uploadUrl").asText());
        assertEquals("the-upload-token", json.get("uploadToken").asText());
        assertEquals(300, json.get("expiresIn").asInt());
        assertEquals("application/pdf", json.get("requiredHeaders").get("Content-Type").asText());
        assertFalse(json.has("objectId"), "fields that do not apply are omitted");
    }

    @Test
    void uploadUrlReturnsTheMinimalBodyWhenTheDocumentAlreadyExists() throws Exception {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), anyLong()))
                .thenReturn(UploadUrlResponse.alreadyExists(1000000001L));

        JsonNode json = body(post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), UPLOAD_BODY));

        assertEquals("ALREADY_EXISTS", json.get("status").asText());
        assertFalse(json.get("uploadRequired").asBoolean());
        assertEquals(1000000001L, json.get("objectId").asLong());
        assertFalse(json.has("uploadUrl"));
        assertFalse(json.has("uploadToken"));
        assertFalse(json.has("objectKey"));
    }

    @Test
    void theRecruiterIsTheAuthenticatedIdentityNeverSomethingFromTheBody() {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), anyLong()))
                .thenReturn(UploadUrlResponse.alreadyExists(1L));

        post(UPLOAD_URL_PATH, recruiterHeader(" 1000000001 "), UPLOAD_BODY);

        ArgumentCaptor<UploadUrlRequest> request = ArgumentCaptor.forClass(UploadUrlRequest.class);
        verify(service).requestUploadUrl(request.capture(), eq(1000000001L));
        assertEquals(Fixtures.STUDENT_ID, request.getValue().studentId());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", request.getValue().checksum());
    }

    @Test
    void aRecruiterIdInTheBodyIsRejectedNotTrusted() {
        String body = UPLOAD_BODY.replaceFirst("\\{", "{\"recruiterId\": 1000000001,");

        APIGatewayV2HTTPResponse response = post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), body);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(service);
    }

    // ---- register -----------------------------------------------------------------------------------------------

    @Test
    void registerReturns201WhenItCreatedTheDocument() throws Exception {
        when(service.registerDocument(any(RegisterRequest.class), eq(Fixtures.RECRUITER_ID))).thenReturn(
                new RegisterOutcome(RegisterResponse.success(1000000002L, Fixtures.STUDENT_ID, DocumentType.RESUME), true));

        APIGatewayV2HTTPResponse response = post(REGISTER_PATH, recruiterHeader("1000000001"), REGISTER_BODY);

        assertEquals(201, response.getStatusCode());
        JsonNode json = body(response);
        assertTrue(json.get("success").asBoolean());
        assertEquals("SUCCESS", json.get("status").asText());
        assertEquals(1000000002L, json.get("objectId").asLong());
        assertEquals(1000000005L, json.get("studentId").asLong());
        assertEquals("RESUME", json.get("documentType").asText());
        assertEquals("Resume uploaded successfully", json.get("message").asText());
    }

    @Test
    void registerReturns200WithTheSameBodyForAReplay() throws Exception {
        when(service.registerDocument(any(RegisterRequest.class), anyLong())).thenReturn(
                new RegisterOutcome(RegisterResponse.success(1000000002L, Fixtures.STUDENT_ID, DocumentType.RESUME), false));

        APIGatewayV2HTTPResponse response = post(REGISTER_PATH, recruiterHeader("1000000001"), REGISTER_BODY);

        assertEquals(200, response.getStatusCode());
        assertEquals(1000000002L, body(response).get("objectId").asLong());
    }

    @Test
    void serviceErrorsKeepTheirStatusAndUseTheProjectEnvelope() throws Exception {
        when(service.registerDocument(any(RegisterRequest.class), anyLong()))
                .thenThrow(new ApiException(409, "DUPLICATE_DOCUMENT", "This document is already registered."));

        APIGatewayV2HTTPResponse response = post(REGISTER_PATH, recruiterHeader("1000000001"), REGISTER_BODY);

        assertEquals(409, response.getStatusCode());
        JsonNode json = body(response);
        assertFalse(json.get("success").asBoolean());
        assertEquals("DUPLICATE_DOCUMENT", json.get("error").asText());
        assertEquals("This document is already registered.", json.get("message").asText());
    }

    @Test
    void validationDetailsAreReturned() throws Exception {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), anyLong()))
                .thenThrow(ApiException.validation(List.of("checksum is required")));

        JsonNode json = body(post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), UPLOAD_BODY));

        assertEquals("VALIDATION_ERROR", json.get("error").asText());
        assertEquals("checksum is required", json.get("details").get(0).asText());
    }

    // ---- authentication: local development only -----------------------------------------------------------------

    @Test
    void whenDevAuthIsNotPermittedEveryCallIsForbiddenEvenWithAValidHeader() throws Exception {
        handler = new DocumentLambdaHandler(service, false);

        APIGatewayV2HTTPResponse upload = post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), UPLOAD_BODY);
        APIGatewayV2HTTPResponse register = post(REGISTER_PATH, recruiterHeader("1000000001"), REGISTER_BODY);

        assertEquals(403, upload.getStatusCode());
        assertEquals(403, register.getStatusCode());
        assertEquals("Recruiter authentication is not configured", body(upload).get("message").asText());
        verifyNoInteractions(service);
    }

    @Test
    void aMissingRecruiterHeaderIs400() {
        assertEquals(400, post(UPLOAD_URL_PATH, new HashMap<>(), UPLOAD_BODY).getStatusCode());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5", "1.5", "99999999999999999999", "", "  "})
    void anInvalidRecruiterIdIs400(String value) {
        assertEquals(400, post(UPLOAD_URL_PATH, recruiterHeader(value), UPLOAD_BODY).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void theHeaderNameIsCaseInsensitive() {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), anyLong()))
                .thenReturn(UploadUrlResponse.alreadyExists(1L));
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DEV-RECRUITER-ID", "1000000001");

        assertEquals(200, post(UPLOAD_URL_PATH, headers, UPLOAD_BODY).getStatusCode());
    }

    // ---- request parsing ----------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "[]", "\"text\"", "{\"studentId\": \"1000000005\"}", "{\"studentId\": 1.5}",
            "{\"studentId\": 1} trailing", ""})
    void malformedOrMistypedJsonIs400(String body) {
        APIGatewayV2HTTPResponse response = post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), body);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void aMissingBodyIs400() {
        assertEquals(400, post(REGISTER_PATH, recruiterHeader("1000000001"), null).getStatusCode());
    }

    // ---- routing ------------------------------------------------------------------------------------------------

    @Test
    void unknownPathsAre404() {
        assertEquals(404, post("/api/v1/documents/download", recruiterHeader("1000000001"), "{}").getStatusCode());
        assertEquals(404, post("/documents/upload-url", recruiterHeader("1000000001"), "{}").getStatusCode());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH"})
    void onlyPostIsAllowedAndThereAreNoGetOrDeleteDocumentRoutes(String method) {
        for (String path : List.of(UPLOAD_URL_PATH, REGISTER_PATH)) {
            APIGatewayV2HTTPResponse response = handler.handleRequest(
                    event(method, path, recruiterHeader("1000000001"), null), null);

            assertEquals(405, response.getStatusCode());
            assertEquals("POST", response.getHeaders().get("Allow"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void aTrailingSlashIsAccepted() {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), anyLong()))
                .thenReturn(UploadUrlResponse.alreadyExists(1L));

        assertEquals(200, post(UPLOAD_URL_PATH + "/", recruiterHeader("1000000001"), UPLOAD_BODY).getStatusCode());
    }

    // ---- responses are credentials-bearing: never cached, never leak --------------------------------------------

    @Test
    void everyResponseIsMarkedNoStoreAndJson() {
        when(service.requestUploadUrl(any(UploadUrlRequest.class), anyLong()))
                .thenReturn(UploadUrlResponse.alreadyExists(1L));

        for (APIGatewayV2HTTPResponse response : List.of(
                post(UPLOAD_URL_PATH, recruiterHeader("1000000001"), UPLOAD_BODY),           // 200
                post(UPLOAD_URL_PATH, new HashMap<>(), UPLOAD_BODY),                         // 400
                post("/nope", recruiterHeader("1000000001"), "{}"))) {                       // 404
            assertEquals("no-store", response.getHeaders().get("Cache-Control"));
            assertEquals("application/json", response.getHeaders().get("Content-Type"));
        }
    }

    @Test
    void anUnexpectedFailureIs500AndLeaksNothing() throws Exception {
        when(service.registerDocument(any(RegisterRequest.class), anyLong()))
                .thenThrow(new IllegalStateException("password=hunter2 jdbc:mysql://internal-host token=abc"));

        APIGatewayV2HTTPResponse response = post(REGISTER_PATH, recruiterHeader("1000000001"), REGISTER_BODY);

        assertEquals(500, response.getStatusCode());
        assertFalse(response.getBody().contains("hunter2"));
        assertFalse(response.getBody().contains("internal-host"));
        assertEquals("INTERNAL_ERROR", body(response).get("error").asText());
    }
}
