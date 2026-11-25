package org.hmb.service;

import org.hmb.exception.RegistrationException;
import org.hmb.model.AccountStatus;
import org.hmb.model.ExistingAccount;
import org.hmb.model.IdentityProvider;
import org.hmb.model.NextStep;

/**
 * The single source of the account-status decisions, used by both {@code GET /api/v1/auth/me} and
 * {@code POST /api/v1/students/register} so the two endpoints can never disagree about an account.
 *
 * <p>Precedence for an account found by the caller's Cognito subject:
 * <ol>
 *   <li>soft-deleted &rarr; 403 {@code ACCOUNT_DELETED}</li>
 *   <li>{@code SUSPENDED} &rarr; 403 {@code ACCOUNT_SUSPENDED}</li>
 *   <li>no {@code students} row (recruiter, manager) &rarr; 403 {@code ACCOUNT_TYPE_MISMATCH}</li>
 *   <li>identity not recorded as {@code GOOGLE} &rarr; 403 {@code GOOGLE_ACCOUNT_REQUIRED} (sign-in only)</li>
 *   <li>{@code PENDING} &rarr; {@link NextStep#ACCOUNT_PENDING}; {@code ACTIVE} &rarr; {@link NextStep#DASHBOARD}</li>
 * </ol>
 * An account found only by email (a different Cognito identity) is never linked: deleted &rarr; 403
 * {@code ACCOUNT_DELETED}, suspended &rarr; 403 {@code ACCOUNT_SUSPENDED}, otherwise 409
 * {@code ACCOUNT_IDENTITY_CONFLICT}. Its account type is not revealed.
 */
public final class AccountRules {

    private AccountRules() {
    }

    /** The next step for a usable student account, also used for the registration response. */
    public static NextStep nextStepFor(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> NextStep.DASHBOARD;
            case PENDING -> NextStep.ACCOUNT_PENDING;
            case SUSPENDED -> throw new IllegalArgumentException("A suspended account has no next step");
        };
    }

    /**
     * Sign-in ({@code /auth/me}) for an account linked to the caller's Cognito subject.
     *
     * @return {@link NextStep#DASHBOARD} or {@link NextStep#ACCOUNT_PENDING}
     * @throws RegistrationException 403 for a deleted, suspended, non-student or non-Google account
     */
    public static NextStep signIn(ExistingAccount account, IdentityProvider provider) {
        RegistrationException restricted = restriction(account);
        if (restricted != null) {
            throw restricted;
        }
        if (provider != IdentityProvider.GOOGLE) {
            throw RegistrationException.googleAccountRequired();
        }
        return nextStepFor(account.status());
    }

    /**
     * Registration for a Cognito subject that is already stored: always a refusal, with the same restriction codes as
     * sign-in, otherwise 409 {@code ACCOUNT_ALREADY_REGISTERED}.
     */
    public static RegistrationException refuseRegisteredIdentity(ExistingAccount account) {
        RegistrationException restricted = restriction(account);
        return restricted != null ? restricted : RegistrationException.alreadyRegistered();
    }

    /** Sign-in or registration whose verified email is already used by a <em>different</em> identity. */
    public static RegistrationException refuseEmailOwner(ExistingAccount account) {
        if (account.deleted()) {
            return RegistrationException.accountDeleted();
        }
        if (account.status() == AccountStatus.SUSPENDED) {
            return RegistrationException.accountSuspended();
        }
        return RegistrationException.identityConflict();
    }

    private static RegistrationException restriction(ExistingAccount account) {
        if (account.deleted()) {
            // Recovering or reactivating a deleted account is a future, explicit recovery flow.
            return RegistrationException.accountDeleted();
        }
        if (account.status() == AccountStatus.SUSPENDED) {
            return RegistrationException.accountSuspended();
        }
        if (!account.student()) {
            return RegistrationException.accountTypeMismatch();
        }
        return null;
    }
}
