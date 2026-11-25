# Recruiter sign-in and registration: frontend integration contract

One button, **Continue with Google**, serves new and returning recruiters. After Google sign-in the portal asks the
backend who the user is (`GET /api/v1/recruiters/auth/me`) and navigates on the `nextStep` it returns. New recruiters
fill in the registration form and are registered with `POST /api/v1/recruiters/register`.

**Anyone with a verified Google account can register as a recruiter, but a new recruiter is never active.** The account
is created `PENDING` and the portal shows a "waiting for approval" screen until the owner approves the account manually
in MySQL. There is no approval API and nothing for the frontend to call to approve.

> **Status (2026-11-16):** implemented and tested locally (`sam local`), **not deployed**. The recruiter workspace APIs now
> accept the same Cognito access token (section 9). Cognito already maps Google's
> `email_verified`. The base URL for deployed environments will be the stack outputs `RecruiterAccountLookupUrl` and
> `RecruiterRegistrationUrl`. This is separate from the student flow ([STUDENT_AUTH_API.md](STUDENT_AUTH_API.md)); the
> two use the same Cognito pool and the same Lambda but different routes and different `nextStep` values.

---

## 1. Flow

```text
Continue with Google ─► Cognito Hosted UI ─► Google ─► /auth/callback (Amplify session ready)
        │
        ▼
GET /api/v1/recruiters/auth/me   (Authorization: Bearer <Cognito ACCESS token>)
        │
        ├─ 200 nextStep = RECRUITER_DASHBOARD    ─► Recruiter Dashboard
        ├─ 200 nextStep = APPROVAL_PENDING       ─► "Waiting for approval" screen
        ├─ 200 nextStep = RECRUITER_SIGNUP_FORM  ─► Registration form (first name, last name, phone, Terms)
        │                                           ─► POST /api/v1/recruiters/register (same session)
        │                                                 └─ 201 data.nextStep = APPROVAL_PENDING ─► "Waiting for approval" screen
        └─ 401 / 403 / 409 / 503                 ─► see the error tables below (never the registration form)

Later, after the owner approves the account in MySQL (no Cognito change, nothing for the portal to do):
Continue with Google ─► GET /api/v1/recruiters/auth/me ─► RECRUITER_DASHBOARD
```

The backend never creates an account during `GET /recruiters/auth/me`. A user that Cognito created at Google sign-in is
**not** a HireMeBot recruiter until registration succeeds, and a registered recruiter is **not** active until approved.

## 2. Base URLs and headers

| Environment | Base URL |
|---|---|
| Local (`sam local start-api --env-vars env.json`) | `http://127.0.0.1:3000` |
| Deployed | not deployed yet: stack outputs `RecruiterAccountLookupUrl` / `RecruiterRegistrationUrl` |

| Endpoint | Full local URL |
|---|---|
| Account lookup | `GET http://127.0.0.1:3000/api/v1/recruiters/auth/me` |
| Registration | `POST http://127.0.0.1:3000/api/v1/recruiters/register` |

Note the **plural** `recruiters` in these two routes. The recruiter dashboard APIs that already exist
(`/api/v1/recruiter/applications`, `/api/v1/recruiter/students`, `/api/v1/documents/*`) use the singular `recruiter` and are
a different, development-only API (see [section 9](#9-backend-authorization-what-an-approved-recruiter-is)).

Every call needs the Cognito **access** token, not the ID token and not a Google token:

```js
import { fetchAuthSession } from 'aws-amplify/auth';

async function authHeaders() {
  const { tokens } = await fetchAuthSession();      // refreshes an expired access token if it can
  const accessToken = tokens?.accessToken?.toString();
  if (!accessToken) throw new Error('NO_SESSION');  // send the user to Continue with Google
  return { Authorization: `Bearer ${accessToken}` };
}
```

- Call `fetchAuthSession()` right before **each** request; do not cache the token string. Access tokens live 60
  minutes and Amplify refreshes them with the refresh token.
- Keep requesting the `openid` and `email` scopes (the app client allows exactly these); `openid` is required.
- Never send the email, Cognito `sub`, user id, manager id, account status or any "verified" flag: the backend ignores
  them. Identity comes only from the token.

**CORS (local React dev):** the API allows the single origin `http://localhost:5173` (deployment parameter
`PortalOrigin`) with the methods `GET`, `POST`, `OPTIONS` and the headers `Authorization` and `Content-Type`. The
Cognito app client lists only `http://localhost:5173/auth/callback` as a callback URL. If the recruiter portal runs on a
different origin or port, both the API CORS setting and the Cognito callback/logout URLs must be extended first.

## 3. `GET /api/v1/recruiters/auth/me`

```http
GET /api/v1/recruiters/auth/me
Authorization: Bearer <Cognito access token>
```

No body and no query parameters (any are ignored). Responses are never cached (`Cache-Control: no-store`).

### 200: approved recruiter

```json
{
  "success": true,
  "registered": true,
  "nextStep": "RECRUITER_DASHBOARD",
  "user": {
    "userId": 1000000001,
    "recruiterId": 1000000001,
    "firstName": "Alex",
    "lastName": "Smith",
    "email": "recruiter@gmail.com",
    "phoneNumber": "+17135550101",
    "emailVerified": true,
    "phoneVerified": false,
    "accountStatus": "ACTIVE",
    "accountType": "RECRUITER"
  }
}
```

Returned only for a Google-linked recruiter whose account is not deleted and is `ACTIVE`.

### 200: registered, waiting for approval

```json
{
  "success": true,
  "registered": true,
  "nextStep": "APPROVAL_PENDING",
  "message": "Your recruiter account is waiting for approval.",
  "user": {
    "userId": 1000000001,
    "recruiterId": 1000000001,
    "accountType": "RECRUITER",
    "accountStatus": "PENDING"
  }
}
```

A pending recruiter gets only these four `user` fields (no name, email or phone). Never send them to the dashboard.

### 200: authenticated with Google, not registered yet

```json
{
  "success": true,
  "registered": false,
  "nextStep": "RECRUITER_SIGNUP_FORM",
  "message": "Complete your HireMeBot recruiter registration."
}
```

There is no `user` object. Keep the Cognito session and show the registration form. Do **not** send the user back to
Google. Before answering `RECRUITER_SIGNUP_FORM` the backend already checked that the Google email is verified, that it
is a Google sign-in, and that the email is not used by another account, so the form will not fail later on an email
conflict.

### Errors (`success: false`)

| HTTP | `code` | Meaning | Frontend action |
|---|---|---|---|
| 401 | `UNAUTHORIZED` | Missing, invalid, expired or revoked token | Sign out locally, show Continue with Google |
| 403 | `ACCOUNT_SUSPENDED` | "Your recruiter account has been suspended." | Restricted screen; no registration form |
| 403 | `ACCOUNT_DELETED` | "This account is unavailable." | Restricted screen; no registration form |
| 403 | `ACCOUNT_TYPE_MISMATCH` | "This Google account is already associated with another HireMeBot account type." (it belongs to a student or manager; accounts are never converted) | Explain and offer another Google account; no registration form |
| 403 | `GOOGLE_ACCOUNT_REQUIRED` | The session is not a Google sign-in (e.g. a username/password Cognito user) | Sign out, ask for Continue with Google |
| 403 | `EMAIL_NOT_VERIFIED` | Google/Cognito did not provide a verified email | Explain; offer another Google account |
| 409 | `ACCOUNT_IDENTITY_CONFLICT` | "This email is already associated with another HireMeBot account." Never auto-linked | Contact/recovery message; no registration form |
| 500 | `INTERNAL_ERROR` | Unexpected | Generic error with `requestId` |
| 503 | `SERVICE_UNAVAILABLE` | Database or Cognito temporarily unavailable | Retry with backoff (1 s, 2 s, 4 s), then an error |

## 4. `POST /api/v1/recruiters/register`

Call it **only** after `GET /recruiters/auth/me` returned `RECRUITER_SIGNUP_FORM` and the form is complete.

```http
POST /api/v1/recruiters/register
Authorization: Bearer <Cognito access token>
Content-Type: application/json

{
  "firstName": "Alex",
  "lastName": "Smith",
  "phoneNumber": "+17135550101",
  "acceptedTerms": true
}
```

| Field | Rules (mirror them in the form) |
|---|---|
| `firstName`, `lastName` | required; trimmed; 1-100 characters; letters of any language, spaces, `'` `’` `-` `.`; must start with a letter |
| `phoneNumber` | required; E.164 with country code, e.g. `+17135550101`; spaces, dashes, dots and parentheses are removed; max 15 digits; US/Canada (`+1`) needs 10 digits with a valid area code |
| `acceptedTerms` | must be the JSON boolean `true` |

These are the same rules as the student form. Anything else in the body (email, user id, manager id, account status,
`phoneVerified`, OTP values) is ignored; sending it does not change what is stored. The phone is stored as **not
verified** (there is no real SMS verification yet); the Terms version and acceptance time are recorded by the server.

### 201: submitted, waiting for approval

```json
{
  "success": true,
  "code": "RECRUITER_REGISTRATION_SUBMITTED",
  "message": "Your recruiter registration is pending approval.",
  "data": {
    "userId": 1000000001,
    "recruiterId": 1000000001,
    "firstName": "Alex",
    "lastName": "Smith",
    "email": "recruiter@gmail.com",
    "phoneNumber": "+17135550101",
    "emailVerified": true,
    "phoneVerified": false,
    "accountStatus": "PENDING",
    "accountType": "RECRUITER",
    "nextStep": "APPROVAL_PENDING"
  }
}
```

`data.nextStep` is always `APPROVAL_PENDING`; a registration never returns a dashboard step. `email` is the verified
Google address, lower-cased.

### Errors

Same envelope as above; `fieldErrors` appears only for `VALIDATION_FAILED`:

```json
{
  "success": false,
  "code": "VALIDATION_FAILED",
  "message": "Please provide valid registration details.",
  "fieldErrors": [{ "field": "phoneNumber", "message": "Enter a valid phone number in international format, e.g. +17135550101." }],
  "requestId": "68d329fa-7c7b-46b3-884b-70b13261ffd2"
}
```

| HTTP | `code` | When | Frontend action |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | A field is invalid (see `fieldErrors[].field`) | Show messages on the fields |
| 400 | `INVALID_REQUEST` | Body is not one JSON object | Client bug |
| 401 | `UNAUTHORIZED` | Token missing/expired/invalid | See [session expiry](#8-session-expiry-during-registration) |
| 403 | `EMAIL_NOT_VERIFIED`, `GOOGLE_ACCOUNT_REQUIRED` | As for `/recruiters/auth/me` | As there |
| 403 | `ACCOUNT_SUSPENDED`, `ACCOUNT_DELETED`, `ACCOUNT_TYPE_MISMATCH` | This Google identity already has a restricted account or a student/manager account | Restricted screen |
| 409 | `ACCOUNT_ALREADY_REGISTERED` | This Google account is already registered as a recruiter (double submit, or a retry after a lost `201`; also while still `PENDING`) | Call `GET /recruiters/auth/me` and follow its `nextStep` |
| 409 | `ACCOUNT_IDENTITY_CONFLICT` | The email belongs to another identity | Contact/recovery message |
| 409 | `NAME_ALREADY_REGISTERED` | "An account with this name already exists. Please contact support." The database keeps first + last name unique across **all** users (students too) | Show the message; do not retry automatically |
| 413 / 415 | `PAYLOAD_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` | Client bug | |
| 500 / 503 | `INTERNAL_ERROR` / `SERVICE_UNAVAILABLE` | Server side | Show `requestId`; retry 503 with backoff |

## 5. API Gateway responses (deployed only)

A token that fails API Gateway's own check never reaches the backend and has API Gateway's body, not the envelope above:

| HTTP | Body | Meaning |
|---|---|---|
| 401 | `{"message":"Unauthorized"}` | Missing, malformed, expired or foreign token |
| 403 | `{"message":"Forbidden"}` | Token lacks the `openid` scope (e.g. an ID token was sent) |

Treat any 401/403 **without** a `code` field like `UNAUTHORIZED`.

## 6. Routes and navigation

Suggested portal routes (names are up to the frontend):

| Route | Shown when |
|---|---|
| `/recruiter/login` | no session; the **Continue with Google** button |
| `/recruiter/register` | `RECRUITER_SIGNUP_FORM` |
| `/recruiter/pending` | `APPROVAL_PENDING` (from the lookup or from the registration response) |
| `/recruiter/dashboard` | `RECRUITER_DASHBOARD` only |
| `/recruiter/restricted` | `ACCOUNT_SUSPENDED`, `ACCOUNT_DELETED`, `ACCOUNT_TYPE_MISMATCH`, `ACCOUNT_IDENTITY_CONFLICT` (message from the response) |

| Result | Go to | Registration draft |
|---|---|---|
| `RECRUITER_DASHBOARD` | Dashboard | clear |
| `APPROVAL_PENDING` (lookup or registration) | Pending screen | clear |
| `RECRUITER_SIGNUP_FORM` | Registration form | keep while registering |
| `ACCOUNT_ALREADY_REGISTERED` (on register) | re-run `GET /recruiters/auth/me`, follow it | clear |
| 403 restricted / 409 conflict | Restricted screen (never the form) | clear |
| `UNAUTHORIZED` | Continue with Google | keep (see section 8) |
| Sign-out | Login page | clear |

- **Run `GET /recruiters/auth/me` on every app start and after every Google sign-in.** Do not trust a stored
  "isAuthenticated", "registered" or "approved" flag: the backend checks the account status each time, so a suspension
  or an approval takes effect on the next lookup.
- **The pending screen:** say that the account is waiting for approval and offer **Check again** (calls the lookup once)
  and **Sign out**. Do not poll in a loop; approval is a manual step by the owner and can take a while. When the owner
  has approved the account, the next lookup returns `RECRUITER_DASHBOARD` (no new sign-in is needed).
- **Draft storage:** keep the registration draft (names, phone, Terms checkbox) in `sessionStorage`, not
  `localStorage`, so it survives the Hosted UI redirect but not the tab. Clear it on success, conflict, restriction or
  sign-out. Never store tokens yourself; Amplify manages them.
- **Avoid double registration:** disable the submit button while the request is in flight and send it once. A duplicate
  is harmless: it creates nothing and returns `409 ACCOUNT_ALREADY_REGISTERED`.
- **Terms and Conditions:** the form needs a required Terms checkbox (send `acceptedTerms: true`). The server stores
  the Terms version it is configured with (`TERMS_VERSION`, currently the same value as for students) and the
  acceptance time; the client never sends a version. Show the recruiter Terms that match the configured version.
- The user's Gmail is never typed in; it comes from Cognito. Show `data.email` read-only if needed.

## 7. One Google account, one role

A Google account that is registered as a **student** gets `ACCOUNT_TYPE_MISMATCH` in the recruiter portal, and a
recruiter's Google account gets the same in the student portal. Accounts are never converted or linked automatically.
A person who needs both roles must use two different Google accounts (and two different names, because first + last
name is unique across all users).

## 8. Session expiry during registration

1. `fetchAuthSession()` normally refreshes an expired access token silently, so filling in a long form is fine.
2. If refresh is impossible (refresh token expired or revoked), `fetchAuthSession()` has no access token or the API
   answers `401`. Keep the draft in `sessionStorage` and run Continue with Google again.
3. After the callback, call `GET /recruiters/auth/me` again:
   - `RECRUITER_SIGNUP_FORM`: restore the draft and let the user submit.
   - `APPROVAL_PENDING`: the registration went through meanwhile (for example a lost `201`); clear the draft.
4. If the user comes back with a **different** Google account, the lookup answers for that account. Discard the draft if
   the email shown differs from what the user expects.

## 9. Backend authorization: what an approved recruiter is

A valid Cognito session is **not** enough to use recruiter features, and the frontend's routing is not a security
control. For protected recruiter endpoints the backend requires **all** of: a valid Cognito access token, an internal user
for that Cognito subject, a matching `recruiters` row, `account_status = 'ACTIVE'`, `is_deleted = false`, and then the
endpoint's own checks (for example the student assignment). A `PENDING` or `SUSPENDED` recruiter is denied everywhere.

The four existing recruiter APIs (`POST /api/v1/recruiter/applications`, `GET /api/v1/recruiter/students`,
`POST /api/v1/documents/upload-url`, `POST /api/v1/documents/register`; note the singular `recruiter`) now use exactly this
rule. **They accept the same Cognito access token** the portal already sends to `GET /api/v1/recruiters/auth/me`:

```http
Authorization: Bearer <Cognito access token>
```

- The portal sends **no recruiter id**. The backend resolves the recruiter from the verified token; a `recruiterId`, user
  id, email or `X-Dev-Recruiter-Id` sent by the client is ignored and can never choose the actor.
- Each call then checks the endpoint's own rules with that id: `GET /api/v1/recruiter/students` returns only the students
  assigned to that recruiter; `POST /api/v1/recruiter/applications` and both document calls refuse a student who is not
  assigned to that recruiter.
- A pending, suspended or deleted recruiter, a student or manager account, a non-Google identity and a Google user who never
  registered all get **`403`**; a missing, invalid, expired, ID or foreign-pool token gets **`401`**; an outage is **`503`**.
  These four routes keep their original error envelope `{ "success": false, "error": "...", "message": "..." }` (not the
  `code`/`requestId` envelope of the sign-in endpoints); branch on the HTTP status and on `error`
  (`UNAUTHORIZED`, `FORBIDDEN`, `SERVICE_UNAVAILABLE`):

  | Status | `error` | `message` |
  |---|---|---|
  | 401 | `UNAUTHORIZED` | `Authentication is required.` |
  | 403 | `FORBIDDEN` | `This account is not registered as a recruiter`, `This account is unavailable`, `This account is not a recruiter account`, `Your recruiter account has been suspended`, `A Google sign-in is required` or `Your recruiter account is waiting for approval` |
  | 503 | `SERVICE_UNAVAILABLE` | `The service is temporarily unavailable. Please try again later.` |

  After a 401, sign the user in again; after a 403 on these routes, call `GET /api/v1/recruiters/auth/me` and follow its
  `nextStep` (it explains pending, suspended and other-account-type cases with the portal's own messages).
- The deployed API Gateway rejects a missing or invalid token with `{"message":"Unauthorized"}` (and a token without the
  `openid` scope, such as an ID token, with `{"message":"Forbidden"}`) before the backend runs; treat a 401/403 without
  an `error` field like `UNAUTHORIZED`.
- Approval takes effect on the very next call (the status is read on every request); no new sign-in is needed.
- The `X-Dev-Recruiter-Id` header is a local-only fallback that works only under `sam local` with no `Authorization`
  header; a deployed stack ignores it. The portal never sends it. Responses of successful calls are unchanged.

## 10. Manual approval (for the operator, not the frontend)

A new recruiter stays `PENDING` until the owner changes `users.account_status` to `ACTIVE` in MySQL. The documented,
tested script is `Registration/db/recruiter-approval.sql` (list pending recruiters, approve one, check that exactly one row
changed, verify). Approval does not verify the phone and does not assign a manager. A suspended recruiter is set back by
changing the status the same way. See [Registration/README.md](../Registration/README.md#approving-a-recruiter-manual-mysql-step).

## 11. Error format

These endpoints use `{ "success": false, "code": ..., "message": ..., "requestId": ... }`, the same as the student
endpoints. The older recruiter, student-dashboard and document APIs ([API.md](API.md)) use `{ "success": false, "error": ... }`.
Branch on `code` here and on `error` there. Messages are safe to show; they never contain SQL, tokens or stack traces.
