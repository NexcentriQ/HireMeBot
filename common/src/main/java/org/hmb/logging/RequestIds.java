package org.hmb.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The correlation id logged for a request: API Gateway's request id (it matches the gateway's access logs), else the
 * Lambda request id, else a random one. Under {@code sam local} the emulated API Gateway sends the same request id on
 * every call, so the Lambda id is used there.
 */
public final class RequestIds {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9=._-]{1,100}");
    /** Set by the SAM CLI in its local containers; never set by AWS in a deployed Lambda. */
    private static final boolean SAM_LOCAL = "true".equalsIgnoreCase(System.getenv("AWS_SAM_LOCAL"));

    private RequestIds() {
    }

    public static String of(String apiGatewayRequestId, String lambdaRequestId) {
        return of(apiGatewayRequestId, lambdaRequestId, SAM_LOCAL);
    }

    static String of(String apiGatewayRequestId, String lambdaRequestId, boolean samLocal) {
        if (!samLocal && apiGatewayRequestId != null && SAFE.matcher(apiGatewayRequestId).matches()) {
            return apiGatewayRequestId;
        }
        if (lambdaRequestId != null && SAFE.matcher(lambdaRequestId).matches()) {
            return lambdaRequestId;
        }
        return UUID.randomUUID().toString();
    }
}
