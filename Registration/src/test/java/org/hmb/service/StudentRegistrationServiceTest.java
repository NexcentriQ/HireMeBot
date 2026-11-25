package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.StudentRegistrationData;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NewStudentUser;
import org.hmb.model.NextStep;
import org.hmb.repository.StudentRepository;
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
import java.sql.SQLTransactionRollbackException;
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

class StudentRegistrationServiceTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final long NEW_ID = 1_000_000_042L;
    private static final Instant NOW = Instant.parse("2026-11-06T18:30:15.987Z");
    private static final AuthenticatedIdentity IDENTITY =
            new AuthenticatedIdentity(SUB, "alex@gmail.com", IdentityProvider.GOOGLE);
    private static final VerifiedAccessToken TOKEN =
            new VerifiedAccessToken(SUB, "google_1", "client", Set.of("openid"), NOW.plusSeconds(600));
    private static final BearerToken BEARER = new BearerToken("raw");
    private static final StudentRegistrationRequest VALID =
            new StudentRegistrationRequest("Alex", "Smith", "+17135550101", true);

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StudentRepository students = mock(StudentRepository.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final UniqueKeyCatalog uniqueKeys = mock(UniqueKeyCatalog.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(identityService.resolve(TOKEN, BEARER)).thenReturn(IDENTITY);
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty());
        when(users.findByEmail(connection, "alex@gmail.com")).thenReturn(Optional.empty());
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenReturn(NEW_ID);
        when(uniqueKeys.classify(any(), any())).thenReturn(UniqueKey.UNKNOWN);
    }

    private StudentRegistrationService service(boolean phoneVerificationRequired) {
        RegistrationConfig config = new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY",
                "5rgjoisv64qhe70oulfla17id9", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01",
                phoneVerificationRequired);
        return new StudentRegistrationService(dataSource, new RegistrationValidator(), identityService, users,
                students, identities, config, Clock.fixed(NOW, ZoneOffset.UTC), uniqueKeys);
    }

    @Test
    void insertsAllThreeRowsInOneTransactionWithTheGeneratedId() throws SQLException {
        StudentRegistrationData data = service(true).register(TOKEN, BEARER, VALID);

        InOrder order = inOrder(connection, identities, users, students);
        order.verify(connection).setAutoCommit(false);
        order.verify(identities).findByCognitoSub(connection, SUB);
        order.verify(users).findByEmail(connection, "alex@gmail.com");
        order.verify(users).insert(eq(connection), any(NewStudentUser.class));
        order.verify(students).insert(connection, NEW_ID);
        order.verify(identities).insert(connection, NEW_ID, IdentityProvider.GOOGLE, SUB);
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
        verify(connection, never()).rollback();

        assertEquals(NEW_ID, data.userId());
        assertEquals(NEW_ID, data.studentId());
        assertEquals("alex@gmail.com", data.email());
        assertTrue(data.emailVerified());
        assertFalse(data.phoneVerified());
        assertEquals("STUDENT", data.accountType());
    }

    @Test
    void storesServerControlledValues() throws SQLException {
        service(true).register(TOKEN, BEARER, VALID);

        ArgumentCaptor<NewStudentUser> user = ArgumentCaptor.forClass(NewStudentUser.class);
        verify(users).insert(eq(connection), user.capture());
        assertEquals("alex@gmail.com", user.getValue().email()); // from Cognito, not the request
        assertEquals("+17135550101", user.getValue().phone());
        assertEquals("2026-11-01", user.getValue().termsVersion()); // from configuration
        assertEquals(Instant.parse("2026-11-06T18:30:15Z"), user.getValue().termsAcceptedAt()); // server UTC clock
        assertEquals(AccountStatus.PENDING, user.getValue().status());
    }

    @Test
    void accountPolicyPendingWhenPhoneVerificationIsRequired() {
        StudentRegistrationData data = service(true).register(TOKEN, BEARER, VALID);
        assertEquals(AccountStatus.PENDING, data.accountStatus());
        assertEquals(NextStep.ACCOUNT_PENDING, data.nextStep());
    }

    @Test
    void accountPolicyActiveOnlyWhenExplicitlyConfigured() {
        StudentRegistrationData data = service(false).register(TOKEN, BEARER, VALID);
        assertEquals(AccountStatus.ACTIVE, data.accountStatus());
        assertEquals(NextStep.DASHBOARD, data.nextStep());
        assertFalse(data.phoneVerified());
    }

    @Test
    void invalidInputTouchesNeitherCognitoNorTheDatabase() {
        RegistrationException e = assertThrows(RegistrationException.class, () -> service(true)
                .register(TOKEN, BEARER, new StudentRegistrationRequest("", "Smith", "+17135550101", true)));
        assertEquals("VALIDATION_FAILED", e.code());
        verifyNoInteractions(identityService, dataSource);
    }

    @Test
    void identityFailuresTouchNoDatabase() {
        when(identityService.resolve(TOKEN, BEARER)).thenThrow(RegistrationException.emailNotVerified());
        assertThrows(RegistrationException.class, () -> service(true).register(TOKEN, BEARER, VALID));
        verifyNoInteractions(dataSource);
    }

    @Test
    void failureInsertingStudentsRollsEverythingBack() throws SQLException {
        doThrow(new SQLException("simulated", "HY000", 1105)).when(students).insert(connection, NEW_ID);

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(true).register(TOKEN, BEARER, VALID));

        assertEquals(500, e.status());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void failureInsertingTheIdentityRollsEverythingBack() throws SQLException {
        doThrow(new SQLException("simulated", "HY000", 1105)).when(identities)
                .insert(connection, NEW_ID, IdentityProvider.GOOGLE, SUB);

        assertThrows(RegistrationException.class, () -> service(true).register(TOKEN, BEARER, VALID));

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void existingCognitoIdentityIsAlreadyRegistered() throws SQLException {
        when(identities.findByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, false)));

        assertCode("ACCOUNT_ALREADY_REGISTERED", 409);
        verify(users, never()).insert(any(), any());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void existingEmailUnderAnotherIdentityIsAConflictAndIsNeverLinked() throws SQLException {
        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, false)));

        assertCode("ACCOUNT_IDENTITY_CONFLICT", 409);
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void deletedAndSuspendedAccountsAreRefusedNotReused() throws SQLException {
        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, true)));
        assertCode("ACCOUNT_DELETED", 403);

        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.SUSPENDED, false)));
        assertCode("ACCOUNT_SUSPENDED", 403);

        when(identities.findByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.SUSPENDED, true)));
        assertCode("ACCOUNT_DELETED", 403); // deleted wins

        verify(users, never()).insert(any(), any());
    }

    @Test
    void losingAConcurrentRaceGivesTheSameDeterministic409() throws SQLException {
        // The pre-check sees nothing, the insert collides, the re-check sees the winner's committed row.
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExistingAccount(9, AccountStatus.PENDING, false)));
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(
                new SQLIntegrityConstraintViolationException("Duplicate entry 'x' for key 'users.email'", "23000", 1062));

        assertCode("ACCOUNT_ALREADY_REGISTERED", 409);
        verify(connection, never()).commit();
    }

    @Test
    void aDuplicateOnTheFullNameIndexIsANameConflict() throws SQLException {
        SQLIntegrityConstraintViolationException duplicate = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'Alex-Smith' for key 'users.first_name'", "23000", 1062);
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(duplicate);
        when(uniqueKeys.classify(connection, duplicate)).thenReturn(UniqueKey.FULL_NAME);

        RegistrationException e = assertCode("NAME_ALREADY_REGISTERED", 409);
        assertFalse(e.getMessage().contains("Alex"));
        assertEquals("users.first_name", DatabaseFailures.duplicateKeyName(duplicate));
        verify(connection, never()).commit();
    }

    @Test
    void anUnexplainedDuplicateKeyIsAnInternalError() throws SQLException {
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(
                new SQLIntegrityConstraintViolationException("Duplicate entry 'x' for key 'users.some_new_index'",
                        "23000", 1062));

        RegistrationException e = assertCode("INTERNAL_ERROR", 500);
        assertEquals("users.some_new_index", DatabaseFailures.duplicateKeyName((SQLException) e.getCause()));
    }

    @Test
    void anUnexplainedDeadlockIsRetryable() throws SQLException {
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(
                new SQLTransactionRollbackException("Deadlock found", "40001", 1213));

        assertCode("SERVICE_UNAVAILABLE", 503);
    }

    @Test
    void databaseOutageIsASanitizedServiceUnavailable() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException(
                "hiremebot-pool - Connection is not available, request timed out after 5000ms."));

        RegistrationException e = assertCode("SERVICE_UNAVAILABLE", 503);
        assertEquals("The service is temporarily unavailable. Please try again later.", e.getMessage());
    }

    @Test
    void ignoredClientFieldsNeverReachTheStoredRow() throws SQLException {
        StudentRegistrationRequest withMockOtp = new StudentRegistrationRequest("Alex", "Smith", "+17135550101", true,
                Set.of("phoneVerified", "otpVerified", "email"));

        StudentRegistrationData data = service(true).register(TOKEN, BEARER, withMockOtp);

        assertFalse(data.phoneVerified());
        assertEquals("alex@gmail.com", data.email());
        assertEquals(AccountStatus.PENDING, data.accountStatus());
    }

    private RegistrationException assertCode(String code, int status) {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service(true).register(TOKEN, BEARER, VALID));
        assertEquals(code, e.code());
        assertEquals(status, e.status());
        return e;
    }
}
