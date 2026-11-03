package org.hmb.security;

import java.util.Map;

/**
 * The single rule for whether the development-only recruiter header may identify a caller.
 *
 * <p>It is permitted only when <b>both</b> hold:
 * <ol>
 *   <li>{@code ALLOW_DEV_RECRUITER_HEADER=true} (the same flag the other Lambdas use), and</li>
 *   <li>the process is running under {@code sam local} ({@code AWS_SAM_LOCAL=true}, which the SAM CLI sets in its
 *       local containers and AWS never sets in a deployed Lambda).</li>
 * </ol>
 * So in a deployed environment the header can never authorize a request, even if the flag is set by mistake. The same
 * rule gates the development fallback for the upload-signing secret. This is a local-testing aid, not authentication:
 * production authentication (a validated Cognito/JWT identity) is a prerequisite before these APIs are exposed.
 */
public final class DevAuth {

    public static final String FLAG_VAR = "ALLOW_DEV_RECRUITER_HEADER";
    public static final String SAM_LOCAL_VAR = "AWS_SAM_LOCAL";

    private DevAuth() {
    }

    public static boolean permitted(Map<String, String> env) {
        return "true".equalsIgnoreCase(env.get(FLAG_VAR)) && "true".equalsIgnoreCase(env.get(SAM_LOCAL_VAR));
    }
}
