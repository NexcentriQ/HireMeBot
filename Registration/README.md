# Registration Lambda: unified "Continue with Google" for students and recruiters

One Lambda (SAM resource `RegistrationFunction`, Maven module `Registration/`) serves the sign-in flow of both portals:

| Route | Purpose | Writes? |
|---|---|---|
| `GET /api/v1/auth/me` | Student: who is this Google user? Answers `DASHBOARD`, `SIGNUP_FORM`, `ACCOUNT_PENDING`, or a restriction | never |
| `POST /api/v1/students/register` | Create the student (`users` + `students` + `user_identities`, one transaction), `ACTIVE` under the V1 policy | yes |
| `GET /api/v1/recruiters/auth/me` | Recruiter: answers `RECRUITER_DASHBOARD`, `RECRUITER_SIGNUP_FORM`, `APPROVAL_PENDING`, or a restriction | never |
| `POST /api/v1/recruiters/register` | Create the recruiter (`users` + `recruiters` + `user_identities`, one transaction), always `PENDING` until the operator approves it in MySQL | yes |

The frontend contracts are [docs/STUDENT_AUTH_API.md](../docs/STUDENT_AUTH_API.md) and
[docs/RECRUITER_AUTH_API.md](../docs/RECRUITER_AUTH_API.md). The recruiter flow is described in
[Recruiter registration](#recruiter-registration-open-signup-manual-approval) below; everything else on this page
applies to both roles (one verifier, one set of Cognito clients, one pool, one transaction core).

- Runtime: Java 21, handler `org.hmb.handler.RegistrationHandler::handleRequest`; all four routes use the Cognito JWT
  authorizer `CognitoAccessToken` with scope `openid`.
- Cognito: pool `us-east-2_F582IgoSY`, app client `5rgjoisv64qhe70oulfla17id9`, domain
  `us-east-2f582igosy.auth.us-east-2.amazoncognito.com`. The Google IdP maps `email`, `email_verified` and
  `username` (verified 2026-11-07).
- Database: `hiremebot_db_v1`. The live local schema matches `hiremebot_db_V1.sql` rev. 3 (verified 2026-11-07); see
  [the full-name index](#the-unique-first_name-last_name-index).
- Account policy: `PHONE_VERIFICATION_REQUIRED=false`. New students are `ACTIVE`, go to the dashboard, and have a
  verified Google email and an **unverified** phone.

## Flow

```text
Continue with Google ─► Cognito + Google ─► access token
  │
  ▼  GET /api/v1/auth/me                     (RegistrationHandler → AccountLookupService, read-only)
  verify JWT locally (signature/JWKS, iss, client_id, token_use=access, exp)        401
  SELECT … FROM user_identities ⋈ users ⟕ students WHERE cognito_sub = <token sub>
  ├─ found  → AccountRules.signIn: deleted 403 · suspended 403 · not a student 403 · not GOOGLE 403
  │           · PENDING → ACCOUNT_PENDING · ACTIVE → DASHBOARD (+ profile)          (no Cognito call)
  └─ absent → CognitoIdentityService: UserInfo (sub match, verified email) + AdminGetUser (Google federated)
              re-check sub, then SELECT … FROM users WHERE email = <verified email>
              ├─ email taken by another identity → 409 ACCOUNT_IDENTITY_CONFLICT (or 403 deleted/suspended)
              └─ free → 200 SIGNUP_FORM                                             (nothing inserted)
  │
  ▼  POST /api/v1/students/register         (after SIGNUP_FORM + form + mock OTP; same session)
  verify JWT again · validate body (400) · UserInfo + AdminGetUser again (401/403)
  BEGIN: re-check subject + email (AccountRules) → INSERT users → students → user_identities → COMMIT
  duplicate key / deadlock → ROLLBACK → re-check → 409 ALREADY_REGISTERED | IDENTITY_CONFLICT | NAME_ALREADY_REGISTERED
  → 201 data.nextStep = DASHBOARD (V1 policy)
```

Registration never trusts the earlier lookup: it repeats every identity and uniqueness check.

## Account-status decision logic

All decisions live in [`AccountRules`](src/main/java/org/hmb/service/AccountRules.java), used by both routes:

| Situation | `GET /auth/me` | `POST /students/register` |
|---|---|---|
| New student: subject unknown, verified Google email unused | 200 `SIGNUP_FORM`, `registered: false` | 201 `STUDENT_REGISTERED`, `nextStep` from the policy |
| Existing `ACTIVE` student | 200 `DASHBOARD` + profile | 409 `ACCOUNT_ALREADY_REGISTERED` |
| Existing `PENDING` student | 200 `ACCOUNT_PENDING` + profile | 409 `ACCOUNT_ALREADY_REGISTERED` |
| `SUSPENDED` (by subject or by email) | 403 `ACCOUNT_SUSPENDED` | 403 `ACCOUNT_SUSPENDED` |
| `is_deleted` (by subject or by email) | 403 `ACCOUNT_DELETED` | 403 `ACCOUNT_DELETED` (never recreated) |
| Subject belongs to a recruiter/manager (no `students` row) | 403 `ACCOUNT_TYPE_MISMATCH` | 403 `ACCOUNT_TYPE_MISMATCH` |
| Student identity not recorded as `GOOGLE` | 403 `GOOGLE_ACCOUNT_REQUIRED` | n/a (subject exists → 409) |
| Unknown subject, email used by another identity | 409 `ACCOUNT_IDENTITY_CONFLICT` | 409 `ACCOUNT_IDENTITY_CONFLICT` (never linked) |
| Not a Google-federated Cognito user / unverified email | 403 `GOOGLE_ACCOUNT_REQUIRED` / `EMAIL_NOT_VERIFIED` | same |
| Same first + last name as another user (only if the index exists) | n/a (name unknown) | 409 `NAME_ALREADY_REGISTERED` |

Precedence for an account found by subject: deleted → suspended → not a student → not Google → `PENDING`/`ACTIVE`.
An email owned by another identity never reveals that account's type.

## Code

| Package | Classes |
|---|---|
| `handler` | `RegistrationHandler` (routes `ME` and `REGISTER`, token, content type, size, envelope), `RegistrationRequestParser` |
| `service` | `AccountLookupService` (new), `AccountRules` (new), `StudentRegistrationService`, `CognitoIdentityService`, `RegistrationValidator`, `DatabaseFailures` |
| `repository` | `UserIdentityRepository` (`findProfileByCognitoSub` new), `UserRepository`, `StudentRepository`, `UniqueKeyCatalog` (new) |
| `security` | `CognitoAccessTokenVerifier`, `CognitoUserInfoClient`, `CognitoAdminUserLookup`, `BearerToken`, `VerifiedAccessToken`, `CognitoUserInfo`, `CognitoUserRecord`, `AuthenticatedIdentity` (all reused unchanged) |
| `model` | `NextStep` (new, replaces `NextAction`), `AccountProfile` (new), `ExistingAccount` (+ `student`), `AccountStatus`, `IdentityProvider`, `NewStudentUser`, `ValidatedRegistration` |
| `dto` | `AccountLookupResponse` (new), `StudentRegistrationData` (`nextStep`), `ApiResponse`, `FieldError`, `StudentRegistrationRequest` |
| `config` | `RegistrationConfig`, `RegistrationDataSource` |
| `exception` | `RegistrationException` (+ `ACCOUNT_TYPE_MISMATCH`, `NAME_ALREADY_REGISTERED`) |

The lookup reuses the registration module's verifier, Cognito clients, connection pool, repositories and error
envelope. There is no second JWT library or verifier.

## SQL

All statements are prepared statements.

```sql
-- GET /auth/me: one row at most (uq_cognito_sub), then users and students by primary key.
SELECT u.user_id, u.first_name, u.last_name, u.email, u.phone, u.email_verified, u.phone_verified,
       u.account_status, u.is_deleted, s.student_id, ui.provider
FROM user_identities ui
INNER JOIN users u ON u.user_id = ui.user_id
LEFT JOIN students s ON s.student_id = u.user_id
WHERE ui.cognito_sub = ?;

-- Email check (lookup of a new identity, and the registration pre-check): UNIQUE(email) index.
SELECT u.user_id, u.account_status, u.is_deleted, s.student_id
FROM users u LEFT JOIN students s ON s.student_id = u.user_id
WHERE u.email = ?;

-- Registration pre-check by subject (inside the transaction).
SELECT u.user_id, u.account_status, u.is_deleted, s.student_id
FROM user_identities ui JOIN users u ON u.user_id = ui.user_id LEFT JOIN students s ON s.student_id = u.user_id
WHERE ui.cognito_sub = ?;

-- Registration transaction (one connection, autoCommit=false; generated key via getGeneratedKeys()).
INSERT INTO users (first_name, last_name, email, phone, email_verified, phone_verified,
                   account_status, terms_accepted_at, terms_version, is_deleted)
VALUES (?, ?, ?, ?, TRUE, FALSE, ?, ?, ?, FALSE);      -- status from PHONE_VERIFICATION_REQUIRED (V1: ACTIVE)
INSERT INTO students (student_id) VALUES (?);
INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, ?, ?);   -- 'GOOGLE', verified sub
COMMIT;

-- Only after a duplicate-key error: which unique index was violated (cached per container).
SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND NON_UNIQUE = 0
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
```

**Why `LEFT JOIN students` and no `provider = 'GOOGLE'` filter (unlike the suggested query).** With an inner join or
a provider filter, a recruiter's or manager's identity would not be found, and would then be treated as a brand-new
student and sent to the signup form. Reading the row and deciding in `AccountRules` gives `ACCOUNT_TYPE_MISMATCH` or
`GOOGLE_ACCOUNT_REQUIRED` instead.

**Indexes used.** These already exist; no schema change is needed:
- `user_identities.uq_cognito_sub`: the lookup and the registration pre-check.
- `users.PRIMARY` and `students.PRIMARY`: the joins.
- `users.email`: the email check, and the email collision guard under concurrency.
- `uq_user_provider`: one identity per provider per user.

`users.email` uses the case- and accent-insensitive `utf8mb4_0900_ai_ci` collation; emails are lower-cased before
use.

### The unique (first_name, last_name) index

The owner keeps `UNIQUE (first_name, last_name)`. **On 2026-11-07 the live local `hiremebot_db_v1.users` did not
have it.** Its only unique keys were `PRIMARY` and `email`, because the database had been recreated from the V1
script, which does not declare it. Nothing in this task adds or removes it. To restore it (owner decision; not
run):

```sql
ALTER TABLE users ADD UNIQUE (first_name, last_name);   -- MySQL names it `first_name`
```

The code works either way:

- **Without the index:** two students with the same name can register.
- **With the index:** the second one gets `409 NAME_ALREADY_REGISTERED`. The transaction is rolled back and the other
  user's row is not touched.

The violated index is identified by MySQL error 1062 plus its **columns** from `information_schema`
(`UniqueKeyCatalog`), not by a hard-coded name. Only the key name at the very end of the driver message is read, so a
name containing `for key '…'` cannot spoof it, and the duplicated values are never logged or returned. A duplicate
on email or subject is re-checked first and reported as `ACCOUNT_ALREADY_REGISTERED` or `ACCOUNT_IDENTITY_CONFLICT`,
never as a name conflict. The index collation also treats `JOSE garcia` as equal to `José García` (tested).

## Security

- **JWT verification.** `CognitoAccessTokenVerifier` runs on both routes, in the Lambda, on every request, so it
  also protects `sam local` (which skips JWT authorizers) and direct invocations. It requires:
  - an RS256 signature by the pool's JWKS (no `alg=none`, HMAC, unknown `kid` or JWE);
  - exact `iss`, `token_use=access` and `client_id`;
  - valid `exp`/`iat` (30 s skew), and well-formed `sub`/`username`;
  - an `openid` scope.

  API Gateway authorizer claims, when present, must name the same `sub`. Failures are a generic 401; the reason is
  logged without the token.
- **Identity.** The subject comes only from the verified token. Query parameters and identity fields in the body
  (`email`, `cognitoSub`, `userId`, `phoneVerified`, OTP values) are ignored.
  - For an unknown subject, the email comes from Cognito UserInfo, called with the user's own token (it must return
    the same `sub` and `email_verified=true`).
  - Google federation is proven by `AdminGetUser`: `EXTERNAL_PROVIDER` status and Google in `identities`.
  - Identities are never linked automatically.
- **Known subject: no Cognito call.** A registered subject was proven Google-federated at registration, and Cognito
  subjects never change. Like the JWT authorizer, this path does not consult Cognito's token-revocation list: a
  signed-out access token keeps working until it expires (at most 60 minutes). Registration calls UserInfo, which
  does reject revoked tokens.
- **No writes on lookup.** `AccountLookupService` contains no write statement. Integration tests assert that row
  counts and `updated_at` are unchanged after repeated lookups.
- **Responses.** No tokens, no Cognito subject, no SQL or stack traces. `Cache-Control: no-store`. Every error
  carries a `requestId` (API Gateway's id when deployed, the Lambda id under `sam local`).

### Student dashboard authorization (audit and contract)

Cognito authentication alone must not open protected student APIs. The only student API today is
`GET /api/v1/student/applications` (StudentLambda). The audit found:

| Finding | Risk | Required change (not implemented here: separate task) |
|---|---|---|
| Identity is the `X-Dev-Student-Id` header when `ALLOW_DEV_STUDENT_HEADER=true`; otherwise 403 (fails closed) | If the flag were ever set in a deployed stack, any caller could read any student's data. Unlike DocumentManagement's `DevAuth`, it is not also gated on `AWS_SAM_LOCAL` | Replace with Cognito: verify the access token, then look up `cognito_sub` → `user_id` server-side. Never accept a client `studentId`. Gate the dev header on `AWS_SAM_LOCAL` until it is removed |
| The route has no authorizer in `template.yaml` | Unauthenticated calls reach the Lambda | Add `Auth: { Authorizer: CognitoAccessToken, AuthorizationScopes: [openid] }` |
| `StudentRepository.findActiveProfile` checks `is_deleted = FALSE` but not `account_status` | Suspended or pending students could see the dashboard | Require `account_status = 'ACTIVE'` as well |

The contract for every protected student endpoint is:

1. Verify the access token.
2. `findProfileByCognitoSub`.
3. Require `AccountRules.signIn(...) == DASHBOARD`, i.e. a student row, `ACTIVE`, not deleted and Google.
   - Unknown subject → 403 `ACCOUNT_NOT_REGISTERED`.
   - `ACCOUNT_PENDING` → 403 `ACCOUNT_PENDING`.
   - Restricted → the same 403 codes as `/auth/me`.
4. Use the resulting `user_id` as the student id, then apply endpoint-specific ownership checks.

To reuse this, move `CognitoAccessTokenVerifier`, `UserIdentityRepository.findProfileByCognitoSub` and `AccountRules`
into `common`.

## Recruiter registration (open signup, manual approval)

Anyone with a verified Google account can register as a recruiter, but **every new recruiter is stored `PENDING`** and
only becomes usable when the owner changes `users.account_status` to `ACTIVE` in MySQL. There is no approval API, no
approval table and no automatic activation. The status is fixed in code
(`RecruiterAccountRules.REGISTRATION_STATUS`) and does not depend on `PHONE_VERIFICATION_REQUIRED`, which is a
student-only policy.

```text
Continue with Google ─► access token
  │
  ▼  GET /api/v1/recruiters/auth/me          (RecruiterAccountLookupService, read-only)
  verify JWT locally (same CognitoAccessTokenVerifier as the student routes)             401
  SELECT … FROM user_identities ⋈ users ⟕ students ⟕ recruiters ⟕ managers WHERE cognito_sub = <token sub>
  ├─ found  → RecruiterAccountRules.signIn: deleted 403 · no recruiters row 403 · suspended 403 · not GOOGLE 403
  │           · PENDING → APPROVAL_PENDING · ACTIVE → RECRUITER_DASHBOARD (+ profile)   (no Cognito call)
  └─ absent → CognitoIdentityService (UserInfo verified email + AdminGetUser Google federation), re-check subject,
              then SELECT … FROM users WHERE email = <verified email>
              ├─ email taken by another identity → 409 ACCOUNT_IDENTITY_CONFLICT (or 403 if that account is deleted)
              └─ free → 200 RECRUITER_SIGNUP_FORM                                        (nothing inserted)
  │
  ▼  POST /api/v1/recruiters/register        (RecruiterRegistrationService → AccountRegistrar)
  verify JWT · validate body (same validator as students) · UserInfo + AdminGetUser again
  BEGIN: re-check subject + email → INSERT users (PENDING) → INSERT recruiters (manager_id NULL)
         → INSERT user_identities (GOOGLE, verified sub) → COMMIT
  duplicate key / deadlock → ROLLBACK → re-check → 409 ALREADY_REGISTERED | IDENTITY_CONFLICT | NAME_ALREADY_REGISTERED
  → 201 RECRUITER_REGISTRATION_SUBMITTED, data.nextStep = APPROVAL_PENDING
  │
  ▼  the owner approves in MySQL (db/recruiter-approval.sql) ─► next lookup → RECRUITER_DASHBOARD
```

### Decision table

All decisions live in [`RecruiterAccountRules`](src/main/java/org/hmb/service/RecruiterAccountRules.java), used by both
routes:

| Situation | `GET /recruiters/auth/me` | `POST /recruiters/register` |
|---|---|---|
| New: subject unknown, verified Google email unused | 200 `RECRUITER_SIGNUP_FORM`, `registered: false` | 201 `RECRUITER_REGISTRATION_SUBMITTED`, `PENDING`, `APPROVAL_PENDING` |
| Existing recruiter `ACTIVE` | 200 `RECRUITER_DASHBOARD` + profile | 409 `ACCOUNT_ALREADY_REGISTERED` |
| Existing recruiter `PENDING` | 200 `APPROVAL_PENDING` + identifiers only | 409 `ACCOUNT_ALREADY_REGISTERED` |
| `is_deleted` (by subject, or by email under another identity) | 403 `ACCOUNT_DELETED` | 403 `ACCOUNT_DELETED` (never recreated) |
| Subject belongs to a student, manager or user with no role row | 403 `ACCOUNT_TYPE_MISMATCH` | 403 `ACCOUNT_TYPE_MISMATCH` (never converted) |
| Recruiter `SUSPENDED` (by subject) | 403 `ACCOUNT_SUSPENDED` | 403 `ACCOUNT_SUSPENDED` |
| Recruiter identity not recorded as `GOOGLE` | 403 `GOOGLE_ACCOUNT_REQUIRED` | n/a (subject exists → 409) |
| Unknown subject, email used by another identity (any role or status except deleted) | 409 `ACCOUNT_IDENTITY_CONFLICT` | 409 `ACCOUNT_IDENTITY_CONFLICT` (never linked) |
| Not a Google-federated Cognito user / unverified email | 403 `GOOGLE_ACCOUNT_REQUIRED` / `EMAIL_NOT_VERIFIED` | same |
| Same first + last name as any other user (only if the index exists) | n/a (name unknown) | 409 `NAME_ALREADY_REGISTERED` |

Precedence for an account found by subject: deleted → no recruiter row → suspended → not Google → `PENDING`/`ACTIVE`.
The role is checked before suspension so that a suspended *student* is told "another account type", not "your recruiter
account is suspended". An email owned by another identity never reveals that account's role or suspension. Messages use
recruiter wording; the codes are the same as for students, and the student responses are unchanged.

### Code

| Package | Recruiter classes (new) | Shared and reused unchanged |
|---|---|---|
| `handler` | routes `RECRUITER_ME` / `RECRUITER_REGISTER` in `RegistrationHandler` | `RegistrationRequestParser`, token check, content type, size, envelope |
| `service` | `RecruiterAccountLookupService`, `RecruiterRegistrationService`, `RecruiterAccountRules` | `CognitoIdentityService`, `RegistrationValidator`, `DatabaseFailures`; **`AccountRegistrar`** (extracted from `StudentRegistrationService`: the transaction, pre-checks, rollback, race and name-conflict handling, used by both roles) |
| `repository` | `RecruiterRepository` | `UserRepository`, `UserIdentityRepository` (the three reads gained `LEFT JOIN recruiters` and `LEFT JOIN managers`), `UniqueKeyCatalog` |
| `model` / `dto` | `RecruiterNextStep`, `RecruiterAccountLookupResponse`, `RecruiterRegistrationData` | `ExistingAccount` and `AccountProfile` gained `recruiter`/`manager` flags (old constructors kept) |

`StudentRegistrationService` now delegates its transaction to `AccountRegistrar` with the `students` insert and the student
refusals; its constructors, SQL, messages and responses did not change, and all 196 pre-existing Registration tests
passed unmodified right after the extraction.

### Recruiter SQL

All statements are prepared statements.

```sql
-- GET /recruiters/auth/me: the same lookup as for students; the role is decided from the LEFT JOINs.
SELECT u.user_id, u.first_name, u.last_name, u.email, u.phone, u.email_verified, u.phone_verified,
       u.account_status, u.is_deleted, s.student_id, r.recruiter_id, m.manager_id, ui.provider
FROM user_identities ui
INNER JOIN users u ON u.user_id = ui.user_id
LEFT JOIN students s ON s.student_id = u.user_id
LEFT JOIN recruiters r ON r.recruiter_id = u.user_id
LEFT JOIN managers m ON m.manager_id = u.user_id
WHERE ui.cognito_sub = ?;

-- Email check for a new identity and the registration pre-check (UNIQUE(email) index):
SELECT u.user_id, u.account_status, u.is_deleted, s.student_id, r.recruiter_id, m.manager_id
FROM users u
LEFT JOIN students s ON s.student_id = u.user_id
LEFT JOIN recruiters r ON r.recruiter_id = u.user_id
LEFT JOIN managers m ON m.manager_id = u.user_id
WHERE u.email = ?;

-- Registration transaction (one connection, autoCommit=false; generated key via getGeneratedKeys()).
INSERT INTO users (first_name, last_name, email, phone, email_verified, phone_verified,
                   account_status, terms_accepted_at, terms_version, is_deleted)
VALUES (?, ?, ?, ?, TRUE, FALSE, ?, ?, ?, FALSE);     -- status bind value is always 'PENDING' for recruiters
INSERT INTO recruiters (recruiter_id, manager_id) VALUES (?, NULL);                 -- the same user_id
INSERT INTO user_identities (user_id, provider, cognito_sub) VALUES (?, ?, ?);       -- 'GOOGLE', verified sub
COMMIT;                                                                             -- any failure → ROLLBACK
```

Unlike the query suggested in the task (`INNER JOIN recruiters … AND ui.provider = 'GOOGLE'`), the lookup uses LEFT JOINs
and no provider filter, for the same reason as the student lookup: with an inner join, a student's or manager's identity
would not be found and would be sent to the recruiter registration form instead of getting `ACCOUNT_TYPE_MISMATCH`, and a
non-Google identity would look like a new user. The decision is made in `RecruiterAccountRules`. All indexes used already
exist (`uq_cognito_sub`, the primary keys, `users.email`); no schema change is needed.

### Approving a recruiter (manual MySQL step)

This is the only way a recruiter becomes `ACTIVE`. The application never runs it. The script is
[`db/recruiter-approval.sql`](db/recruiter-approval.sql); the integration tests execute that file's own statements, so
this is exactly what is tested.

1. In MySQL Workbench, on `hiremebot_db_v1`, run the pending query to see who is waiting:

   ```sql
   SELECT u.user_id, u.first_name, u.last_name, u.email, u.phone, u.account_status, u.created_at
   FROM users u
   JOIN recruiters r ON r.recruiter_id = u.user_id
   WHERE u.account_status = 'PENDING' AND u.is_deleted = FALSE
   ORDER BY u.created_at DESC;
   ```

2. Choose **one** recruiter and set its id (the default `0` matches nothing, so running the script unedited is harmless):

   ```sql
   SET @recruiter_id = 1000000001;   -- the user_id from step 1
   ```

3. Approve exactly that recruiter, then read how many rows changed **immediately** (any statement in between resets it):

   ```sql
   UPDATE users
   SET account_status = 'ACTIVE'
   WHERE user_id = @recruiter_id
     AND account_status = 'PENDING'
     AND is_deleted = FALSE
     AND EXISTS (SELECT 1 FROM recruiters WHERE recruiter_id = users.user_id);
   SELECT ROW_COUNT() AS rows_changed;      -- must be 1; Workbench's Action Output says "1 row(s) affected"
   ```

   If it is `0`, nothing was approved: the id is not a `PENDING`, non-deleted recruiter. A student, manager, `ACTIVE`,
   `SUSPENDED` or deleted user is never changed by this statement (tested). Do not edit the `WHERE` clause to force it.
4. Verify: `account_status = ACTIVE` and `phone_verified = 0` (approval does not verify a phone and does not assign a
   manager):

   ```sql
   SELECT u.user_id, u.account_status, u.email_verified, u.phone_verified, u.is_deleted, r.manager_id
   FROM users u JOIN recruiters r ON r.recruiter_id = u.user_id WHERE u.user_id = @recruiter_id;
   ```

Workbench's Safe Updates mode accepts the `UPDATE` (the primary key is in the `WHERE`); leave it on. Nothing changes in
Cognito: on the recruiter's next `GET /api/v1/recruiters/auth/me` the lookup returns `RECRUITER_DASHBOARD`. To suspend a
recruiter later, set `account_status = 'SUSPENDED'` for that one `user_id` the same way (the lookup then returns
`ACCOUNT_SUSPENDED`). V1 has no audit table: keep your own record of who approved whom and when.

Do not run the approval against real user records as part of development or testing; the automated tests only use
throwaway schemas.

### Recruiter authorization on the existing APIs

A valid Cognito session is not authorization. The four recruiter-facing routes (`POST /api/v1/recruiter/applications`,
`GET /api/v1/recruiter/students`, `POST /api/v1/documents/upload-url`, `POST /api/v1/documents/register`; note the singular
`recruiter` and the different Lambdas) now use the same Cognito access token and the same account rule as
`GET /api/v1/recruiters/auth/me`. History: on 2026-11-12 their SQL gained `account_status = 'ACTIVE'`; on 2026-11-16 the
dev header was replaced by token authentication.

| Piece | Where | What it does |
|---|---|---|
| Token verification | `common`: `JwtAccessTokenVerifier` (Registration's `CognitoAccessTokenVerifier` is a thin adapter around it that keeps this module's error envelope) | RS256 by the pool's JWKS, exact issuer / `client_id`, `token_use=access`, lifetime, `openid`; also runs under `sam local` |
| Account lookup | `common`: `AccountProfileRepository` (the one query behind `findProfileByCognitoSub`, used by all four sign-in/lookup paths) | `cognito_sub` → `user_identities` ⋈ `users` ⟕ `students`/`recruiters`/`managers` |
| Decision | `common`: `RecruiterAuthenticator` (mirrors `RecruiterAccountRules`; `RecruiterAuthParityTest` runs all 48 account states through both) | 401 bad/no token; 403 unless an undeleted, `ACTIVE`, Google `recruiters` row |
| Handlers | `RecruiterLambdaHandler`, `DocumentLambdaHandler` | use only `Principal.recruiterId()`; the services keep their assignment and ownership checks |
| Dev header | `RecruiterAuthenticator` + `DevAuth` | only with no `Authorization` header, `ALLOW_DEV_RECRUITER_HEADER=true` **and** `AWS_SAM_LOCAL`; a bad token is 401, never retried as the header; ignored in a deployed stack |
| Template | `template.yaml` | `CognitoAccessToken` authorizer + `openid` scope on the four routes; `COGNITO_*` env; `X-Dev-Recruiter-Id` removed from CORS |

The seed recruiter `1000000002` in `hiremebot_db_v1` is `PENDING`, so it is refused (`403`, and on the header path
`Recruiter does not exist or is not active`) until it is approved with the script above. Not changed: the student dashboard
route (`GET /api/v1/student/applications`) still uses `X-Dev-Student-Id` and has no authorizer.

## Configuration

| Variable | Required | Value | Notes |
|---|---|---|---|
| `COGNITO_REGION` | yes | `us-east-2` | must match the pool prefix |
| `COGNITO_USER_POOL_ID` | yes | `us-east-2_F582IgoSY` | |
| `COGNITO_CLIENT_ID` | yes | `5rgjoisv64qhe70oulfla17id9` | |
| `COGNITO_DOMAIN` | yes | `us-east-2f582igosy.auth.us-east-2.amazoncognito.com` | used for `/oauth2/userInfo` |
| `TERMS_VERSION` | yes | the Terms version the UI shows | ≤ 30 chars `[A-Za-z0-9._-]`; local `env.json` uses the placeholder `local-dev` |
| `PHONE_VERIFICATION_REQUIRED` | V1: `false` | `false` → `ACTIVE`/`DASHBOARD`; `true` → `PENDING`/`ACCOUNT_PENDING` | the template default is `false`; if the variable is missing the code fails safe to `true` |
| `DB_SECRET_ARN` | deployed | Secrets Manager secret (`username`, `password`, optional `host`/`port`/`dbname`) | empty locally |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_MODE` | local | | `DB_USERNAME`/`DB_PASSWORD` only when `DB_SECRET_ARN` is empty |

The same `RegistrationConfig` and `AccountRules` drive both routes, so the policy cannot differ between them.

## Build and test

```bash
export JAVA_HOME="/c/Program Files/Amazon Corretto/jdk21.0.12_9"
export MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"   # only behind the TLS-inspecting proxy

mvn -pl Registration -am test          # unit tests; DB integration tests are skipped

# + MySQL integration and local end-to-end tests (throwaway schemas hmb_reg_it_*, never hiremebot_db_v1)
export RUN_DB_TESTS=true DB_HOST=127.0.0.1 DB_PORT=3306 DB_USERNAME=root DB_SSL_MODE=DISABLED
read -rs DB_PASSWORD && export DB_PASSWORD
mvn -pl Registration -am test
```

| Test class | What it proves |
|---|---|
| `AccountRulesTest` | the decision table and precedence |
| `AccountLookupServiceTest`, `AccountLookupHandlerTest` | `/auth/me`: DASHBOARD / SIGNUP_FORM / ACCOUNT_PENDING shapes; every restricted code; all invalid tokens are 401 with no lookup; query parameters ignored; 405/404 |
| `AccountLookupMySqlIntegrationTest` | real MySQL: new → SIGNUP_FORM with zero rows → register → DASHBOARD on repeated lookups with nothing written; pending/suspended/deleted; recruiter and manager identities → ACCOUNT_TYPE_MISMATCH; email conflict |
| `FullNameConstraintMySqlIntegrationTest` | schema **with** the name index: NAME_ALREADY_REGISTERED with full rollback and the other row untouched; case/accent variants; concurrent same-name race → one account; real MySQL 1062 messages classified via `information_schema` |
| `UnifiedSignInEndToEndLocalIntegrationTest` | scenarios A-D through the handler: real JWT verification (JWKS over HTTP), real UserInfo and AdminGetUser clients against a fake Cognito, real MySQL, V1 policy |
| `UniqueKeyCatalogTest`, `RegistrationConflictClassificationTest` | key parsing (incl. spoofing attempts) and conflict classification |
| `RecruiterAccountRulesTest`, `RecruiterAccountLookupServiceTest`, `RecruiterRegistrationServiceTest` | recruiter decision table and precedence; lookup shapes; `PENDING` whatever the student phone policy; rollback when the `users`, `recruiters` or identity insert fails; conflict mapping; recruiter wording |
| `RecruiterHandlerTest` | both routes through the real JWT verifier: response contracts, every invalid token is 401 with nothing called, 415/413/400, forged status/manager/email/OTP fields ignored, 405/404, student routes unaffected |
| `RecruiterMySqlIntegrationTest` | real MySQL: exactly `users`+`recruiters`+`user_identities` with one id, `manager_id` NULL, `PENDING`, terms recorded; lookups write nothing; student/manager/role-less identities → `ACCOUNT_TYPE_MISMATCH` in both directions; email conflicts; suspended/deleted; rollback at each insert; races (same identity, same email, student-vs-recruiter, burst of 8) → one account |
| `RecruiterFullNameConstraintMySqlIntegrationTest` | schema **with** the name index across roles: `NAME_ALREADY_REGISTERED` with full rollback; recruiter-vs-student, case/accent variants, same-name race |
| `RecruiterManualApprovalMySqlIntegrationTest` | executes the operator script's own statements: approves exactly one pending recruiter (`ROW_COUNT()` = 1), never a student, manager, active, suspended, deleted or unknown id; the unedited script changes nothing; the next lookup returns `RECRUITER_DASHBOARD`; `phone_verified` stays 0 |
| `RecruiterEndToEndLocalIntegrationTest` | scenarios A-E through the handler: real JWT (JWKS over HTTP), real UserInfo and AdminGetUser clients against a fake Cognito, real MySQL, the approval script; native non-Google user, unverified email, student identity |
| `RecruiterDatabaseOutageTest` | a real connection failure → sanitized 503 with a request id on both recruiter routes |
| pre-existing registration tests | validation, transaction, rollback, concurrency, token security, outage (all 196 unchanged) |

**Other modules' database tests.** `RecruiterLambda`, `DocumentManagementLambda` and `StudentLambda` have `RUN_DB_TESTS`
integration tests that write and delete users in the database named by `DB_NAME`. Now that `hiremebot_db_v1` holds real
rows, run them against a throwaway copy of the full V1 schema instead (load the owner's `hiremebot_db_V1.sql` under a
scratch name, without its own `CREATE DATABASE`/`USE` lines, and drop it afterwards):

```bash
mysql -uroot -p -e "CREATE DATABASE hmb_full_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
grep -v -i -E "^(CREATE DATABASE|USE) " hiremebot_db_V1.sql | mysql -uroot -p hmb_full_it
export RUN_DB_TESTS=true DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=hmb_full_it DB_USERNAME=root DB_SSL_MODE=DISABLED
read -rs DB_PASSWORD && export DB_PASSWORD
mvn -pl RecruiterLambda,DocumentManagementLambda,StudentLambda -am test
mysql -uroot -p -e "DROP DATABASE hmb_full_it"
```

## Local API with SAM

Requires Docker Desktop running. `env.json` (git-ignored) needs a `RegistrationFunction` block; see
`env.json.example`. The local one was added on 2026-11-07, reusing the local DB settings of `StudentFunction`.

```bash
mvn -pl common -am install -DskipTests     # sam build resolves the parent POM and common from ~/.m2
sam validate --lint
sam build                                  # build ALL functions (see the note below)
sam local start-api --env-vars env.json    # http://127.0.0.1:3000
```

Local URLs:

- `GET  http://127.0.0.1:3000/api/v1/auth/me`
- `POST http://127.0.0.1:3000/api/v1/students/register`
- `GET  http://127.0.0.1:3000/api/v1/recruiters/auth/me`
- `POST http://127.0.0.1:3000/api/v1/recruiters/register`

Things to know about `sam local`:

- It does **not** run the JWT authorizer (it logs `Authorizer 'CognitoAccessToken' … was not found, skipping`).
  The Lambda still verifies every token, so a real Cognito access token is required, and invalid or missing tokens
  get 401.
- Run the full `sam build` before `start-api`. A single-function build (`sam build RegistrationFunction`) rewrites
  `.aws-sam/build/template.yaml`, and the other functions are then mounted from their source folders
  (`ClassNotFoundException`).
- The CORS preflight from `http://localhost:5173` is answered locally with the same settings as deployed.
- Containers reach the host MySQL as `host.docker.internal:3306`, and reach Cognito over HTTPS for JWKS, UserInfo and
  AdminGetUser. AdminGetUser uses the AWS credentials SAM passes into the container, which need
  `cognito-idp:AdminGetUser` on the pool.

### Troubleshooting: every call is `503 SERVICE_UNAVAILABLE` (SQLState `08001`)

Symptom in the `sam local` log: `Database unavailable: SQLTransientConnectionException (SQLState=08001)` after about
5 s on every call, while MySQL is running and `host.docker.internal:3306` is reachable. A client that retries 503s
(as the contract suggests) then repeats the request every few seconds, which looks like a loop but is not one.

Cause: the local MySQL 8+ account `root` uses `caching_sha2_password`. After MySQL restarts (a reboot, `FLUSH
PRIVILEGES`, `ALTER USER`) its login cache is empty, and the first login over an **unencrypted** connection is refused:
the driver reports `Public Key Retrieval is not allowed`. Any TLS login (for example `mysql.exe`) refills the cache,
so the problem comes and goes, and is only visible after a restart. The log line now includes this root cause.

Fix: encrypt the local connection. Use `"DB_SSL_MODE": "REQUIRED"` in the `RegistrationFunction` block of `env.json`
(`env.json.example` has it). `REQUIRED` encrypts without verifying the server certificate, which is fine for a local
MySQL, and is safer than the alternative `allowPublicKeyRetrieval=true`. Then restart `sam local start-api`
(environment variables are read when it starts). Deployed stacks keep the default `VERIFY_IDENTITY`.

The other function blocks in `env.json` that still say `DISABLED` have the same latent problem after a MySQL restart.

## Testing with Postman

### Recruiter flow

Import [`postman/RecruiterAuth.postman_collection.json`](postman/RecruiterAuth.postman_collection.json) and create an
environment with `BASE_URL` (`http://127.0.0.1:3000` for `sam local`) and the **secret** variable `COGNITO_ACCESS_TOKEN`
(current value only; an access token from a Google account you may test with, taken the same way as below; never commit
or share it). Use a Google account that is **not** already a student (the two existing ones are), otherwise every call
answers `ACCOUNT_TYPE_MISMATCH`. Run the folders in order; between them you do the MySQL steps listed in the collection:

1. lookup → `RECRUITER_SIGNUP_FORM`; register → `201` `PENDING` / `APPROVAL_PENDING`; lookup → `APPROVAL_PENDING`;
   register again → `409 ACCOUNT_ALREADY_REGISTERED`.
2. **Manual step:** approve that recruiter with [`db/recruiter-approval.sql`](db/recruiter-approval.sql) (see
   [Approving a recruiter](#approving-a-recruiter-manual-mysql-step)); expect `ROW_COUNT()` = 1.
3. lookup → `RECRUITER_DASHBOARD`.
4. **Manual step:** `UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = <that id>;` then lookup → `403
   ACCOUNT_SUSPENDED`; set it back to `'ACTIVE'` afterwards.
5. The negative requests: a student's token → `ACCOUNT_TYPE_MISMATCH`, an invalid token and no token → `401`, an account with
   an unverified email → `EMAIL_NOT_VERIFIED` (set the optional `COGNITO_ACCESS_TOKEN_STUDENT` /
   `COGNITO_ACCESS_TOKEN_UNVERIFIED` variables for those two; the requests are skipped by their tests when unset).

Verify rows with the returned id:

```sql
SELECT * FROM users WHERE user_id = ?;        -- email from Google, email_verified=1, phone_verified=0, PENDING (then ACTIVE)
SELECT * FROM recruiters WHERE recruiter_id = ?;    -- manager_id NULL
SELECT * FROM user_identities WHERE user_id = ?;    -- provider='GOOGLE', cognito_sub = token sub
```

### Student flow

Import [`postman/StudentAuth.postman_collection.json`](postman/StudentAuth.postman_collection.json). Then:

1. Create an environment with `baseUrl` = `http://127.0.0.1:3000`, and `cognitoAccessToken` as a **secret** variable
   set in the *current value* only.
2. Sign in to the frontend dev build with **Continue with Google**, using an account you are authorized to test with.
   Run `(await fetchAuthSession()).tokens.accessToken.toString()` in the browser console and paste the result into
   `cognitoAccessToken`. Never put the token in tickets, screenshots or docs. It expires after 60 minutes.
3. Run the requests in order:
   1. lookup → `SIGNUP_FORM` (new user)
   2. register → `201` with `nextStep: DASHBOARD`
   3. lookup again → `DASHBOARD`
   4. register again → `409`

   The remaining requests check `401`, `400` and `415`.
4. Check the rows with the returned id:

   ```sql
   SELECT * FROM users WHERE user_id = ?;            -- email from Google, email_verified=1, phone_verified=0, ACTIVE
   SELECT * FROM students WHERE student_id = ?;
   SELECT * FROM user_identities WHERE user_id = ?;  -- provider='GOOGLE', cognito_sub = token sub
   ```

5. Test accounts are real rows; remove them from a non-production database when you are done.

## Deployment requirements (not deployed)

Nothing has been deployed. Account 716397954339 / us-east-2 had no stack, Lambda or API when last checked. A later
`sam deploy` will create:

- **Lambda `RegistrationFunction`:** java21, 1024 MB, 20 s, log group with 30-day retention. IAM:
  - `cognito-idp:AdminGetUser` on the pool;
  - `secretsmanager:GetSecretValue` on `DatabaseSecretArn`;
  - `kms:Decrypt` if the secret uses a customer-managed key.
- **HTTP API routes:** `GET /api/v1/auth/me`, `POST /api/v1/students/register`, `GET /api/v1/recruiters/auth/me` and
  `POST /api/v1/recruiters/register`, all with the `CognitoAccessToken` JWT authorizer (issuer
  `https://cognito-idp.us-east-2.amazonaws.com/us-east-2_F582IgoSY`, audience `5rgjoisv64qhe70oulfla17id9`) and
  scope `openid`. Existing routes are unchanged (there is no default authorizer).
- **Also:** CORS for `PortalOrigin`, and access logs in `HttpApiAccessLogs`.
- **Parameters:**
  - `TermsVersion` (required; the real version);
  - `PhoneVerificationRequired` (default `false`);
  - `DatabaseSecretArn` (required for deployed stacks);
  - `PortalOrigin` (the real frontend origin).
- **Network:** if MySQL is private, put the function in a VPC with a NAT route. The Cognito hosted domain
  (`/oauth2/userInfo`) and the JWKS URL are public endpoints. `cognito-idp` and `secretsmanager` can use NAT or
  interface endpoints.
- **Cognito:** add the production callback and logout URLs; currently only `http://localhost:5173`.

```bash
mvn -pl common -am install -DskipTests && sam build
sam deploy --parameter-overrides TermsVersion=<version> DatabaseSecretArn=<arn> PortalOrigin=<origin>
# outputs: AccountLookupUrl, StudentRegistrationUrl, RecruiterAccountLookupUrl, RecruiterRegistrationUrl
```

## Outstanding items

1. **Full-name index:** the owner wants `UNIQUE (first_name, last_name)`, but the live local database does not have
   it (see above). Decide whether to add it there and in other environments.
2. **Terms version:** the real value is needed for deployment; local `env.json` uses `local-dev`.
3. **Live end-to-end:** the happy path with a real Google-issued token has not been run (no token available to the
   implementer). Use the Postman steps above.
4. **Student dashboard authorization:** the StudentLambda changes listed in the audit are needed before the dashboard
   API can be exposed.
5. **Revocation:** the known-subject `/auth/me` path does not check Cognito token revocation (bounded by the 60-minute
   access-token lifetime). Calling UserInfo there would add it at the cost of latency.
6. **Future work:** real SMS OTP (then revisit `PHONE_VERIFICATION_REQUIRED`), deleted-account recovery, and explicit,
   verified account linking.
7. **Recruiter approval has no audit trail** (by design for V1: no new table). The operator keeps their own record; an
   optional `approved_by`/`approved_at` or an approvals table can be added later.
8. **Existing recruiter and document APIs still use the dev header** and cannot be called with the Google session; they
   now require an `ACTIVE` recruiter. The seed recruiter `1000000002` is `PENDING`, so it is denied by them until approved.
   Migrating them to the Cognito token check (and giving `RecruiterLambda` the `AWS_SAM_LOCAL` gate) is a separate task.
9. **Live recruiter happy path** with a real Google token has not been run (no token available to the implementer, and
   both Google accounts already in the database are students). Use the Postman steps with a second Google account.
10. **One name across all roles:** if the owner's `UNIQUE (first_name, last_name)` index is added to the database, a
    recruiter cannot share a name with a student either (`NAME_ALREADY_REGISTERED`); it is still absent from the live
    local database.
