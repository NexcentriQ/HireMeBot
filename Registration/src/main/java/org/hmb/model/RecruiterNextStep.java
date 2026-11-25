package org.hmb.model;

/**
 * Where the recruiter portal goes next. A separate type from the student {@link NextStep} so that a student response can
 * never carry a recruiter step and vice versa. Both recruiter endpoints derive it from
 * {@link org.hmb.service.RecruiterAccountRules}.
 */
public enum RecruiterNextStep {
    /** A registered, {@link AccountStatus#ACTIVE} (approved) recruiter: open the Recruiter Dashboard. */
    RECRUITER_DASHBOARD,
    /** Authenticated with Google but no HireMeBot account yet: show the recruiter signup form, then register. */
    RECRUITER_SIGNUP_FORM,
    /** A registered recruiter still {@link AccountStatus#PENDING} manual approval: show the "waiting for approval" screen. */
    APPROVAL_PENDING
}
