package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.TestData;
import org.hmb.dto.AssignedStudentDto;
import org.hmb.dto.AssignedStudentsResponse;
import org.hmb.dto.RecruiterResponse;
import org.hmb.exception.ApiException;
import org.hmb.service.ApplicationService;
import org.hmb.service.RecruiterStudentService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecruiterLambdaHandlerTest {

    private static final String PATH = "/api/v1/recruiter/applications";

    @Mock ApplicationService service;
    @Mock RecruiterStudentService studentService;

    private RecruiterLambdaHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RecruiterLambdaHandler(service, studentService, true);
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

    private APIGatewayV2HTTPResponse post(Map<String, String> headers, String body) {
        return handler.handleRequest(event("POST", PATH, headers, body), null);
    }

    @Test
    void validRequestReturns201() throws Exception {
        when(service.createApplication(eq(1000000001L), any())).thenReturn(RecruiterResponse.created(1000000042L));

        APIGatewayV2HTTPResponse response = post(recruiterHeader("1000000001"), TestData.VALID_JSON);

        assertEquals(201, response.getStatusCode());
        JsonNode json = new ObjectMapper().readTree(response.getBody());
        assertTrue(json.get("success").asBoolean());
        assertEquals(1000000042L, json.get("applicationId").asLong());
    }

    @Test
    void missingRecruiterHeaderIs400() {
        assertEquals(400, post(new HashMap<>(), TestData.VALID_JSON).getStatusCode());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5"})
    void invalidRecruiterIdIs400(String value) {
        assertEquals(400, post(recruiterHeader(value), TestData.VALID_JSON).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void devHeaderIsRejectedWhenNotEnabled() {
        handler = new RecruiterLambdaHandler(service, studentService, false);

        assertEquals(403, post(recruiterHeader("1000000001"), TestData.VALID_JSON).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void recruiterIdInBodyIs400() {
        String json = TestData.VALID_JSON.replaceFirst("\\{", "{\"recruiterId\": 1000000001,");

        assertEquals(400, post(recruiterHeader("1000000001"), json).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonIs400() {
        assertEquals(400, post(recruiterHeader("1000000001"), "{not json").getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void unknownPathIs404AndWrongMethodIs405() {
        assertEquals(404, handler.handleRequest(
                event("POST", "/api/v1/other", recruiterHeader("1000000001"), TestData.VALID_JSON), null).getStatusCode());
        assertEquals(405, handler.handleRequest(
                event("GET", PATH, recruiterHeader("1000000001"), null), null).getStatusCode());
        verifyNoInteractions(service);
    }

    // ---- GET /api/v1/recruiter/students ---------------------------------------------------------------------

    private static final String STUDENTS_PATH = "/api/v1/recruiter/students";
    private static final ObjectMapper JSON = new ObjectMapper();

    private APIGatewayV2HTTPResponse getStudents(Map<String, String> headers) {
        return handler.handleRequest(event("GET", STUDENTS_PATH, headers, null), null);
    }

    private static AssignedStudentsResponse threeStudents() {
        return AssignedStudentsResponse.of(1000000001L, List.of(
                AssignedStudentDto.of(1000000004L, "Michael", "Brown", "michael.brown@example.com",
                        LocalDateTime.parse("2026-10-22T14:45:00")),
                AssignedStudentDto.of(1000000002L, "John", "Smith", "john.smith@example.com",
                        LocalDateTime.parse("2026-10-20T09:30:00")),
                AssignedStudentDto.of(1000000003L, "Sarah", "Johnson", "sarah.johnson@example.com",
                        LocalDateTime.parse("2026-10-21T11:15:00"))));
    }

    @Test
    void getStudentsRouteIsRecognizedAndReturnsTheContract() throws Exception {
        when(studentService.getAssignedStudents(1000000001L)).thenReturn(threeStudents());

        APIGatewayV2HTTPResponse response = getStudents(recruiterHeader("1000000001"));

        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        JsonNode root = JSON.readTree(response.getBody());
        assertTrue(root.get("success").asBoolean());
        JsonNode data = root.get("data");
        assertEquals(1000000001L, data.get("recruiterId").asLong());
        assertEquals(3, data.get("totalStudents").asInt());
        JsonNode first = data.get("students").get(0);
        assertEquals(1000000004L, first.get("studentId").asLong());
        assertEquals("Michael", first.get("firstName").asText());
        assertEquals("Brown", first.get("lastName").asText());
        assertEquals("Michael Brown", first.get("fullName").asText());
        assertEquals("michael.brown@example.com", first.get("email").asText());
        assertEquals("2026-10-22T14:45:00Z", first.get("assignedAt").asText());
        // Nothing beyond the documented fields is exposed.
        assertEquals(6, first.size());
        verifyNoInteractions(service);
    }

    @Test
    void getStudentsWithNoAssignmentsIs200WithAnEmptyArray() throws Exception {
        when(studentService.getAssignedStudents(1000000001L))
                .thenReturn(AssignedStudentsResponse.of(1000000001L, List.of()));

        APIGatewayV2HTTPResponse response = getStudents(recruiterHeader("1000000001"));

        assertEquals(200, response.getStatusCode());
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertEquals(0, data.get("totalStudents").asInt());
        assertTrue(data.get("students").isArray());
        assertEquals(0, data.get("students").size());
    }

    @Test
    void getStudentsHeaderNameIsCaseInsensitive() {
        when(studentService.getAssignedStudents(1000000001L)).thenReturn(threeStudents());
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DEV-RECRUITER-ID", " 1000000001 ");

        assertEquals(200, getStudents(headers).getStatusCode());
    }

    @Test
    void getStudentsTrailingSlashIsAccepted() {
        when(studentService.getAssignedStudents(1000000001L)).thenReturn(threeStudents());

        assertEquals(200, handler.handleRequest(
                event("GET", STUDENTS_PATH + "/", recruiterHeader("1000000001"), null), null).getStatusCode());
    }

    @Test
    void getStudentsMissingHeaderIs400() {
        assertEquals(400, getStudents(new HashMap<>()).getStatusCode());
        verifyNoInteractions(studentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-5", "1.5", "99999999999999999999"})
    void getStudentsInvalidRecruiterIdIs400(String value) {
        assertEquals(400, getStudents(recruiterHeader(value)).getStatusCode());
        verifyNoInteractions(studentService);
    }

    @Test
    void getStudentsFailsClosedWhenTheDevHeaderIsNotEnabled() {
        handler = new RecruiterLambdaHandler(service, studentService, false);

        assertEquals(403, getStudents(recruiterHeader("1000000001")).getStatusCode());
        verifyNoInteractions(studentService);
    }

    @Test
    void recruiterIdInTheQueryStringIsIgnoredNotTrusted() {
        // The recruiter always comes from the header, never from a query parameter.
        when(studentService.getAssignedStudents(1000000001L)).thenReturn(threeStudents());
        var event = APIGatewayV2HTTPEvent.builder()
                .withRawPath(STUDENTS_PATH)
                .withHeaders(recruiterHeader("1000000001"))
                .withQueryStringParameters(Map.of("recruiterId", "1000000099"))
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod("GET").withPath(STUDENTS_PATH).build())
                        .build())
                .build();

        assertEquals(200, handler.handleRequest(event, null).getStatusCode());
        org.mockito.Mockito.verify(studentService).getAssignedStudents(1000000001L);
        org.mockito.Mockito.verify(studentService, org.mockito.Mockito.never()).getAssignedStudents(1000000099L);
    }

    @Test
    void getStudentsRecruiterNotActiveIs403() throws Exception {
        when(studentService.getAssignedStudents(1000000001L))
                .thenThrow(ApiException.forbidden("Recruiter does not exist or is not active"));

        APIGatewayV2HTTPResponse response = getStudents(recruiterHeader("1000000001"));

        assertEquals(403, response.getStatusCode());
        assertEquals("FORBIDDEN", JSON.readTree(response.getBody()).get("error").asText());
    }

    @Test
    void getStudentsDatabaseUnavailableIs503() {
        when(studentService.getAssignedStudents(1000000001L)).thenThrow(ApiException.serviceUnavailable(null));

        assertEquals(503, getStudents(recruiterHeader("1000000001")).getStatusCode());
    }

    @Test
    void getStudentsUnexpectedFailureIs500AndLeaksNothing() {
        when(studentService.getAssignedStudents(1000000001L))
                .thenThrow(new IllegalStateException("password=hunter2 jdbc:mysql://internal-host"));

        APIGatewayV2HTTPResponse response = getStudents(recruiterHeader("1000000001"));

        assertEquals(500, response.getStatusCode());
        assertTrue(!response.getBody().contains("hunter2"));
        assertTrue(!response.getBody().contains("internal-host"));
    }

    // ---- routing is by method AND path ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void studentsRouteAllowsOnlyGet(String method) {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event(method, STUDENTS_PATH, recruiterHeader("1000000001"), null), null);

        assertEquals(405, response.getStatusCode());
        assertEquals("GET", response.getHeaders().get("Allow"));
        verifyNoInteractions(studentService, service);
    }

    @Test
    void applicationsRouteStillAllowsOnlyPostAndAdvertisesIt() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET", PATH, recruiterHeader("1000000001"), null), null);

        assertEquals(405, response.getStatusCode());
        assertEquals("POST", response.getHeaders().get("Allow"));
        verifyNoInteractions(studentService, service);
    }

    @Test
    void postStillReachesTheApplicationServiceNotTheStudentService() {
        when(service.createApplication(eq(1000000001L), any())).thenReturn(RecruiterResponse.created(1000000042L));

        assertEquals(201, post(recruiterHeader("1000000001"), TestData.VALID_JSON).getStatusCode());
        verifyNoInteractions(studentService);
    }

    @Test
    void unknownRecruiterPathIs404() {
        assertEquals(404, handler.handleRequest(
                event("GET", "/api/v1/recruiter/other", recruiterHeader("1000000001"), null), null).getStatusCode());
        verifyNoInteractions(studentService, service);
    }
}
