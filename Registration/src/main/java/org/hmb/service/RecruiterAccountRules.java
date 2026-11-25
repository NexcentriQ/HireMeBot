package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.RecruiterNextStep;

/**
 * The single source of the recruiter account decisions, used by both {@code GET /api/v1/recruiters/auth/me} and
 * {@code POST /api/v1/recruiters/register} so the two endpoints can never disagree about an account. The student
 * counterpart is {@link AccountRules}; this class has its own table and recruiter wording.
 *
 * <p>Precedence for an account found by the caller's Cognito subject:
 * <ol>
 *   <li>soft-deleted &rarr; 403 {@code ACCOUNT_DELETED}</li>
 *   <li>no {@code recruiters} row (a student, a manager, or a user with no role row) &rarr; 403
 *       {@code ACCOUNT_TYPE_MISMATCH}. This comes before suspension so that a suspended <em>student</em> is told it is
 *       another account type rather than that "your recruiter account" is suspended. Accounts are never converted.</li>
 *   <li>{@code SUSPENDED} &rarr; 403 {@code ACCOUNT_SUSPENDED}</li>
 *   <li>identity not recorded as {@code GOOGLE} &rarr; 403 {@code GOOGLE_ACCOUNT_REQUIRED} (sign-in only)</li>
 *   <li>{@code PENDING} &rarr; {@link RecruiterNextStep#APPROVAL_PENDING}; {@code ACTIVE} &rarr;
 *       {@link RecruiterNextStep#RECRUITER_DASHBOARD}</li>
 * </ol>
 * An account found only by email (a different Cognito identity) is never linked: deleted &rarr; 403
 * {@code ACCOUNT_DELETED}, anything else &rarr; 409 {@code ACCOUNT_IDENTITY_CONFLICT}. Its role and its suspension are
 * deliberately not revealed: the caller has no account of their own, the true fact is the email conflict.
 *
 * <p>A recruiter is only ever <em>created</em> {@link #REGISTRATION_STATUS}; there is no automatic activation. The only
 * way to {@code ACTIVE} is the operator's manual {@code UPDATE} (see {@code Registration/db/recruiter-approval.sql}).
 */
public final class RecruiterAccountRules {

    /** Every new recruiter starts here, whatever the student phone-verification policy says. */
    public static final AccountStatus REGISTRATION_STATUS = AccountStatus.PENDING;

    private RecruiterAccountRules() {
    }

    /** The next step for a usable recruiter account (also what a registration response reports). */
    public static RecruiterNextStep nextStepFor(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> RecruiterNextStep.RECRUITER_DASHBOARD;
            case PENDING -> RecruiterNextStep.APPROVAL_PENDING;
            case SUSPENDED -> throw new IllegalArgumentException("A suspended account has no next step");
        };
    }

    /**
     * Sign-in ({@code /recruiters/auth/me}) for an account linked to the caller's Cognito subject.
     *
     * @return {@link RecruiterNextStep#RECRUITER_DASHBOARD} or {@link RecruiterNextStep#APPROVAL_PENDING}
     * @throws RegistrationException 403 for a deleted, other-type, suspended or non-Google account
     */
    public static RecruiterNextStep signIn(ExistingAccount account, IdentityProvider provider) {
        RegistrationException restricted = restriction(account);
        if (restricted != null) {
            throw restricted;
        }
        if (provider != IdentityProvider.GOOGLE) {
            throw RegistrationException.recruiterGoogleAccountRequired();
        }
        return nextStepFor(account.status());
    }

    /**
     * Registration for a Cognito subject that is already stored: always a refusal, with the same restriction codes as
     * sign-in, otherwise 409 {@code ACCOUNT_ALREADY_REGISTERED} (also for a still-{@code PENDING} recruiter).
     */
    public static RegistrationException refuseRegisteredIdentity(ExistingAccount account) {
        RegistrationException restricted = restriction(account);
        return restricted != null ? restricted : RegistrationException.alreadyRegistered();
    }

    /** Sign-in or registration whose verified email is already used by a <em>different</em> identity. */
    public static RegistrationException refuseEmailOwner(ExistingAccount account) {
        return account.deleted() ? RegistrationException.accountDeleted()
                : RegistrationException.recruiterIdentityConflict();
    }

    /**
     * The shared Cognito identity check words one error for students ("...sign in as a student"); the recruiter portal
     * gets the recruiter wording for it and every other error unchanged.
     */
    public static RegistrationException recruiterWording(RegistrationException e) {
        return "GOOGLE_ACCOUNT_REQUIRED".equals(e.code()) ? RegistrationException.recruiterGoogleAccountRequired() : e;
    }

    private static RegistrationException restriction(ExistingAccount account) {
        if (account.deleted()) {
            // Recovering or reactivating a deleted account is a future, explicit recovery flow.
            return RegistrationException.accountDeleted();
        }
        if (!account.recruiter()) {
            return RegistrationException.recruiterTypeMismatch();
        }
        if (account.status() == AccountStatus.SUSPENDED) {
            return RegistrationException.recruiterAccountSuspended();
        }
        return null;
    }
}
