package org.hmb.service;

import org.hmb.config.RegistrationConfig;
import org.hmb.dto.StudentRegistrationRequest;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NewStudentUser;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Registration conflicts under the unified rules: account type, and which unique index a duplicate violated. */
class RegistrationConflictClassificationTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final VerifiedAccessToken TOKEN =
            new VerifiedAccessToken(SUB, "google_1", "client", Set.of("openid"), Instant.now().plusSeconds(600));
    private static final BearerToken BEARER = new BearerToken("raw");
    private static final StudentRegistrationRequest VALID =
            new StudentRegistrationRequest("Alex", "Smith", "+17135550101", true);

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final UniqueKeyCatalog uniqueKeys = mock(UniqueKeyCatalog.class);
    private StudentRegistrationService service;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(identityService.resolve(TOKEN, BEARER))
                .thenReturn(new AuthenticatedIdentity(SUB, "alex@gmail.com", IdentityProvider.GOOGLE));
        when(identities.findByCognitoSub(connection, SUB)).thenReturn(Optional.empty());
        when(users.findByEmail(connection, "alex@gmail.com")).thenReturn(Optional.empty());
        RegistrationConfig config = new RegistrationConfig("us-east-2", "us-east-2_F582IgoSY",
                "5rgjoisv64qhe70oulfla17id9", "us-east-2f582igosy.auth.us-east-2.amazoncognito.com", "2026-11-01",
                false);
        service = new StudentRegistrationService(dataSource, new RegistrationValidator(), identityService, users,
                mock(StudentRepository.class), identities, config, Clock.systemUTC(), uniqueKeys);
    }

    @Test
    void aRecruiterOrManagerIdentityIsAnAccountTypeMismatchNotAlreadyRegistered() throws SQLException {
        when(identities.findByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(new ExistingAccount(5, AccountStatus.ACTIVE, false, false)));

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service.register(TOKEN, BEARER, VALID));

        assertEquals("ACCOUNT_TYPE_MISMATCH", e.code());
        assertEquals(403, e.status());
        verify(users, never()).insert(any(), any());
    }

    @Test
    void aNameDuplicateIsNotReportedAsAnEmailDuplicate() throws SQLException {
        SQLIntegrityConstraintViolationException duplicate = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'Alex-Smith' for key 'users.first_name'", "23000", 1062);
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(duplicate);
        when(uniqueKeys.classify(connection, duplicate)).thenReturn(UniqueKey.FULL_NAME);

        RegistrationException e = assertThrows(RegistrationException.class,
                () -> service.register(TOKEN, BEARER, VALID));

        assertEquals("NAME_ALREADY_REGISTERED", e.code());
        assertEquals(409, e.status());
        verify(connection, never()).commit();
    }

    @Test
    void anEmailOrSubjectDuplicateThatTheRecheckCannotConfirmIsRetryable() throws SQLException {
        SQLIntegrityConstraintViolationException duplicate = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'alex@gmail.com' for key 'users.email'", "23000", 1062);
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(duplicate);
        when(uniqueKeys.classify(connection, duplicate)).thenReturn(UniqueKey.EMAIL);

        assertEquals("SERVICE_UNAVAILABLE", assertThrows(RegistrationException.class,
                () -> service.register(TOKEN, BEARER, VALID)).code());

        when(uniqueKeys.classify(connection, duplicate)).thenReturn(UniqueKey.COGNITO_SUBJECT);
        assertEquals("SERVICE_UNAVAILABLE", assertThrows(RegistrationException.class,
                () -> service.register(TOKEN, BEARER, VALID)).code());
    }

    @Test
    void anEmailDuplicateConfirmedByTheRecheckIsTheEmailConflictEvenIfTheNameAlsoMatches() throws SQLException {
        SQLIntegrityConstraintViolationException duplicate = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'Alex-Smith' for key 'users.first_name'", "23000", 1062);
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenThrow(duplicate);
        when(users.findByEmail(connection, "alex@gmail.com")).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExistingAccount(9, AccountStatus.ACTIVE, false, true)));
        when(uniqueKeys.classify(connection, duplicate)).thenReturn(UniqueKey.FULL_NAME);

        assertEquals("ACCOUNT_IDENTITY_CONFLICT", assertThrows(RegistrationException.class,
                () -> service.register(TOKEN, BEARER, VALID)).code());
    }

    @Test
    void theDefaultV1PolicyCreatesAnActiveAccountThatGoesToTheDashboard() throws SQLException {
        when(users.insert(eq(connection), any(NewStudentUser.class))).thenReturn(1_000_000_000L);

        var data = service.register(TOKEN, BEARER, VALID);

        assertEquals(AccountStatus.ACTIVE, data.accountStatus());
        assertEquals(org.hmb.model.NextStep.DASHBOARD, data.nextStep());
        assertEquals(false, data.phoneVerified());
        assertEquals(true, data.emailVerified());
    }
}
