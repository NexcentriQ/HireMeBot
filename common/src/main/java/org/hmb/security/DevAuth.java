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
 * rule gates the development fallback for the upload-signing secret, and (with {@link #STUDENT_FLAG_VAR}) the
 * student dashboard's {@code X-Dev-Student-Id} header.
 *
 * <p>This is a local-testing fallback, not authentication. The recruiter APIs authenticate with the Cognito access token
 * ({@link RecruiterAuthenticator}); the header is consulted <b>only</b> when the request carries no {@code Authorization}
 * header at all, so a Bearer token (valid or not) always wins and a bad token is never retried as the header.
 */
public final class DevAuth {

    public static final String FLAG_VAR = "ALLOW_DEV_RECRUITER_HEADER";
    /** The flag for the student dashboard's development header; it is subject to the same {@code AWS_SAM_LOCAL} rule. */
    public static final String STUDENT_FLAG_VAR = "ALLOW_DEV_STUDENT_HEADER";
    public static final String SAM_LOCAL_VAR = "AWS_SAM_LOCAL";

    private DevAuth() {
    }

    public static boolean permitted(Map<String, String> env) {
        return permitted(env, FLAG_VAR);
    }

    /** The same rule for another development header's flag (e.g. {@link #STUDENT_FLAG_VAR}): flag and sam local. */
    public static boolean permitted(Map<String, String> env, String flagVar) {
        return "true".equalsIgnoreCase(env.get(flagVar)) && "true".equalsIgnoreCase(env.get(SAM_LOCAL_VAR));
    }
}
