package org.hmb.model;

/**
 * The account linked to a Cognito subject, as read by the sign-in lookups: the {@code users} columns, which role rows
 * exist ({@code students}, {@code recruiters}, {@code managers}), and the provider of the matching
 * {@code user_identities} row.
 */
public record AccountProfile(long userId, String firstName, String lastName, String email, String phone,
                             boolean emailVerified, boolean phoneVerified, AccountStatus status, boolean deleted,
                             boolean student, boolean recruiter, boolean manager, IdentityProvider provider) {

    /** A profile with no recruiter or manager row (the student lookups and their tests). */
    public AccountProfile(long userId, String firstName, String lastName, String email, String phone,
                          boolean emailVerified, boolean phoneVerified, AccountStatus status, boolean deleted,
                          boolean student, IdentityProvider provider) {
        this(userId, firstName, lastName, email, phone, emailVerified, phoneVerified, status, deleted, student, false,
                false, provider);
    }

    public ExistingAccount existing() {
        return new ExistingAccount(userId, status, deleted, student, recruiter, manager);
    }

    @Override
    public String toString() {
        return "AccountProfile[userId=" + userId + ", status=" + status + ", deleted=" + deleted + ", student="
                + student + ", recruiter=" + recruiter + ", manager=" + manager + ", provider=" + provider
                + ", redacted]";
    }
}
