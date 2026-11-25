package org.hmb.model;

/**
 * An already-stored {@code users} row found by Cognito subject or by email: just what is needed to decide how a
 * sign-in or registration attempt is answered.
 *
 * @param student   whether a {@code students} row exists for this user
 * @param recruiter whether a {@code recruiters} row exists for this user
 * @param manager   whether a {@code managers} row exists for this user
 */
public record ExistingAccount(long userId, AccountStatus status, boolean deleted, boolean student, boolean recruiter,
                              boolean manager) {

    /** A student account that is not a recruiter or manager. */
    public ExistingAccount(long userId, AccountStatus status, boolean deleted, boolean student) {
        this(userId, status, deleted, student, false, false);
    }

    /** A student account (the common case in tests). */
    public ExistingAccount(long userId, AccountStatus status, boolean deleted) {
        this(userId, status, deleted, true, false, false);
    }
}
