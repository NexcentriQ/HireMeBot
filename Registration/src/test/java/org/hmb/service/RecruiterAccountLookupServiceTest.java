package org.hmb.service;

import org.hmb.dto.RecruiterAccountLookupResponse;
import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountProfile;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;
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

class RecruiterAccountLookupServiceTest {

    private static final String SUB = "8f1c2d3e-4b5a-6978-8a9b-0c1d2e3f4a5b";
    private static final VerifiedAccessToken TOKEN = new VerifiedAccessToken(SUB, "google_1", "client",
            Set.of("openid", "email"), Instant.now().plusSeconds(600));
    private static final BearerToken BEARER = new BearerToken("raw");

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CognitoIdentityService identityService = mock(CognitoIdentityService.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RecruiterAccountLookupService service =
            new RecruiterAccountLookupService(dataSource, identityService, identities, users);

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(identities.findProfileByCognitoSub(connection, SUB)).thenReturn(Optional.empty());
        when(users.findByEmail(connection, "recruiter@gmail.com")).thenReturn(Optional.empty());
        when(identityService.resolve(TOKEN, BEARER))
                .thenReturn(new AuthenticatedIdentity(SUB, "recruiter@gmail.com", IdentityProvider.GOOGLE));
    }

    private static AccountProfile recruiter(AccountStatus status, boolean deleted, IdentityProvider provider) {
        return new AccountProfile(1_000_000_001L, "Alex", "Smith", "recruiter@gmail.com", "+17135550101", true, false,
                status, deleted, false, true, false, provider);
    }

    private static AccountProfile otherRole(boolean student, boolean manager, AccountStatus status) {
        return new AccountProfile(1_000_000_005L, "Sam", "Jones", "other@gmail.com", "+17135550102", true, false,
                status, false, student, false, manager, IdentityProvider.GOOGLE);
    }

    // ---- existing accounts: decided from MySQL only, no Cognito call -------------------------------------------

    @Test
    void anActiveRecruiterGoesToTheDashboardWithTheFullProfile() throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(recruiter(AccountStatus.ACTIVE, false, IdentityProvider.GOOGLE)));

        RecruiterAccountLookupResponse response = service.lookup(TOKEN, BEARER);

        assertTrue(response.success());
        assertTrue(response.registered());
        assertEquals(RecruiterNextStep.RECRUITER_DASHBOARD, response.nextStep());
        assertNull(response.message());
        RecruiterAccountLookupResponse.User user = response.user();
        assertEquals(1_000_000_001L, user.userId());
        assertEquals(1_000_000_001L, user.recruiterId());
        assertEquals("Alex", user.firstName());
        assertEquals("Smith", user.lastName());
        assertEquals("recruiter@gmail.com", user.email());
        assertEquals("+17135550101", user.phoneNumber());
        assertTrue(user.emailVerified());
        assertFalse(user.phoneVerified());
        assertEquals(AccountStatus.ACTIVE, user.accountStatus());
        assertEquals("RECRUITER", user.accountType());
        verifyNoInteractions(identityService, users);
    }

    @Test
    void aPendingRecruiterGetsApprovalPendingWithIdentifiersOnly() throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB))
                .thenReturn(Optional.of(recruiter(AccountStatus.PENDING, false, IdentityProvider.GOOGLE)));

        RecruiterAccountLookupResponse response = service.lookup(TOKEN, BEARER);

        assertTrue(response.registered());
        assertEquals(RecruiterNextStep.APPROVAL_PENDING, response.nextStep());
        assertEquals("Your recruiter account is waiting for approval.", response.message());
        RecruiterAccountLookupResponse.User user = response.user();
        assertEquals(1_000_000_001L, user.userId());
        assertEquals(1_000_000_001L, user.recruiterId());
        assertEquals("RECRUITER", user.accountType());
        assertEquals(AccountStatus.PENDING, user.accountStatus());
        assertNull(user.firstName());
        assertNull(user.email());
        assertNull(user.phoneNumber());
        assertNull(user.emailVerified());
        verifyNoInteractions(identityService);
    }

    @Test
    void restrictedAccountsAreRefusedAndNeverSentToTheSignupForm() throws SQLException {
        assertRefused(recruiter(AccountStatus.SUSPENDED, false, IdentityProvider.GOOGLE), "ACCOUNT_SUSPENDED", 403);
        assertRefused(recruiter(AccountStatus.ACTIVE, true, IdentityProvider.GOOGLE), "ACCOUNT_DELETED", 403);
        assertRefused(recruiter(AccountStatus.PENDING, true, IdentityProvider.GOOGLE), "ACCOUNT_DELETED", 403);
        assertRefused(recruiter(AccountStatus.ACTIVE, false, IdentityProvider.COGNITO), "GOOGLE_ACCOUNT_REQUIRED", 403);
        assertRefused(otherRole(true, false, AccountStatus.ACTIVE), "ACCOUNT_TYPE_MISMATCH", 403);   // a student
        assertRefused(otherRole(true, false, AccountStatus.SUSPENDED), "ACCOUNT_TYPE_MISMATCH", 403);
        assertRefused(otherRole(false, true, AccountStatus.ACTIVE), "ACCOUNT_TYPE_MISMATCH", 403);   // a manager
        assertRefused(otherRole(false, false, AccountStatus.ACTIVE), "ACCOUNT_TYPE_MISMATCH", 403);  // no role row
        verifyNoInteractions(identityService);
    }

    // ---- unknown subject: verified Google identity, email checked, nothing written ------------------------------

    @Test
    void aNewGoogleUserIsSentToTheRecruiterSignupForm() throws SQLException {
        RecruiterAccountLookupResponse response = service.lookup(TOKEN, BEARER);

        assertTrue(response.success());
        assertFalse(response.registered());
        assertEquals(RecruiterNextStep.RECRUITER_SIGNUP_FORM, response.nextStep());
        assertEquals("Complete your HireMeBot recruiter registration.", response.message());
        assertNull(response.user());
        verify(identityService).resolve(TOKEN, BEARER);
        verify(users).findByEmail(connection, "recruiter@gmail.com");
        verify(users, never()).insert(any(), any());
        verify(identities, never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void aNewIdentityWhoseEmailIsAlreadyRegisteredIsAConflictNotTheSignupForm() throws SQLException {
        for (ExistingAccount owner : new ExistingAccount[] {
                new ExistingAccount(7, AccountStatus.ACTIVE, false, true, false, false),    // a student
                new ExistingAccount(8, AccountStatus.PENDING, false, false, true, false),   // a recruiter
                new ExistingAccount(9, AccountStatus.SUSPENDED, false, false, true, false)}) {
            when(users.findByEmail(connection, "recruiter@gmail.com")).thenReturn(Optional.of(owner));
            RegistrationException e = assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER));
            assertEquals("ACCOUNT_IDENTITY_CONFLICT", e.code(), owner.toString());
            assertEquals(409, e.status());
            assertEquals("This email is already associated with another HireMeBot account.", e.getMessage());
        }
        when(users.findByEmail(connection, "recruiter@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(7, AccountStatus.ACTIVE, true, false, true, false)));
        assertEquals("ACCOUNT_DELETED", assertThrows(RegistrationException.class,
                () -> service.lookup(TOKEN, BEARER)).code());
    }

    @Test
    void cognitoIdentityFailuresStopTheLookupWithRecruiterWording() throws SQLException {
        doThrow(RegistrationException.googleAccountRequired()).when(identityService).resolve(TOKEN, BEARER);
        RegistrationException google = assertThrows(RegistrationException.class, () -> service.lookup(TOKEN, BEARER));
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", google.code());
        assertEquals("Please use Continue with Google to sign in as a recruiter.", google.getMessage());

        doThrow(RegistrationException.emailNotVerified()).when(identityService).resolve(TOKEN, BEARER);
        assertEquals("EMAIL_NOT_VERIFIED", assertThrows(RegistrationException.class,
                () -> service.lookup(TOKEN, BEARER)).code());
        verify(users, never()).findByEmail(any(), any());
    }

    @Test
    void anAccountCreatedMeanwhileInAnotherTabIsFoundOnTheRecheck() throws SQLException {
        when(identities.findProfileByCognitoSub(connection, SUB)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(recruiter(AccountStatus.PENDING, false, IdentityProvider.GOOGLE)));
        // Without the re-check this email match (the user's own new account) would look like a conflict.
        when(users.findByEmail(connection, "recruiter@gmail.com"))
                .thenReturn(Optional.of(new ExistingAccount(1_000_000_001L, AccountStatus.PENDING, false, false, true, false)));

        assertEquals(RecruiterNextStep.APPROVAL_PENDING, service.lookup(TOKEN, BEARER).nextStep());
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
