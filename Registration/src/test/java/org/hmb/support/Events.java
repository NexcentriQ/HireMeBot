package org.hmb.support;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;

import java.util.HashMap;
import java.util.Map;

/** Builds API Gateway HTTP API (payload 2.0) events the way API Gateway sends them (lower-case header names). */
public final class Events {

    public static final String PATH = "/api/v1/students/register";
    public static final String VALID_BODY = """
            {"firstName":"Alex","lastName":"Smith","phoneNumber":"+17135550101","acceptedTerms":true}""";

    private Events() {
    }

    public static APIGatewayV2HTTPEvent register(String accessToken, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        if (accessToken != null) {
            headers.put("authorization", "Bearer " + accessToken);
        }
        return event("POST", PATH, headers, body);
    }

    public static final String ME_PATH = "/api/v1/auth/me";
    public static final String RECRUITER_PATH = "/api/v1/recruiters/register";
    public static final String RECRUITER_ME_PATH = "/api/v1/recruiters/auth/me";

    /** {@code GET /api/v1/recruiters/auth/me} as a browser sends it: no body, no Content-Type. */
    public static APIGatewayV2HTTPEvent recruiterMe(String accessToken) {
        Map<String, String> headers = new HashMap<>();
        if (accessToken != null) {
            headers.put("authorization", "Bearer " + accessToken);
        }
        return event("GET", RECRUITER_ME_PATH, headers, null);
    }

    /** {@code POST /api/v1/recruiters/register} with a JSON body. */
    public static APIGatewayV2HTTPEvent recruiterRegister(String accessToken, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        if (accessToken != null) {
            headers.put("authorization", "Bearer " + accessToken);
        }
        return event("POST", RECRUITER_PATH, headers, body);
    }

    /** {@code GET /api/v1/auth/me} as a browser sends it: no body, no Content-Type. */
    public static APIGatewayV2HTTPEvent me(String accessToken) {
        Map<String, String> headers = new HashMap<>();
        if (accessToken != null) {
            headers.put("authorization", "Bearer " + accessToken);
        }
        return event("GET", ME_PATH, headers, null);
    }

    public static APIGatewayV2HTTPEvent event(String method, String path, Map<String, String> headers, String body) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withIsBase64Encoded(false)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withRequestId("req-test-1")
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method)
                                .withPath(path)
                                .build())
                        .build())
                .build();
    }
}
