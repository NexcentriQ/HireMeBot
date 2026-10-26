package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.dto.StudentApplicationDto;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.exception.ApiException;
import org.hmb.service.StudentApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentLambdaHandlerTest {

    private static final String PATH = "/api/v1/student/applications";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock StudentApplicationService service;

    private StudentLambdaHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StudentLambdaHandler(service, true);
    }

    private static Map<String, String> studentHeader(String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dev-student-id", value);
        return headers;
    }

    private static APIGatewayV2HTTPEvent event(String method, String path, Map<String, String> headers,
                                               Map<String, String> query) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withQueryStringParameters(query)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method).withPath(path).build())
                        .build())
                .build();
    }

    private APIGatewayV2HTTPResponse get(Map<String, String> headers, Map<String, String> query) {
        return handler.handleRequest(event("GET", PATH, headers, query), null);
    }

    private static StudentApplicationsResponse sampleResponse() {
        Map<Integer, List<StudentApplicationDto>> pages = new LinkedHashMap<>();
        pages.put(1, List.of(new StudentApplicationDto(1000000001L, "Senior Java Backend Developer", "Amazon",
                "Austin, TX", "https://example.com/jobs/50", "Amazon Careers", "2026-10-25T15:30:00Z")));
        return StudentApplicationsResponse.of(new StudentApplicationsResponse.Data(
                1000000002L, "2026-10-25", 1, 20, 1, pages));
    }

    @Test
    void validRequestReturns200WithPagesObjectKeyedByPageNumber() throws Exception {
        when(service.getApplications(1000000002L, LocalDate.of(2026, 10, 25))).thenReturn(sampleResponse());

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"), Map.of("date", "2026-10-25"));

        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode json = JSON.readTree(response.getBody());
        assertTrue(json.get("success").asBoolean());
        JsonNode data = json.get("data");
        assertEquals(1000000002L, data.get("studentId").asLong());
        assertEquals("2026-10-25", data.get("date").asText());
        assertEquals(1, data.get("totalApplications").asInt());
        assertEquals(20, data.get("pageSize").asInt());
        assertEquals(1, data.get("totalPages").asInt());
        JsonNode first = data.get("pages").get("1").get(0);
        assertEquals(1000000001L, first.get("applicationId").asLong());
        assertEquals("Amazon", first.get("companyName").asText());
        assertEquals("2026-10-25T15:30:00Z", first.get("appliedAt").asText());
    }

    @Test
    void emptyResultIsA200WithEmptyPagesObject() throws Exception {
        when(service.getApplications(anyLong(), any())).thenReturn(StudentApplicationsResponse.of(
                new StudentApplicationsResponse.Data(1000000002L, "2026-10-25", 0, 20, 0, new LinkedHashMap<>())));

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"), Map.of("date", "2026-10-25"));

        assertEquals(200, response.getStatusCode());
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertEquals(0, data.get("totalApplications").asInt());
        assertEquals(0, data.get("totalPages").asInt());
        assertTrue(data.get("pages").isObject());
        assertEquals(0, data.get("pages").size());
    }

    @Test
    void omittedDateIsPassedToTheServiceAsNull() {
        when(service.getApplications(anyLong(), isNull())).thenReturn(sampleResponse());

        assertEquals(200, get(studentHeader("1000000002"), null).getStatusCode());
        assertEquals(200, get(studentHeader("1000000002"), Map.of("other", "x")).getStatusCode());

        verify(service, org.mockito.Mockito.times(2)).getApplications(1000000002L, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13-45", "2026-02-30", "25-10-2026", "2026/10/25", "2026-1-5", "abc", "", "   ",
            "2026-10-25,2026-10-26", "0001-01-01", "+12026-01-01"})
    void invalidDateIs400(String date) {
        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"), Map.of("date", date));

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void missingStudentHeaderIs400() throws Exception {
        APIGatewayV2HTTPResponse response = get(new HashMap<>(), Map.of("date", "2026-10-25"));

        assertEquals(400, response.getStatusCode());
        assertFalse(JSON.readTree(response.getBody()).get("success").asBoolean());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5", "1.5", "99999999999999999999"})
    void invalidStudentIdIs400(String value) {
        assertEquals(400, get(studentHeader(value), Map.of("date", "2026-10-25")).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void headerNameIsCaseInsensitive() {
        when(service.getApplications(anyLong(), any())).thenReturn(sampleResponse());
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DEV-STUDENT-ID", " 1000000002 ");

        assertEquals(200, get(headers, Map.of("date", "2026-10-25")).getStatusCode());
    }

    @Test
    void devHeaderIsRejectedWhenNotEnabled() {
        handler = new StudentLambdaHandler(service, false);

        assertEquals(403, get(studentHeader("1000000002"), Map.of("date", "2026-10-25")).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void studentNotFoundIs404() throws Exception {
        when(service.getApplications(anyLong(), any())).thenThrow(ApiException.notFound("Student not found"));

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"), Map.of("date", "2026-10-25"));

        assertEquals(404, response.getStatusCode());
        assertEquals("NOT_FOUND", JSON.readTree(response.getBody()).get("error").asText());
    }

    @Test
    void databaseUnavailableIs503() {
        when(service.getApplications(anyLong(), any())).thenThrow(ApiException.serviceUnavailable(null));

        assertEquals(503, get(studentHeader("1000000002"), Map.of("date", "2026-10-25")).getStatusCode());
    }

    @Test
    void unexpectedExceptionIs500AndLeaksNothing() {
        when(service.getApplications(anyLong(), any()))
                .thenThrow(new IllegalStateException("password=hunter2 jdbc:mysql://internal-host"));

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"), Map.of("date", "2026-10-25"));

        assertEquals(500, response.getStatusCode());
        assertFalse(response.getBody().contains("hunter2"));
        assertFalse(response.getBody().contains("internal-host"));
    }

    @Test
    void unknownPathIs404() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET", "/api/v1/student/other", studentHeader("1000000002"), null), null);

        assertEquals(404, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void unsupportedMethodIs405WithAllowHeader(String method) {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event(method, PATH, studentHeader("1000000002"), null), null);

        assertEquals(405, response.getStatusCode());
        assertEquals("GET", response.getHeaders().get("Allow"));
        verifyNoInteractions(service);
    }

    @Test
    void trailingSlashOnPathIsAccepted() {
        when(service.getApplications(anyLong(), any())).thenReturn(sampleResponse());

        assertEquals(200, handler.handleRequest(
                event("GET", PATH + "/", studentHeader("1000000002"), Map.of("date", "2026-10-25")), null).getStatusCode());
    }
}
