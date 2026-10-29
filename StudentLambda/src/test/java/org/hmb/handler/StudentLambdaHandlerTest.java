package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.dto.MonthlyProgressDto;
import org.hmb.dto.StudentApplicationDto;
import org.hmb.dto.StudentApplicationsResponse;
import org.hmb.dto.StudentStatisticsDto;
import org.hmb.dto.TodayProgressDto;
import org.hmb.dto.WeeklyActivityDto;
import org.hmb.exception.ApiException;
import org.hmb.service.StudentApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentLambdaHandlerTest {

    private static final String PATH = "/api/v1/student/applications";
    private static final long STUDENT_ID = 1000000002L;
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

    private APIGatewayV2HTTPResponse get(Map<String, String> headers) {
        return get(headers, null);
    }

    private static StudentStatisticsDto sampleStatistics() {
        return new StudentStatisticsDto("2026-10-29", "America/Chicago",
                new MonthlyProgressDto("2026-10", 820, 1100, 280, 75),
                new TodayProgressDto("2026-10-29", 35, 50, 15, 70),
                new WeeklyActivityDto("2026-10-26", 50, List.of(
                        new WeeklyActivityDto.DayActivity("Mon", "2026-10-26", 50),
                        new WeeklyActivityDto.DayActivity("Tue", "2026-10-27", 44),
                        new WeeklyActivityDto.DayActivity("Wed", "2026-10-28", 50),
                        new WeeklyActivityDto.DayActivity("Thu", "2026-10-29", 38),
                        new WeeklyActivityDto.DayActivity("Fri", "2026-10-30", 0))));
    }

    private static StudentApplicationsResponse sampleResponse() {
        Map<Integer, List<StudentApplicationDto>> pages = new LinkedHashMap<>();
        pages.put(1, List.of(new StudentApplicationDto(1000000035L, "Senior Java Backend Developer", "Amazon",
                "Austin, TX", "https://example.com/jobs/35", "Amazon Careers", "2026-10-29T10:30:00-05:00")));
        return StudentApplicationsResponse.of(new StudentApplicationsResponse.Data(
                STUDENT_ID, "2026-10-29", 1, 20, 1, pages, sampleStatistics()));
    }

    @Test
    void validRequestReturns200WithApplicationsAndStatistics() throws Exception {
        when(service.getApplications(STUDENT_ID)).thenReturn(sampleResponse());

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"));

        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertEquals(STUDENT_ID, data.get("studentId").asLong());
        assertEquals("2026-10-29", data.get("date").asText());
        assertEquals(1, data.get("totalApplications").asInt());
        assertEquals(20, data.get("pageSize").asInt());

        JsonNode first = data.get("pages").get("1").get(0);
        assertEquals(1000000035L, first.get("applicationId").asLong());
        assertEquals("2026-10-29T10:30:00-05:00", first.get("appliedAt").asText());

        JsonNode stats = data.get("statistics");
        assertEquals("2026-10-29", stats.get("asOfDate").asText());
        assertEquals("America/Chicago", stats.get("timezone").asText());
        assertEquals(1100, stats.get("monthlyProgress").get("target").asInt());
        assertEquals(35, stats.get("todayProgress").get("applied").asInt());
        JsonNode days = stats.get("weeklyActivity").get("days");
        assertEquals(5, days.size());
        assertEquals("Mon", days.get(0).get("day").asText());
        assertEquals("2026-10-30", days.get(4).get("date").asText());
    }

    @Test
    void emptyResultIsA200WithEmptyPagesObject() throws Exception {
        when(service.getApplications(anyLong())).thenReturn(StudentApplicationsResponse.of(
                new StudentApplicationsResponse.Data(STUDENT_ID, "2026-10-29", 0, 20, 0,
                        new LinkedHashMap<>(), sampleStatistics())));

        JsonNode data = JSON.readTree(get(studentHeader("1000000002")).getBody()).get("data");

        assertEquals(0, data.get("totalApplications").asInt());
        assertEquals(0, data.get("totalPages").asInt());
        assertTrue(data.get("pages").isObject());
        assertEquals(0, data.get("pages").size());
        // Statistics still carry real historical numbers even when today is empty.
        assertEquals(820, data.get("statistics").get("monthlyProgress").get("applied").asInt());
    }

    @Test
    void takesNoQueryParametersAndIdentifiesTheStudentFromTheHeader() {
        when(service.getApplications(STUDENT_ID)).thenReturn(sampleResponse());

        assertEquals(200, get(studentHeader("1000000002")).getStatusCode());
        assertEquals(200, get(studentHeader("1000000002"), Map.of("unrelated", "x")).getStatusCode());

        verify(service, org.mockito.Mockito.times(2)).getApplications(STUDENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "startDate", "endDate", "month", "week", "page", "size", "DATE", "Page"})
    void deprecatedQueryParametersAreRejectedWith400(String param) throws Exception {
        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"), Map.of(param, "anything"));

        assertEquals(400, response.getStatusCode());
        assertTrue(JSON.readTree(response.getBody()).get("message").asText().contains(param));
        verifyNoInteractions(service);
    }

    @Test
    void missingStudentHeaderIs400() throws Exception {
        APIGatewayV2HTTPResponse response = get(new HashMap<>());

        assertEquals(400, response.getStatusCode());
        assertFalse(JSON.readTree(response.getBody()).get("success").asBoolean());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5", "1.5", "99999999999999999999"})
    void invalidStudentIdIs400(String value) {
        assertEquals(400, get(studentHeader(value)).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void headerNameIsCaseInsensitive() {
        when(service.getApplications(STUDENT_ID)).thenReturn(sampleResponse());
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DEV-STUDENT-ID", " 1000000002 ");

        assertEquals(200, get(headers).getStatusCode());
    }

    @Test
    void devHeaderIsRejectedWhenNotEnabled() {
        handler = new StudentLambdaHandler(service, false);

        assertEquals(403, get(studentHeader("1000000002")).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void studentNotFoundIs404() throws Exception {
        when(service.getApplications(anyLong())).thenThrow(ApiException.notFound("Student not found"));

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"));

        assertEquals(404, response.getStatusCode());
        assertEquals("NOT_FOUND", JSON.readTree(response.getBody()).get("error").asText());
    }

    @Test
    void databaseUnavailableIs503() {
        when(service.getApplications(anyLong())).thenThrow(ApiException.serviceUnavailable(null));

        assertEquals(503, get(studentHeader("1000000002")).getStatusCode());
    }

    @Test
    void unexpectedExceptionIs500AndLeaksNothing() {
        when(service.getApplications(anyLong()))
                .thenThrow(new IllegalStateException("password=hunter2 jdbc:mysql://internal-host"));

        APIGatewayV2HTTPResponse response = get(studentHeader("1000000002"));

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
        when(service.getApplications(STUDENT_ID)).thenReturn(sampleResponse());

        assertEquals(200, handler.handleRequest(
                event("GET", PATH + "/", studentHeader("1000000002"), null), null).getStatusCode());
    }
}
