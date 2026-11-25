package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;
import org.junit.jupiter.api.Test;

import static org.hmb.model.AccountStatus.ACTIVE;
import static org.hmb.model.AccountStatus.PENDING;
import static org.hmb.model.AccountStatus.SUSPENDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The recruiter decision table shared by GET /recruiters/auth/me and POST /recruiters/register. */
class RecruiterAccountRulesTest {

    private static ExistingAccount recruiter(AccountStatus status, boolean deleted) {
        return new ExistingAccount(1_000_000_001L, status, deleted, false, true, false);
    }

    private static ExistingAccount student(AccountStatus status, boolean deleted) {
        return new ExistingAccount(1_000_000_000L, status, deleted, true, false, false);
    }

    private static ExistingAccount manager(AccountStatus status) {
        return new ExistingAccount(1_000_000_003L, status, false, false, false, true);
    }

    private static ExistingAccount noRole(AccountStatus status) {
        return new ExistingAccount(1_000_000_004L, status, false, false, false, false);
    }

    @Test
    void activeRecruiterGoesToTheDashboardAndPendingToApprovalPending() {
        assertEquals(RecruiterNextStep.RECRUITER_DASHBOARD,
                RecruiterAccountRules.signIn(recruiter(ACTIVE, false), IdentityProvider.GOOGLE));
        assertEquals(RecruiterNextStep.APPROVAL_PENDING,
                RecruiterAccountRules.signIn(recruiter(PENDING, false), IdentityProvider.GOOGLE));
        assertEquals(RecruiterNextStep.RECRUITER_DASHBOARD, RecruiterAccountRules.nextStepFor(ACTIVE));
        assertEquals(RecruiterNextStep.APPROVAL_PENDING, RecruiterAccountRules.nextStepFor(PENDING));
    }

    @Test
    void everyNewRecruiterStartsPending() {
        assertSame(PENDING, RecruiterAccountRules.REGISTRATION_STATUS);
    }

    @Test
    void signInRestrictionsInPrecedenceOrder() {
        assertSignIn(recruiter(ACTIVE, true), "ACCOUNT_DELETED", "This account is unavailable.");
        assertSignIn(recruiter(SUSPENDED, true), "ACCOUNT_DELETED", "This account is unavailable."); // deleted wins
        assertSignIn(student(ACTIVE, true), "ACCOUNT_DELETED", "This account is unavailable.");
        assertSignIn(recruiter(SUSPENDED, false), "ACCOUNT_SUSPENDED", "Your recruiter account has been suspended.");
    }

    @Test
    void aStudentManagerOrRoleLessIdentityIsAnAccountTypeMismatchNeverANewRecruiter() {
        String message = "This Google account is already associated with another HireMeBot account type.";
        assertSignIn(student(ACTIVE, false), "ACCOUNT_TYPE_MISMATCH", message);
        assertSignIn(student(PENDING, false), "ACCOUNT_TYPE_MISMATCH", message);
        assertSignIn(manager(ACTIVE), "ACCOUNT_TYPE_MISMATCH", message);
        assertSignIn(noRole(ACTIVE), "ACCOUNT_TYPE_MISMATCH", message);
        // The role is checked before suspension: a suspended student is not told "your recruiter account".
        assertSignIn(student(SUSPENDED, false), "ACCOUNT_TYPE_MISMATCH", message);
        assertSignIn(manager(SUSPENDED), "ACCOUNT_TYPE_MISMATCH", message);
    }

    @Test
    void aNonGoogleIdentityNeedsGoogle() {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> RecruiterAccountRules.signIn(recruiter(ACTIVE, false), IdentityProvider.COGNITO));
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", e.code());
        assertEquals("Please use Continue with Google to sign in as a recruiter.", e.getMessage());
    }

    @Test
    void registrationOfAStoredIdentityUsesTheSameRestrictions() {
        assertEquals("ACCOUNT_DELETED", RecruiterAccountRules.refuseRegisteredIdentity(recruiter(ACTIVE, true)).code());
        assertEquals("ACCOUNT_SUSPENDED",
                RecruiterAccountRules.refuseRegisteredIdentity(recruiter(SUSPENDED, false)).code());
        assertEquals("ACCOUNT_TYPE_MISMATCH",
                RecruiterAccountRules.refuseRegisteredIdentity(student(ACTIVE, false)).code());
        assertEquals("ACCOUNT_TYPE_MISMATCH",
                RecruiterAccountRules.refuseRegisteredIdentity(manager(PENDING)).code());
        for (AccountStatus status : new AccountStatus[] {PENDING, ACTIVE}) {
            RegistrationException registered = RecruiterAccountRules.refuseRegisteredIdentity(recruiter(status, false));
            assertEquals("ACCOUNT_ALREADY_REGISTERED", registered.code());
            assertEquals(409, registered.status());
        }
    }

    @Test
    void anEmailOwnedByAnotherIdentityIsNeverLinkedAndRevealsNeitherItsRoleNorItsStatus() {
        String message = "This email is already associated with another HireMeBot account.";
        for (ExistingAccount owner : new ExistingAccount[] {student(ACTIVE, false), recruiter(PENDING, false),
                recruiter(ACTIVE, false), manager(ACTIVE), student(SUSPENDED, false), recruiter(SUSPENDED, false)}) {
            RegistrationException e = RecruiterAccountRules.refuseEmailOwner(owner);
            assertEquals("ACCOUNT_IDENTITY_CONFLICT", e.code(), owner.toString());
            assertEquals(409, e.status());
            assertEquals(message, e.getMessage());
        }
        assertEquals("ACCOUNT_DELETED", RecruiterAccountRules.refuseEmailOwner(student(ACTIVE, true)).code());
        assertEquals(403, RecruiterAccountRules.refuseEmailOwner(recruiter(ACTIVE, true)).status());
    }

    @Test
    void theSharedGoogleRequiredErrorGetsRecruiterWordingAndNothingElseChanges() {
        RegistrationException reworded = RecruiterAccountRules.recruiterWording(
                RegistrationException.googleAccountRequired());
        assertEquals("GOOGLE_ACCOUNT_REQUIRED", reworded.code());
        assertEquals("Please use Continue with Google to sign in as a recruiter.", reworded.getMessage());

        RegistrationException other = RegistrationException.emailNotVerified();
        assertSame(other, RecruiterAccountRules.recruiterWording(other));
    }

    @Test
    void aSuspendedAccountHasNoNextStep() {
        assertThrows(IllegalArgumentException.class, () -> RecruiterAccountRules.nextStepFor(SUSPENDED));
    }

    private static void assertSignIn(ExistingAccount account, String code, String message) {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> RecruiterAccountRules.signIn(account, IdentityProvider.GOOGLE), account.toString());
        assertEquals(code, e.code(), account.toString());
        assertEquals(403, e.status());
        assertEquals(message, e.getMessage());
    }
}
