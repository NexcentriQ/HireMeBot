package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.NewStudentUser;
import org.hmb.model.NextStep;
import org.hmb.model.ValidatedRegistration;
import org.hmb.repository.StudentRepository;
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
 * New student registration.
 *
 * <ol>
 *   <li>Validate the body (no network or database work for an invalid request).</li>
 *   <li>Resolve the identity through Cognito (UserInfo + AdminGetUser), outside any transaction.</li>
 *   <li>Run the shared {@link AccountRegistrar} transaction with the student specifics: the {@code students} row, the
 *       account status from the policy ({@link RegistrationConfig#initialAccountStatus()}) and the student refusals of
 *       {@link AccountRules}. See {@link AccountRegistrar} for the transaction, rollback and concurrency behaviour.</li>
 * </ol>
 *
 * <p>The Cognito user exists before this runs and is never deleted or changed here, even if the transaction fails.
 */
public class StudentRegistrationService {

    private static final Logger LOG = LoggerFactory.getLogger(StudentRegistrationService.class);

    /** The student decision table: an existing subject or an email owned by another identity (never linked). */
    private static final AccountRegistrar.Refusals STUDENT_REFUSALS = new AccountRegistrar.Refusals(
            AccountRules::refuseRegisteredIdentity, AccountRules::refuseEmailOwner);

    private final RegistrationValidator validator;
    private final CognitoIdentityService identityService;
    private final StudentRepository students;
    private final RegistrationConfig config;
    private final Clock clock;
    private final AccountRegistrar registrar;

    public StudentRegistrationService(DataSource dataSource, RegistrationValidator validator,
                                      CognitoIdentityService identityService, UserRepository users,
                                      StudentRepository students, UserIdentityRepository identities,
                                      RegistrationConfig config, Clock clock) {
        this(dataSource, validator, identityService, users, students, identities, config, clock,
                new UniqueKeyCatalog());
    }

    public StudentRegistrationService(DataSource dataSource, RegistrationValidator validator,
                                      CognitoIdentityService identityService, UserRepository users,
                                      StudentRepository students, UserIdentityRepository identities,
                                      RegistrationConfig config, Clock clock, UniqueKeyCatalog uniqueKeys) {
        this.validator = validator;
        this.identityService = identityService;
        this.students = students;
        this.config = config;
        this.clock = clock;
        this.registrar = new AccountRegistrar(dataSource, users, identities, uniqueKeys);
    }

    /**
     * @param token   the locally verified access token
     * @param bearer  the same token, raw, for the UserInfo call
     * @throws RegistrationException 400 invalid body, 401/403 identity, 409 already registered or conflicting
     *                               account, 403 suspended, 503/500 infrastructure
     */
    public StudentRegistrationData register(VerifiedAccessToken token, BearerToken bearer,
                                            StudentRegistrationRequest request) {
        if (!request.ignoredFields().isEmpty()) {
            // e.g. a mock OTP result or an "email" field: never read, never trusted.
            LOG.info("Ignoring client-supplied fields {}", request.ignoredFields());
        }
        ValidatedRegistration input = validator.validate(request);
        AuthenticatedIdentity identity = identityService.resolve(token, bearer);
        return persist(identity, input);
    }

    /** Step 3: the transaction. Visible for integration tests that supply an already-resolved identity. */
    public StudentRegistrationData persist(AuthenticatedIdentity identity, ValidatedRegistration input) {
        AccountStatus status = config.initialAccountStatus();
        NewStudentUser user = new NewStudentUser(input.firstName(), input.lastName(), identity.email(),
                input.phoneE164(), status, clock.instant().truncatedTo(ChronoUnit.SECONDS), config.termsVersion());

        long userId = registrar.register(identity, user, students::insert, STUDENT_REFUSALS);

        NextStep nextStep = AccountRules.nextStepFor(status);
        LOG.info("Registered student {} (accountStatus={}, nextStep={})", userId, status, nextStep);
        return new StudentRegistrationData(userId, userId, user.firstName(), user.lastName(), user.email(),
                user.phone(), true, false, status, StudentRegistrationData.ACCOUNT_TYPE_STUDENT, nextStep);
    }
}
