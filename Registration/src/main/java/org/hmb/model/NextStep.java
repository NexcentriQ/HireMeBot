package org.hmb.model;

/**
 * Where the frontend goes next. Both {@code GET /api/v1/auth/me} and {@code POST /api/v1/students/register} use this
 * one vocabulary, and both derive it from the same rules ({@link org.hmb.service.AccountRules}).
 */
public enum NextStep {
    /** A registered, {@link AccountStatus#ACTIVE} student: open the Student Dashboard. */
    DASHBOARD,
    /** Authenticated with Google but no HireMeBot account yet: show the signup form, then register. */
    SIGNUP_FORM,
    /** A registered student whose account is {@link AccountStatus#PENDING}: show the pending/onboarding screen. */
    ACCOUNT_PENDING
}
