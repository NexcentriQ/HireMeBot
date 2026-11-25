# Student sign-in and registration: frontend integration contract

One button, **Continue with Google**, serves new and returning students. After Google sign-in the frontend asks the
backend who the user is (`GET /api/v1/auth/me`) and navigates on the `nextStep` it returns. New students fill in the
signup form and are registered with `POST /api/v1/students/register`.

> **Status (2026-11-07):** implemented and tested locally (`sam local`), **not deployed**. Cognito already maps
> Google's `email_verified`. The base URL for deployed environments will be the stack outputs `AccountLookupUrl`
> and `StudentRegistrationUrl`.

---

## 1. Flow

```text
Continue with Google ─► Cognito Hosted UI ─► Google ─► /auth/callback (Amplify session ready)
        │
        ▼
GET /api/v1/auth/me   (Authorization: Bearer <Cognito ACCESS token>)
        │
        ├─ 200 nextStep = DASHBOARD        ─► Student Dashboard
        ├─ 200 nextStep = ACCOUNT_PENDING  ─► "Account pending" screen
        ├─ 200 nextStep = SIGNUP_FORM      ─► Signup form (first name, last name, phone, Terms)
        │                                     ─► mock OTP screen (development only, verifies nothing)
        │                                     ─► POST /api/v1/students/register (same session)
        │                                           └─ 201 data.nextStep = DASHBOARD ─► Student Dashboard
        └─ 401 / 403 / 409 / 503           ─► see the error tables below (never the signup form)
```

The backend never creates an account during `GET /auth/me`. A user that Cognito created at Google sign-in is
**not** a HireMeBot student until registration succeeds.

## 2. Base URLs and headers

| Environment | Base URL |
|---|---|
| Local (`sam local start-api --env-vars env.json`) | `http://127.0.0.1:3000` |
| Deployed | not deployed yet: stack outputs `AccountLookupUrl` / `StudentRegistrationUrl` |

| Endpoint | Full local URL |
|---|---|
| Account lookup | `GET http://127.0.0.1:3000/api/v1/auth/me` |
| Registration | `POST http://127.0.0.1:3000/api/v1/students/register` |

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

- Call `fetchAuthSession()` right before **each** request; do not cache the token string yourself. Access tokens
  live 60 minutes, and Amplify refreshes them with the refresh token.
- Keep requesting the `openid` and `email` scopes (the app client allows exactly these); `openid` is required.
- Never send the email, Cognito `sub`, user id or a "verified" flag: the backend ignores them. Identity comes only
  from the token.

**CORS (local React dev):** the API allows the single origin `http://localhost:5173` (deployment parameter
`PortalOrigin`) with the methods `GET`, `POST`, `OPTIONS` and the headers `Authorization` and `Content-Type`.
`sam local` answers the preflight the same way. Another dev origin or port must be added on the backend.

## 3. `GET /api/v1/auth/me`

```http
GET /api/v1/auth/me
Authorization: Bearer <Cognito access token>
```

No body and no query parameters (any are ignored). Responses are never cached (`Cache-Control: no-store`).

### 200: registered, active student

```json
{
  "success": true,
  "registered": true,
  "nextStep": "DASHBOARD",
  "user": {
    "userId": 1000000000,
    "studentId": 1000000000,
    "firstName": "Alex",
    "lastName": "Smith",
    "email": "alex@gmail.com",
    "phoneNumber": "+17135550101",
    "emailVerified": true,
    "phoneVerified": false,
    "accountStatus": "ACTIVE",
    "accountType": "STUDENT"
  }
}
```

### 200: registered, pending account

The same `user` object, with `"accountStatus": "PENDING"`:

```json
{ "success": true, "registered": true, "nextStep": "ACCOUNT_PENDING", "user": { "userId": 1000000000, "accountStatus": "PENDING", "...": "..." } }
```

With the V1 policy (`PHONE_VERIFICATION_REQUIRED=false`) new registrations are `ACTIVE`. `ACCOUNT_PENDING` only
appears if an account was set to pending or the policy changes.

### 200: authenticated with Google, not registered yet

```json
{
  "success": true,
  "registered": false,
  "nextStep": "SIGNUP_FORM",
  "message": "Complete your HireMeBot student registration."
}
```

There is no `user` object. Keep the Cognito session and show the signup form. Do **not** send the user back to
Google. Before answering `SIGNUP_FORM` the backend already checked that the Google email is verified, that it is a
Google sign-in, and that the email is not used by another account. So the form will not fail later on an email
conflict.

### Errors (`success: false`)

| HTTP | `code` | Meaning | Frontend action |
|---|---|---|---|
| 401 | `UNAUTHORIZED` | Missing, invalid, expired or revoked token | Sign out locally, show Continue with Google |
| 403 | `ACCOUNT_SUSPENDED` | "Your account is currently suspended." | Restricted screen; no signup |
| 403 | `ACCOUNT_DELETED` | "This account is unavailable." | Restricted screen; no signup |
| 403 | `ACCOUNT_TYPE_MISMATCH` | The Google identity belongs to a recruiter/manager account | Tell the user this is not a student account; no signup |
| 403 | `GOOGLE_ACCOUNT_REQUIRED` | The session is not a Google sign-in (e.g. a username/password Cognito user) | Sign out, ask for Continue with Google |
| 403 | `EMAIL_NOT_VERIFIED` | Google/Cognito did not provide a verified email | Explain; offer another Google account |
| 409 | `ACCOUNT_IDENTITY_CONFLICT` | "An existing account requires a different sign-in or account recovery method." Never auto-linked | Recovery/contact message; no signup |
| 500 | `INTERNAL_ERROR` | Unexpected | Generic error with `requestId` |
| 503 | `SERVICE_UNAVAILABLE` | Database or Cognito temporarily unavailable | Retry with backoff (1 s, 2 s, 4 s), then an error |

## 4. `POST /api/v1/students/register`

Call it **only** after `GET /auth/me` returned `SIGNUP_FORM`, the form is complete and the mock OTP screen is done.

```http
POST /api/v1/students/register
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

Anything else in the body (email, `phoneVerified`, OTP values) is ignored. The phone is stored as **not verified**.
The Terms version and time are recorded by the server.

### 201: registered

```json
{
  "success": true,
  "code": "STUDENT_REGISTERED",
  "message": "Student registered successfully",
  "data": {
    "userId": 1000000000,
    "studentId": 1000000000,
    "firstName": "Alex",
    "lastName": "Smith",
    "email": "alex@gmail.com",
    "phoneNumber": "+17135550101",
    "emailVerified": true,
    "phoneVerified": false,
    "accountStatus": "ACTIVE",
    "accountType": "STUDENT",
    "nextStep": "DASHBOARD"
  }
}
```

Navigate on `data.nextStep` (`DASHBOARD`, or `ACCOUNT_PENDING` if the policy ever requires phone verification). Any
later `GET /auth/me` returns the same `nextStep`.

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
| 400 | `VALIDATION_FAILED` | Field invalid (see `fieldErrors[].field`) | Show messages on the fields |
| 400 | `INVALID_REQUEST` | Body is not one JSON object | Client bug |
| 401 | `UNAUTHORIZED` | Token missing/expired/invalid | See [session expiry](#7-session-expiry-during-signup) |
| 403 | `EMAIL_NOT_VERIFIED`, `GOOGLE_ACCOUNT_REQUIRED` | As for `/auth/me` | As for `/auth/me` |
| 403 | `ACCOUNT_SUSPENDED`, `ACCOUNT_DELETED`, `ACCOUNT_TYPE_MISMATCH` | The identity already has a restricted or non-student account | Restricted screen |
| 409 | `ACCOUNT_ALREADY_REGISTERED` | This Google account is already registered (e.g. double submit, or a retry after a lost `201`) | Call `GET /auth/me` and follow its `nextStep` |
| 409 | `ACCOUNT_IDENTITY_CONFLICT` | The email belongs to another identity | Recovery/contact message |
| 409 | `NAME_ALREADY_REGISTERED` | "An account with this name already exists. Please contact support." (the database keeps first+last name unique) | Show the message; do not retry automatically |
| 413 / 415 | `PAYLOAD_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` | Client bug | |
| 500 / 503 | `INTERNAL_ERROR` / `SERVICE_UNAVAILABLE` | Server side | Show `requestId`; retry 503 with backoff |

## 5. API Gateway responses (deployed only)

A token that fails API Gateway's own check never reaches the backend and has API Gateway's body, not the envelope
above:

| HTTP | Body | Meaning |
|---|---|---|
| 401 | `{"message":"Unauthorized"}` | Missing, malformed, expired or foreign token |
| 403 | `{"message":"Forbidden"}` | Token lacks the `openid` scope (e.g. an ID token was sent) |

Treat any 401/403 **without** a `code` field like `UNAUTHORIZED`.

## 6. Navigation and client state

| Result | Go to | Signup draft |
|---|---|---|
| `DASHBOARD` (lookup or registration) | Student Dashboard | clear |
| `ACCOUNT_PENDING` | Pending screen | clear |
| `SIGNUP_FORM` | Signup form | keep while signing up |
| `ACCOUNT_ALREADY_REGISTERED` (on register) | re-run `GET /auth/me`, follow it | clear |
| 403 restricted / 409 conflict | Restricted or recovery screen (never the form) | clear |
| `UNAUTHORIZED` | Continue with Google | keep (see below) |
| Sign-out | Landing page | clear |

- **Run `GET /auth/me` on every app start and after every Google sign-in.** Do not trust a stored "isAuthenticated"
  or "registered" flag; the backend checks the account status each time (suspension takes effect immediately).
- **Draft storage:** keep the signup draft (names, phone, Terms checkbox) in `sessionStorage`, not `localStorage`,
  so it survives a Hosted UI redirect but not the tab. Clear it on success, conflict, restriction or sign-out. Never
  store tokens yourself; Amplify manages them.
- **Avoid double registration:** disable the submit button while `POST` is in flight and send the request once.
  A duplicate is still harmless: it creates nothing and returns `409 ACCOUNT_ALREADY_REGISTERED`.
- The user's Gmail is never typed in; it comes from Cognito. Show `user.email` / `data.email` read-only if needed.

## 7. Session expiry during signup

1. `fetchAuthSession()` normally refreshes an expired access token silently, so filling in a long form is fine.
2. If refresh is impossible (refresh token expired or revoked), `fetchAuthSession()` has no access token, or the API
   answers `401`. Keep the draft in `sessionStorage` and run Continue with Google again.
3. After the callback, call `GET /auth/me` again:
   - `SIGNUP_FORM`: restore the draft and let the user submit.
   - `DASHBOARD`: the account was created meanwhile; clear the draft.
4. If the user comes back with a **different** Google account, `GET /auth/me` answers for that account. Discard the
   draft if the email shown differs from what the user expects.

## 8. Changes since the 2026-11-06 registration contract

- New endpoint `GET /api/v1/auth/me`.
- Registration response: `data.nextAction` (`PHONE_VERIFICATION` / `STUDENT_DASHBOARD`) was replaced by
  `data.nextStep` (`DASHBOARD` / `ACCOUNT_PENDING`), the same vocabulary as `/auth/me`.
- V1 policy is now `PHONE_VERIFICATION_REQUIRED=false`, so new students are `ACTIVE` and get `nextStep: DASHBOARD`.
- `ACCOUNT_DELETED` is now **403** (was 409) on both endpoints, with the message "This account is unavailable.".
- New codes: `ACCOUNT_TYPE_MISMATCH` (403), `NAME_ALREADY_REGISTERED` (409).
- Updated messages for `ACCOUNT_SUSPENDED`, `ACCOUNT_IDENTITY_CONFLICT` and `GOOGLE_ACCOUNT_REQUIRED`.

## 9. Error field: `code` vs `error`

These two endpoints use `{ "success": false, "code": ..., "message": ..., "requestId": ... }`. The older
recruiter, student-dashboard and document APIs ([API.md](API.md)) use `{ "success": false, "error": ..., ... }`.
Branch on `code` here and on `error` there. Messages are safe to show; they never contain SQL, tokens or stack
traces.
