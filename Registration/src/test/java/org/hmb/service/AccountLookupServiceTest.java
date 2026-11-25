package org.hmb.service;

import org.hmb.dto.AccountLookupResponse;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NextStep;
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
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountLookupServiceTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final VerifiedAccessToken TOKEN = new VerifiedAccessToken(SUB, "google_1", "client",
            Set.of("openid", "email"), Instant.now().plusSeconds(600));
    private static final BearerToken BEARER = new BearerToken("raw");

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccountLookupService service = new AccountLookupService(dataSource, identityService, identities, users);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(identities.findProfileByCognitoSub(connection, SUB)).thenReturn(Optional.empty());
        when(users.findByEmail(connection, "alex@gmail.com")).thenReturn(Optional.empty());
        when(identityService.resolve(TOKEN, BEARER))
                .thenReturn(new AuthenticatedIdentity(SUB, "alex@gmail.com", IdentityProvider.GOOGLE));
    }

    private static AccountProfile profile(AccountStatus status, boolean deleted, boolean student,
                                          IdentityProvider provider) {
        return new AccountProfile(1_000_000_000L, "Alex", "Smith", "alex@gmail.com", "+17135550101", true, false,
                status, deleted, student, provider);
    }

    // ---- existing accounts: decided from MySQL only, no Cognito call ---------------------------------------------

    @Test
    void anActiveStudentGoesToTheDashboardWithTheirProfile() throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(profile(AccountStatus.ACTIVE, false, true, IdentityProvider.GOOGLE)));

        AccountLookupResponse response = service.lookup(TOKEN, BEARER);

        assertTrue(response.success());
        assertTrue(response.registered());
        assertEquals(NextStep.DASHBOARD, response.nextStep());
        assertNull(response.message());
        assertEquals(1_000_000_000L, response.user().userId());
        assertEquals(1_000_000_000L, response.user().studentId());
        assertEquals("alex@gmail.com", response.user().email());
        assertEquals("+17135550101", response.user().phoneNumber());
        assertTrue(response.user().emailVerified());
        assertFalse(response.user().phoneVerified());
        assertEquals(AccountStatus.ACTIVE, response.user().accountStatus());
        assertEquals("STUDENT", response.user().accountType());
        verifyNoInteractions(identityService, users);
    }

    @Test
    void aPendingStudentGetsThePendingStepNotTheDashboardOrTheSignupForm() throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(profile(AccountStatus.PENDING, false, true, IdentityProvider.GOOGLE)));

        AccountLookupResponse response = service.lookup(TOKEN, BEARER);

        assertTrue(response.registered());
        assertEquals(NextStep.ACCOUNT_PENDING, response.nextStep());
        assertEquals(AccountStatus.PENDING, response.user().accountStatus());
    }

    @Test
    void restrictedAccountsAreRefusedAndNeverSentToSignup() throws SQLException {
        assertRefused(profile(AccountStatus.SUSPENDED, false, true, IdentityProvider.GOOGLE), "ACCOUNT_SUSPENDED", 403);
        assertRefused(profile(AccountStatus.ACTIVE, true, true, IdentityProvider.GOOGLE), "ACCOUNT_DELETED", 403);
        assertRefused(profile(AccountStatus.ACTIVE, false, false, IdentityProvider.GOOGLE), "ACCOUNT_TYPE_MISMATCH", 403);
        assertRefused(profile(AccountStatus.ACTIVE, false, false, IdentityProvider.COGNITO), "ACCOUNT_TYPE_MISMATCH", 403);
        assertRefused(profile(AccountStatus.ACTIVE, false, true, IdentityProvider.COGNITO), "GOOGLE_ACCOUNT_REQUIRED", 403);
        verifyNoInteractions(identityService);
    }

    // ---- unknown subject: verified Google identity, email checked, nothing written ----------------------------------

    @Test
    void aNewGoogleUserIsSentToTheSignupForm() throws SQLException {
        AccountLookupResponse response = service.lookup(TOKEN, BEARER);

        assertTrue(response.success());
        assertFalse(response.registered());
        assertEquals(NextStep.SIGNUP_FORM, response.nextStep());
        assertEquals("Complete your HireMeBot student registration.", response.message());
        assertNull(response.user());
        verify(identityService).resolve(TOKEN, BEARER);
        verify(users).findByEmail(connection, "alex@gmail.com");
        verify(users, never()).insert(any(), any());
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void aNewIdentityWhoseEmailIsAlreadyRegisteredIsAConflictNotTheSignupForm() throws SQLException {
        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, false, true)));

        RegistrationException e = assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER));

        assertEquals("ACCOUNT_IDENTITY_CONFLICT", e.code());
        assertEquals(409, e.status());
    }

    @Test
    void anEmailOwnedByADeletedOrSuspendedAccountIsReportedAsSuch() throws SQLException {
        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, true, true)));
        assertEquals("ACCOUNT_DELETED",
                assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER)).code());

        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.SUSPENDED, false, false)));
        assertEquals("ACCOUNT_SUSPENDED",
                assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER)).code());
    }

    @Test
    void cognitoIdentityFailuresStopTheLookup() throws SQLException {
        doThrow(RegistrationException.googleAccountRequired()).when(identityService).resolve(TOKEN, BEARER);
        assertEquals("GOOGLE_ACCOUNT_REQUIRED",
                assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER)).code());

        doThrow(RegistrationException.emailNotVerified()).when(identityService).resolve(TOKEN, BEARER);
        assertEquals("EMAIL_NOT_VERIFIED",
                assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER)).code());
        verify(users, never()).findByEmail(any(), any());
    }

    @Test
    void anAccountCreatedMeanwhileInAnotherTabIsFoundOnTheRecheck() throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(profile(AccountStatus.ACTIVE, false, true, IdentityProvider.GOOGLE)));
        // Without the re-check this email match (the user's own new account) would look like a conflict.
        when(users.findByEmail(connection, "alex@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(1_000_000_000L, AccountStatus.ACTIVE, false, true)));

        assertEquals(NextStep.DASHBOARD, service.lookup(TOKEN, BEARER).nextStep());
    }

    @Test
    void databaseOutageIsAServiceUnavailable() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLTransientConnectionException("pool timeout"));
        RegistrationException e = assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER));
        assertEquals(503, e.status());
        verifyNoInteractions(identityService);
    }

    private void assertRefused(AccountProfile profile, String code, int status) throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB)).thenReturn(Optional.of(profile));
        RegistrationException e = assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER));
        assertEquals(code, e.code(), profile.toString());
        assertEquals(status, e.status());
    }
}
