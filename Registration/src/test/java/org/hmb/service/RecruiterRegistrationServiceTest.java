package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.RecruiterRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NewStudentUser;
import org.hmb.model.RecruiterNextStep;
import org.hmb.repository.RecruiterRepository;
import org.hmb.repository.UniqueKeyCatalog;
import org.hmb.repository.UniqueKeyCatalog.UniqueKey;
import org.hmb.repository.UserIdentityRepository;
import org.hmb.repository.UserRepository;
import org.hmb.security.AuthenticatedIdentity;
import org.hmb.security.BearerToken;
import org.hmb.security.VerifiedAccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecruiterRegistrationServiceTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final long NEW_ID = 1_000_000_042L;
    private static final Instant NOW = Instant.parse("2026-11-12T18:30:15.987Z");
    private static final AuthenticatedIdentity IDENTITY =
            new AuthenticatedIdentity(SUB, "recruiter@gmail.com", IdentityProvider.GOOGLE);
    private static final VerifiedAccessToken TOKEN =
            new VerifiedAccessToken(SUB, "google_1", "client", Set.of("openid"), NOW.plusSeconds(600));
    private static final BearerToken BEARER = new BearerToken("raw");
    private static final StudentRegistrationRequest VALID =
            new StudentRegistrationRequest("Alex", "Smith", "+17135550101", true);

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RecruiterRepository recruiters = mock(RecruiterRepository.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final UniqueKeyCatalog uniqueKeys = mock(UniqueKeyCatalog.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(identityService.resolve(TOKEN, BEARER)).thenReturn(IDENTITY);
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty());
        when(users.findByEmail(connection, "recruiter@gmail.com")).thenReturn(Optional.empty());
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenReturn(NEW_ID);
        when(uniqueKeys.classify(any(), any())).thenReturn(UniqueKey.UNKNOWN);
    }

    /** {@code phoneVerificationRequired} is the STUDENT policy flag; it must make no difference for recruiters. */
    private RecruiterRegistrationService service(boolean phoneVerificationRequired) {
        RegistrationConfig config = new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY",
                "5rgjoisv64qhe70oulfla17id9", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01",
                phoneVerificationRequired);
        return new RecruiterRegistrationService(dataSource, new RegistrationValidator(), identityService, users,
                recruiters, identities, config, Clock.fixed(NOW, ZoneOffset.UTC), uniqueKeys);
    }

    @Test
    void insertsUsersRecruitersAndIdentityInOneTransactionWithTheGeneratedId() throws SQLException {
        RecruiterRegistrationData data = service(false).register(TOKEN, BEARER, VALID);

        InOrder order = inOrder(connection, identities, users, recruiters);
        order.verify(connection).setAutoCommit(false);
        order.verify(identities).findByCognitoSub(connection, SUB);
        order.verify(users).findByEmail(connection, "recruiter@gmail.com");
        order.verify(users).insert(eq(connection), any(NewStudentUser.class));
        order.verify(recruiters).insert(connection, NEW_ID);
        order.verify(identities).insert(connection, NEW_ID, IdentityProvider.GOOGLE, SUB);
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
        verify(connection, never()).rollback();

        assertEquals(NEW_ID, data.userId());
        assertEquals(NEW_ID, data.recruiterId());
        assertEquals("recruiter@gmail.com", data.email());
        assertTrue(data.emailVerified());
        assertFalse(data.phoneVerified());
        assertEquals("RECRUITER", data.accountType());
    }

    @Test
    void everyRecruiterIsPendingWhateverTheStudentPhonePolicy() throws SQLException {
        for (boolean phoneRequired : new boolean[] {false, true}) {
            RecruiterRegistrationData data = service(phoneRequired).register(TOKEN, BEARER, VALID);
            assertEquals(AccountStatus.PENDING, data.accountStatus(), "phoneRequired=" + phoneRequired);
            assertEquals(RecruiterNextStep.APPROVAL_PENDING, data.nextStep());
        }
        ArgumentCaptor<NewStudentUser> user = ArgumentCaptor.forClass(NewStudentUser.class);
        verify(users, org.mockito.Mockito.times(2)).insert(eq(connection), user.capture());
        assertTrue(user.getAllValues().stream().allMatch(u -> u.status() == AccountStatus.PENDING));
    }

    @Test
    void storesServerControlledValues() throws SQLException {
        service(false).register(TOKEN, BEARER, VALID);

        ArgumentCaptor<NewStudentUser> user = ArgumentCaptor.forClass(NewStudentUser.class);
        verify(users).insert(eq(connection), user.capture());
        assertEquals("recruiter@gmail.com", user.getValue().email());     // from Cognito, not the request
        assertEquals("+17135550101", user.getValue().phone());
        assertEquals("2026-11-01", user.getValue().termsVersion());       // from configuration
        assertEquals(Instant.parse("2026-11-12T18:30:15Z"), user.getValue().termsAcceptedAt()); // server UTC clock
        assertEquals(AccountStatus.PENDING, user.getValue().status());
    }

    @Test
    void clientSuppliedStatusManagerAndVerificationFieldsAreIgnored() {
        StudentRegistrationRequest forged = new StudentRegistrationRequest("Alex", "Smith", "+17135550101", true,
                Set.of("accountStatus", "managerId", "userId", "email", "cognitoSub", "phoneVerified", "otpVerified"));

        RecruiterRegistrationData data = service(false).register(TOKEN, BEARER, forged);

        assertEquals(AccountStatus.PENDING, data.accountStatus());
        assertFalse(data.phoneVerified());
        assertEquals("recruiter@gmail.com", data.email());
    }

    @Test
    void invalidInputTouchesNeitherCognitoNorTheDatabase() {
        RegistrationException e = assertThrows(RegistrationException.class, () -> service(false)
                .register(TOKEN, BEARER, new StudentRegistrationRequest("Alex", "", "+17135550101", true)));
        assertEquals("VALIDATION_FAILED", e.code());
        verifyNoInteractions(identityService, dataSource);
    }

    @Test
    void identityFailuresTouchNoDatabaseAndGoogleRequiredHasRecruiterWording() {
        doThrow(RegistrationException.emailNotVerified()).when(identityService).resolve(TOKEN, BEARER);
        assertEquals("EMAIL_NOT_VERIFIED", assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID)).code());

        doThrow(RegistrationException.googleAccountRequired()).when(identityService).resolve(TOKEN, BEARER);
        RegistrationException google = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", google.code());
        assertEquals("Please use Continue with Google to sign in as a recruiter.", google.getMessage());
        verifyNoInteractions(dataSource);
    }

    // ---- rollback at each of the three inserts -----------------------------------------------------------------

    @Test
    void failureInsertingUsersCreatesNoRecruiterAndNoIdentity() throws SQLException {
        doThrow(new SQLException("simulated", "HY000", 1105)).when(users)
                .insert(eq(connection), any(NewStudentUser.class));

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));

        assertEquals(500, e.status());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(recruiters, never()).insert(any(), anyLong());
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void failureInsertingRecruitersRollsTheUsersInsertBack() throws SQLException {
        doThrow(new SQLException("simulated", "HY000", 1105)).when(recruiters).insert(connection, NEW_ID);

        assertThrows(RegistrationException.class, () -> service(false).register(TOKEN, BEARER, VALID));

        verify(users).insert(eq(connection), any(NewStudentUser.class));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void failureInsertingTheIdentityRollsEverythingBack() throws SQLException {
        doThrow(new SQLException("simulated", "HY000", 1105)).when(identities)
                .insert(connection, NEW_ID, IdentityProvider.GOOGLE, SUB);

        assertThrows(RegistrationException.class, () -> service(false).register(TOKEN, BEARER, VALID));

        verify(users).insert(eq(connection), any(NewStudentUser.class));
        verify(recruiters).insert(connection, NEW_ID);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // ---- existing accounts ---------------------------------------------------------------------------------------

    @Test
    void anExistingRecruiterIdentityIsAlreadyRegisteredWhetherPendingOrActive() throws SQLException {
        for (AccountStatus status : new AccountStatus[] {AccountStatus.PENDING, AccountStatus.ACTIVE}) {
            when(identities.findByCognitoSub(connection, SUB))
                    .thenReturn(Optional.of(new ExistingAccount(7, status, false, false, true, false)));
            RegistrationException e = assertThrows(RegistrationException.class,
                    () -> service(false).register(TOKEN, BEARER, VALID));
            assertEquals("ACCOUNT_ALREADY_REGISTERED", e.code(), status.name());
            assertEquals(409, e.status());
        }
        verify(users, never()).insert(any(), any());
    }

    @Test
    void anExistingStudentOrManagerIdentityIsNeverConvertedIntoARecruiter() throws SQLException {
        for (ExistingAccount other : new ExistingAccount[] {
                new ExistingAccount(7, AccountStatus.ACTIVE, false, true, false, false),
                new ExistingAccount(8, AccountStatus.SUSPENDED, false, true, false, false),
                new ExistingAccount(9, AccountStatus.ACTIVE, false, false, false, true)}) {
            when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.of(other));
            RegistrationException e = assertThrows(RegistrationException.class,
                    () -> service(false).register(TOKEN, BEARER, VALID));
            assertEquals("ACCOUNT_TYPE_MISMATCH", e.code(), other.toString());
            assertEquals(403, e.status());
            assertEquals("This Google account is already associated with another HireMeBot account type.",
                    e.getMessage());
        }
        verify(recruiters, never()).insert(any(), anyLong());
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void deletedAndSuspendedRecruiterIdentitiesAreRefusedNotReused() throws SQLException {
        when(identities.findByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, true, false, true, false)));
        assertEquals("ACCOUNT_DELETED", assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID)).code());

        when(identities.findByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.SUSPENDED, false, false, true, false)));
        RegistrationException suspended = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));
        assertEquals("ACCOUNT_SUSPENDED", suspended.code());
        assertEquals("Your recruiter account has been suspended.", suspended.getMessage());
        verify(users, never()).insert(any(), any());
    }

    @Test
    void anEmailOwnedByAnotherIdentityIsAConflictAndIsNeverLinked() throws SQLException {
        when(users.findByEmail(connection, "recruiter@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, false, true, false, false)));

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));

        assertEquals("ACCOUNT_IDENTITY_CONFLICT", e.code());
        assertEquals(409, e.status());
        assertEquals("This email is already associated with another HireMeBot account.", e.getMessage());
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    // ---- conflicts under concurrency -----------------------------------------------------------------------------

    @Test
    void aNameDuplicateIsNotReportedAsAnEmailDuplicate() throws SQLException {
        SQLIntegrityConstraintViolationException duplicate = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'Alex-Smith' for key 'users.first_name'", "23000", 1062);
        doThrow(duplicate).when(users).insert(eq(connection), any(NewStudentUser.class));
        when(uniqueKeys.classify(connection, duplicate)).thenReturn(UniqueKey.FULL_NAME);

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));

        assertEquals("NAME_ALREADY_REGISTERED", e.code());
        assertEquals(409, e.status());
        assertEquals("An account with this name already exists. Please contact support.", e.getMessage());
        verify(connection, never()).commit();
    }

    @Test
    void losingARaceForTheSameIdentityGivesTheSameDeterministic409() throws SQLException {
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExistingAccount(9, AccountStatus.PENDING, false, false, true, false)));
        doThrow(new SQLIntegrityConstraintViolationException("Duplicate entry 'x' for key 'users.email'", "23000",
                1062)).when(users).insert(eq(connection), any(NewStudentUser.class));

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));

        assertEquals("ACCOUNT_ALREADY_REGISTERED", e.code());
        verify(connection, never()).commit();
    }

    @Test
    void databaseOutageIsASanitizedServiceUnavailable() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException(
                "hiremebot-pool - Connection is not available, request timed out after 5000ms."));

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(false).register(TOKEN, BEARER, VALID));

        assertEquals("SERVICE_UNAVAILABLE", e.code());
        assertEquals(503, e.status());
        assertEquals("The service is temporarily unavailable. Please try again later.", e.getMessage());
    }
}
