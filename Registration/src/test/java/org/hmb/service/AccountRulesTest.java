package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NextStep;
import org.junit.jupiter.api.Test;

import static org.hmb.model.AccountStatus.ACTIVE;
import static org.hmb.model.AccountStatus.PENDING;
import static org.hmb.model.AccountStatus.SUSPENDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The account-status decision table shared by GET /auth/me and POST /students/register. */
class AccountRulesTest {

    private static ExistingAccount account(AccountStatus status, boolean deleted, boolean student) {
        return new ExistingAccount(1_000_000_000L, status, deleted, student);
    }

    @Test
    void activeStudentGoesToTheDashboardAndPendingToThePendingScreen() {
        assertEquals(NextStep.DASHBOARD, AccountRules.signIn(account(ACTIVE, false, true), IdentityProvider.GOOGLE));
        assertEquals(NextStep.ACCOUNT_PENDING,
                AccountRules.signIn(account(PENDING, false, true), IdentityProvider.GOOGLE));
        assertEquals(NextStep.DASHBOARD, AccountRules.nextStepFor(ACTIVE));
        assertEquals(NextStep.ACCOUNT_PENDING, AccountRules.nextStepFor(PENDING));
    }

    @Test
    void signInRestrictionsInPrecedenceOrder() {
        assertSignIn(account(ACTIVE, true, true), "ACCOUNT_DELETED", 403);
        assertSignIn(account(SUSPENDED, true, false), "ACCOUNT_DELETED", 403);      // deleted wins
        assertSignIn(account(SUSPENDED, false, true), "ACCOUNT_SUSPENDED", 403);
        assertSignIn(account(SUSPENDED, false, false), "ACCOUNT_SUSPENDED", 403);   // suspended before type
        assertSignIn(account(ACTIVE, false, false), "ACCOUNT_TYPE_MISMATCH", 403);  // recruiter / manager
        assertSignIn(account(PENDING, false, false), "ACCOUNT_TYPE_MISMATCH", 403);
        RegistrationException nonGoogle = assertThrows(RegistrationException.class,
                () -> AccountRules.signIn(account(ACTIVE, false, true), IdentityProvider.COGNITO));
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", nonGoogle.code());
    }

    @Test
    void registrationOfAStoredIdentityUsesTheSameRestrictions() {
        assertEquals("ACCOUNT_DELETED", AccountRules.refuseRegisteredIdentity(account(ACTIVE, true, true)).code());
        assertEquals("ACCOUNT_SUSPENDED",
                AccountRules.refuseRegisteredIdentity(account(SUSPENDED, false, true)).code());
        assertEquals("ACCOUNT_TYPE_MISMATCH",
                AccountRules.refuseRegisteredIdentity(account(ACTIVE, false, false)).code());
        RegistrationException registered = AccountRules.refuseRegisteredIdentity(account(PENDING, false, true));
        assertEquals("ACCOUNT_ALREADY_REGISTERED", registered.code());
        assertEquals(409, registered.status());
    }

    @Test
    void anEmailOwnedByAnotherIdentityIsNeverLinkedAndItsTypeIsNotRevealed() {
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", AccountRules.refuseEmailOwner(account(ACTIVE, false, true)).code());
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", AccountRules.refuseEmailOwner(account(ACTIVE, false, false)).code());
        assertEquals("ACCOUNT_IDENTITY_CONFLICT", AccountRules.refuseEmailOwner(account(PENDING, false, true)).code());
        assertEquals(409, AccountRules.refuseEmailOwner(account(ACTIVE, false, true)).status());
        assertEquals("ACCOUNT_DELETED", AccountRules.refuseEmailOwner(account(ACTIVE, true, true)).code());
        assertEquals("ACCOUNT_SUSPENDED", AccountRules.refuseEmailOwner(account(SUSPENDED, false, true)).code());
    }

    @Test
    void restrictedMessagesMatchTheContract() {
        assertEquals("Your account is currently suspended.", RegistrationException.accountSuspended().getMessage());
        assertEquals("This account is unavailable.", RegistrationException.accountDeleted().getMessage());
        assertEquals(403, RegistrationException.accountDeleted().status());
        assertEquals("An existing account requires a different sign-in or account recovery method.",
                RegistrationException.identityConflict().getMessage());
        assertEquals("An account with this name already exists. Please contact support.",
                RegistrationException.nameAlreadyRegistered().getMessage());
    }

    private static void assertSignIn(ExistingAccount account, String code, int status) {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> AccountRules.signIn(account, IdentityProvider.GOOGLE));
        assertEquals(code, e.code());
        assertEquals(status, e.status());
    }
}
