package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.RecruiterRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.NewStudentUser;
import org.hmb.model.RecruiterNextStep;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.UniqueKeyCatalog;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.VerifiedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * New recruiter registration: open signup, manual approval.
 *
 * <ol>
 *   <li>Validate the body with the student {@link RegistrationValidator} (same names, E.164 phone and terms rules; no
 *       network or database work for an invalid request). The request type is shared too: only {@code firstName},
 *       {@code lastName}, {@code phoneNumber} and {@code acceptedTerms} are read; email, subject, user id, manager id,
 *       status and verification flags from the client are ignored.</li>
 *   <li>Resolve the identity through Cognito (UserInfo + AdminGetUser), outside any transaction.</li>
 *   <li>Run the shared {@link AccountRegistrar} transaction with the recruiter specifics: the {@code recruiters} row
 *       with {@code manager_id = NULL}, the status {@link RecruiterAccountRules#REGISTRATION_STATUS} and the recruiter
 *       refusals of {@link RecruiterAccountRules}.</li>
 * </ol>
 *
 * <p><b>Never active on registration.</b> The status is fixed at {@code PENDING}, independent of
 * {@code PHONE_VERIFICATION_REQUIRED} (a student-only policy), and the response step is always
 * {@link RecruiterNextStep#APPROVAL_PENDING}. {@code phone_verified} stays {@code FALSE}.
 */
public class RecruiterRegistrationService {

    private static final Logger LOG = LoggerFactory.getLogger(RecruiterRegistrationService.class);

    /** The recruiter decision table: an existing subject or an email owned by another identity (never linked). */
    private static final AccountRegistrar.Refusals RECRUITER_REFUSALS = new AccountRegistrar.Refusals(
            RecruiterAccountRules::refuseRegisteredIdentity, RecruiterAccountRules::refuseEmailOwner);

    private final RegistrationValidator validator;
    private final CognitoIdentityService identityService;
    private final RecruiterRepository recruiters;
    private final RegistrationConfig config;
    private final Clock clock;
    private final AccountRegistrar registrar;

    public RecruiterRegistrationService(DataSource dataSource, RegistrationValidator validator,
                                        CognitoIdentityService identityService, UserRepository users,
                                        RecruiterRepository recruiters, UserIdentityRepository identities,
                                        RegistrationConfig config, Clock clock) {
        this(dataSource, validator, identityService, users, recruiters, identities, config, clock,
                new UniqueKeyCatalog());
    }

    public RecruiterRegistrationService(DataSource dataSource, RegistrationValidator validator,
                                        CognitoIdentityService identityService, UserRepository users,
                                        RecruiterRepository recruiters, UserIdentityRepository identities,
                                        RegistrationConfig config, Clock clock, UniqueKeyCatalog uniqueKeys) {
        this.validator = validator;
        this.identityService = identityService;
        this.recruiters = recruiters;
        this.config = config;
        this.clock = clock;
        this.registrar = new AccountRegistrar(dataSource, users, identities, uniqueKeys);
    }

    /**
     * @param token  the locally verified access token
     * @param bearer the same token, raw, for the UserInfo call
     * @throws RegistrationException 400 invalid body, 401/403 identity, 409 already registered or conflicting
     *                               account, 403 deleted / other account type / suspended, 503/500 infrastructure
     */
    public RecruiterRegistrationData register(VerifiedAccessToken token, BearerToken bearer,
                                              StudentRegistrationRequest request) {
        if (!request.ignoredFields().isEmpty()) {
            // e.g. a mock OTP result, an "email", a "status" or a "managerId": never read, never trusted.
            LOG.info("Ignoring client-supplied fields {}", request.ignoredFields());
        }
        ValidatedRegistration input = validator.validate(request);
        AuthenticatedIdentity identity;
        try {
            identity = identityService.resolve(token, bearer);
        } catch (RegistrationException e) {
            throw RecruiterAccountRules.recruiterWording(e);
        }
        return persist(identity, input);
    }

    /** Step 3: the transaction. Visible for integration tests that supply an already-resolved identity. */
    public RecruiterRegistrationData persist(AuthenticatedIdentity identity, ValidatedRegistration input) {
        AccountStatus status = RecruiterAccountRules.REGISTRATION_STATUS;
        NewStudentUser user = new NewStudentUser(input.firstName(), input.lastName(), identity.email(),
                input.phoneE164(), status, clock.instant().truncatedTo(ChronoUnit.SECONDS), config.termsVersion());

        long userId = registrar.register(identity, user, recruiters::insert, RECRUITER_REFUSALS);

        RecruiterNextStep nextStep = RecruiterAccountRules.nextStepFor(status);
        LOG.info("Registered recruiter {} (accountStatus={}, nextStep={})", userId, status, nextStep);
        return new RecruiterRegistrationData(userId, userId, user.firstName(), user.lastName(), user.email(),
                user.phone(), true, false, status, RecruiterRegistrationData.ACCOUNT_TYPE_RECRUITER, nextStep);
    }
}
