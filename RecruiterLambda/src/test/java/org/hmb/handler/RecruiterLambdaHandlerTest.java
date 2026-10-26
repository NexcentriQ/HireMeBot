package org.hmb.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hmb.TestData;
import org.hmb.dto.RecruiterResponse;
import org.hmb.service.ApplicationService;
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

    private RecruiterLambdaHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RecruiterLambdaHandler(service, true);
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
        handler = new RecruiterLambdaHandler(service, false);

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
}
