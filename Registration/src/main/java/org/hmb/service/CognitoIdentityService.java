package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.IdentityProvider;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.CognitoUserInfo;
import org.hmb.security.CognitoUserRecord;
import org.hmb.security.FederatedUserLookup;
import org.hmb.security.UserInfoClient;
import org.hmb.security.VerifiedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a verified access token into the identity that may be registered. Runs <b>before</b> any database work and
 * makes no call inside the MySQL transaction.
 *
 * <ol>
 *   <li>UserInfo with the caller's token: the subject must equal the verified token's {@code sub}; the email must be
 *       present and {@code email_verified} true (string or boolean).</li>
 *   <li>AdminGetUser: the same {@code sub}, enabled, and created by Google federation ({@code EXTERNAL_PROVIDER} with a
 *       {@code Google} entry in the Cognito-managed {@code identities} attribute).</li>
 * </ol>
 * The email is normalized to lower case, consistent with the case-insensitive collation of {@code users.email}.
 */
public class CognitoIdentityService {

    private static final Logger LOG = LoggerFactory.getLogger(CognitoIdentityService.class);
    /** {@code users.email VARCHAR(255)}; a plain sanity check, the address itself comes verified from Google. */
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+\\.[^\\s@\\p{Cntrl}]+");

    private final UserInfoClient userInfo;
    private final FederatedUserLookup users;

    public CognitoIdentityService(UserInfoClient userInfo, FederatedUserLookup users) {
        this.userInfo = userInfo;
        this.users = users;
    }

    /**
     * @throws RegistrationException 401 subject mismatch / revoked token / unknown or disabled user,
     *                               403 {@code EMAIL_NOT_VERIFIED} or {@code GOOGLE_ACCOUNT_REQUIRED}, 503/500 Cognito
     */
    public AuthenticatedIdentity resolve(VerifiedAccessToken token, BearerToken bearer) {
        CognitoUserInfo info = userInfo.fetch(bearer);
        if (info.subject() == null || !info.subject().equals(token.subject())) {
            LOG.warn("Security: UserInfo subject does not match the verified access token");
            throw RegistrationException.unauthorized();
        }

        String email = normalizeEmail(info.email());
        if (email == null) {
            LOG.info("Registration refused: Cognito returned no usable email");
            throw RegistrationException.emailNotVerified();
        }
        if (!info.emailVerified()) {
            LOG.info("Registration refused: email is not verified in Cognito");
            throw RegistrationException.emailNotVerified();
        }

        CognitoUserRecord user = users.lookup(token.username());
        if (!token.subject().equals(user.subject())) {
            LOG.warn("Security: AdminGetUser subject does not match the verified access token");
            throw RegistrationException.unauthorized();
        }
        if (!user.enabled()) {
            LOG.info("Registration refused: Cognito user is disabled");
            throw RegistrationException.unauthorized();
        }
        if (!user.isGoogleFederated()) {
            LOG.info("Registration refused: not a Google-federated user (status {}, providers {})",
                    user.userStatus(), user.federatedProviders());
            throw RegistrationException.googleAccountRequired();
        }
        return new AuthenticatedIdentity(token.subject(), email, IdentityProvider.GOOGLE);
    }

    /** Trimmed and lower-cased, or null if absent, too long or not shaped like an address. */
    static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            return null;
        }
        return email;
    }
}
