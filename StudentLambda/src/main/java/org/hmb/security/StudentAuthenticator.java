package org.hmb.security;

import org.hmb.exception.ApiException;
import org.hmb.model.AccountStatus;
import org.hmb.model.IdentityProvider;
import org.hmb.repository.StudentIdentityRepository;
import org.hmb.repository.StudentIdentityRepository.StudentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies the acting student for {@code GET /api/v1/student/applications}. It is the single place that turns a request
 * into a student id; the handler uses only the id returned here, so nothing the client sends (a student id in a header,
 * query or body, an email) can pick the actor. It mirrors {@link RecruiterAuthenticator}.
 *
 * <h2>Order of decisions</h2>
 * <ol>
 *   <li><b>An {@code Authorization} header is present</b> (whatever it contains): the request is authenticated by that
 *       token <em>only</em>. It must be a Bearer token that passes {@link TokenVerifier} (RS256 by the pool's JWKS, exact
 *       issuer and app client, {@code token_use=access}, not expired, {@code openid} scope). Anything else is
 *       <b>401</b>. The development header is never consulted, so a bad token cannot be retried as the header. When API
 *       Gateway's authorizer ran, its {@code sub} claim must equal the verified token's.</li>
 *   <li><b>No {@code Authorization} header</b> and {@link DevAuth#permitted(Map, String)} for
 *       {@link DevAuth#STUDENT_FLAG_VAR} (the flag <em>and</em> {@code AWS_SAM_LOCAL}, i.e. a local {@code sam local}
 *       run): {@code X-Dev-Student-Id} identifies the caller (400 when missing or malformed).</li>
 *   <li>Otherwise <b>401</b>. In a deployed stack the header is therefore ignored even if sent, with or without a flag.</li>
 * </ol>
 *
 * <h2>Token to student</h2>
 * The verified Cognito {@code sub} is looked up in {@code user_identities} ({@link StudentIdentityRepository}, which
 * joins {@code users} and {@code students}). The account must be a usable student, otherwise <b>403</b> {@code FORBIDDEN}
 * with a safe message: not deleted ({@code is_deleted = FALSE}), a {@code students} row (a recruiter, manager or
 * role-less user is refused, never converted), not {@code SUSPENDED}, a {@code GOOGLE} identity (as for
 * {@code /api/v1/auth/me}), and {@code account_status = ACTIVE} (a {@code PENDING} student is refused). The status is read
 * on every request, so a suspension takes effect on the next call. Nothing is ever created or updated.
 *
 * <p>A database or key-fetch outage is a 503. Tokens, emails and other personal data are never logged.
 */
public final class StudentAuthenticator {

    public static final String DEV_STUDENT_HEADER = "X-Dev-Student-Id";

    private static final Logger LOG = LoggerFactory.getLogger(StudentAuthenticator.class);
    private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8 * 1024 + 16;
    /** RFC 6750 b64token after a case-insensitive "Bearer" scheme. */
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer +([A-Za-z0-9._~+/-]+=*)");

    /** The authenticated student: the internal {@code students.student_id} (= {@code users.user_id}). */
    public record Principal(long studentId) {
    }

    private final TokenVerifier verifier;
    private final DataSource dataSource;
    private final StudentIdentityRepository identities;
    private final boolean devHeaderPermitted;

    /**
     * @param verifier           verifies bearer tokens; {@code null} only for the header-only local/test mode, in which
     *                           any request that carries a token is refused with 401 (never treated as header auth)
     * @param devHeaderPermitted whether the development header may identify a caller when no token is sent (see
     *                           {@link DevAuth#permitted(Map, String)})
     */
    public StudentAuthenticator(TokenVerifier verifier, DataSource dataSource, StudentIdentityRepository identities,
                                boolean devHeaderPermitted) {
        this.verifier = verifier;
        this.dataSource = dataSource;
        this.identities = identities;
        this.devHeaderPermitted = devHeaderPermitted;
    }

    /** Header-only mode for handler tests: no token verification, no database. */
    public static StudentAuthenticator devHeaderOnly(boolean devHeaderPermitted) {
        return new StudentAuthenticator(null, null, null, devHeaderPermitted);
    }

    /**
     * The production authenticator, built from the Lambda's environment: {@code COGNITO_REGION},
     * {@code COGNITO_USER_POOL_ID}, {@code COGNITO_CLIENT_ID} (validated; a bad value fails closed) and the dev-header
     * gate {@code DevAuth.permitted(env, ALLOW_DEV_STUDENT_HEADER)}.
     */
    public static StudentAuthenticator fromEnvironment(Map<String, String> env, DataSource dataSource, Clock clock) {
        CognitoAuthConfig config = CognitoAuthConfig.fromEnv(env);
        boolean devPermitted = DevAuth.permitted(env, DevAuth.STUDENT_FLAG_VAR);
        if ("true".equalsIgnoreCase(env.get(DevAuth.STUDENT_FLAG_VAR)) && !devPermitted) {
            LOG.warn("{}=true is ignored: the development header is accepted only under sam local",
                    DevAuth.STUDENT_FLAG_VAR);
        }
        LOG.info("Student authentication: Cognito access tokens (issuer {}); development header permitted={}",
                config.issuer(), devPermitted);
        return new StudentAuthenticator(JwtAccessTokenVerifier.forConfig(config, clock), dataSource,
                new StudentIdentityRepository(), devPermitted);
    }

    /**
     * @param requestHeaders   the request headers (names are matched case-insensitively)
     * @param authorizerClaims the claims of API Gateway's JWT authorizer, if it ran (may be null or empty)
     * @throws ApiException 401 no/invalid/expired/ID/foreign token, 403 not an active student, 400 malformed
     *                      development header (local only), 503 database or Cognito keys unavailable
     */
    public Principal authenticate(Map<String, String> requestHeaders, Map<String, String> authorizerClaims) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (requestHeaders != null) {
            headers.putAll(requestHeaders);
        }
        String authorization = headers.get("Authorization");
        if (authorization != null) {
            return fromBearerToken(authorization, authorizerClaims);
        }
        if (devHeaderPermitted) {
            return fromDevelopmentHeader(headers);
        }
        LOG.info("Rejected request: no Authorization header");
        throw ApiException.unauthorized();
    }

    // ---- Cognito access token ------------------------------------------------------------------------------------

    private Principal fromBearerToken(String authorization, Map<String, String> authorizerClaims) {
        if (authorization.length() > MAX_AUTHORIZATION_HEADER_LENGTH) {
            LOG.info("Rejected request: oversized Authorization header");
            throw ApiException.unauthorized();
        }
        Matcher matcher = BEARER.matcher(authorization.strip());
        if (!matcher.matches()) {
            LOG.info("Rejected request: Authorization header is not a Bearer token");
            throw ApiException.unauthorized();
        }
        if (verifier == null) {
            LOG.error("Rejected request: a token was sent but no token verifier is configured");
            throw ApiException.unauthorized();
        }
        VerifiedAccessToken token = verifier.verify(new BearerToken(matcher.group(1)));

        String authorizerSubject = authorizerClaims == null ? null : authorizerClaims.get("sub");
        if (authorizerSubject != null && !authorizerSubject.equals(token.subject())) {
            LOG.warn("Security: authorizer subject does not match the verified access token");
            throw ApiException.unauthorized();
        }
        return studentFor(token.subject());
    }

    private Principal studentFor(String cognitoSubject) {
        Optional<StudentIdentity> found;
        try (Connection connection = dataSource.getConnection()) {
            found = identities.findByCognitoSub(connection, cognitoSubject);
        } catch (SQLException e) {
            throw databaseFailure(e);
        }
        if (found.isEmpty()) {
            LOG.info("Student authentication refused: the Cognito identity has no HireMeBot account");
            throw ApiException.forbidden("This account is not registered as a student");
        }
        StudentIdentity account = found.get();
        // Same precedence as AccountRules (Registration): deleted, not a student, suspended, not Google, pending.
        String refusal = null;
        if (account.deleted()) {
            refusal = "This account is unavailable";
        } else if (!account.student()) {
            refusal = "This account is not a student account";
        } else if (account.status() == AccountStatus.SUSPENDED) {
            refusal = "Your student account has been suspended";
        } else if (account.provider() != IdentityProvider.GOOGLE) {
            refusal = "A Google sign-in is required";
        } else if (account.status() != AccountStatus.ACTIVE) {
            refusal = "Your student account is waiting for activation";
        }
        if (refusal != null) {
            LOG.info("Student authentication refused for user {}: {}", account.userId(), refusal);
            throw ApiException.forbidden(refusal);
        }
        return new Principal(account.userId());
    }

    private static ApiException databaseFailure(SQLException e) {
        if (isConnectivityProblem(e)) {
            LOG.error("Database unavailable during authentication: {} (SQLState={})", e.getClass().getSimpleName(),
                    e.getSQLState());
            return ApiException.serviceUnavailable(e);
        }
        LOG.error("Database error during authentication (SQLState={}, code={})", e.getSQLState(), e.getErrorCode());
        return ApiException.internal(e);
    }

    private static boolean isConnectivityProblem(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // ---- development header (local only, never together with a token) -------------------------------------------

    private static Principal fromDevelopmentHeader(Map<String, String> headers) {
        String raw = headers.get(DEV_STUDENT_HEADER);
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Missing required header " + DEV_STUDENT_HEADER);
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id <= 0) {
                throw new NumberFormatException("not positive");
            }
            return new Principal(id);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(DEV_STUDENT_HEADER + " must be a positive number");
        }
    }
}
