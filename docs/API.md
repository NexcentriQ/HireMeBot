# HireMeBot API Reference

Three endpoints, served by two Lambdas:

| Endpoint | Purpose | Lambda |
|---|---|---|
| [`POST /api/v1/recruiter/applications`](#1-post-apiv1recruiterapplications) | A recruiter records a job application they already made for an assigned student | RecruiterLambda |
| [`GET /api/v1/recruiter/students`](#2-get-apiv1recruiterstudents) | A recruiter lists their active assigned students (fills the Apply Job form's student dropdown) | RecruiterLambda |
| [`GET /api/v1/student/applications`](#3-get-apiv1studentapplications) | A student's dashboard: today's recorded applications plus monthly/daily/weekly progress statistics | StudentLambda |

> **Status: development only.** Authentication is not implemented yet. Callers are identified by temporary
> `X-Dev-*` headers that are **not authentication** and are accepted only when the server is started with the
> matching flag. Do not expose these APIs publicly until Cognito/JWT replaces them (see
> [Authentication](#authentication-development-only)).

---

## Conventions

| | |
|---|---|
| Base URL (local, `sam local start-api`) | `http://127.0.0.1:3000` |
| Base URL (deployed) | not deployed yet |
| Request/response format | JSON, UTF-8. Every response from the application has `Content-Type: application/json` |
| Identifiers | `userId`, `studentId`, `applicationId`, object ids are positive 64-bit integers, sent as JSON numbers |
| Timestamps | ISO-8601 with the reporting-zone offset, e.g. `2026-10-29T10:30:00-05:00` (the same instant as `2026-10-29T15:30:00Z`) |
| Calendar days | `YYYY-MM-DD` throughout responses (`date`, `asOfDate`, `month` as `YYYY-MM`, weekday `date`s), all resolved server-side in the reporting zone (`APP_TIMEZONE`, `America/Chicago` by default) — never supplied by the client |
| Header names | case-insensitive |
| Trailing slash | `/api/v1/student/applications/` is the same route as without the slash |

### Success and error shapes

Success bodies always contain `"success": true`. Every error, from either endpoint, has this shape:

```json
{
  "success": false,
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": ["application.jobTitle is required"]
}
```

| Field | Type | Notes |
|---|---|---|
| `success` | boolean | always `false` for errors |
| `error` | string | stable machine-readable code (table below); branch on this, not on `message` |
| `message` | string | human-readable; wording may change |
| `details` | string[] | only present when non-empty (currently only `VALIDATION_ERROR`) |

Error bodies never contain SQL, stack traces, hostnames or credentials; the technical cause is only in the server logs.

| `error` code | HTTP status | Meaning |
|---|---|---|
| `BAD_REQUEST` | 400 | the request is malformed (header, query parameter, or JSON body) |
| `VALIDATION_ERROR` | 400 | the JSON body parsed but one or more fields are invalid; see `details` |
| `FORBIDDEN` | 403 | the caller is not allowed to do this (including a recruiter who does not exist or is deleted), or a referenced object does not exist |
| `NOT_FOUND` | 404 | unknown route, or the student does not exist |
| `METHOD_NOT_ALLOWED` | 405 | the route exists but not for this HTTP method (response includes an `Allow` header) |
| `CONFLICT` | 409 | the application already exists |
| `INTERNAL_ERROR` | 500 | unexpected server error |
| `SERVICE_UNAVAILABLE` | 503 | the database is temporarily unreachable; safe to retry |

### Authentication (development only)

| Endpoint | Header | Server flag that enables it |
|---|---|---|
| POST recruiter applications | `X-Dev-Recruiter-Id: <recruiter user_id>` | `ALLOW_DEV_RECRUITER_HEADER=true` |
| GET recruiter students | `X-Dev-Recruiter-Id: <recruiter user_id>` | `ALLOW_DEV_RECRUITER_HEADER=true` (same flag as the POST) |
| GET student applications | `X-Dev-Student-Id: <student user_id>` | `ALLOW_DEV_STUDENT_HEADER=true` |

The flag defaults to `false`. While it is `false`, the endpoint answers **403** to every request, whatever headers
are sent, so a deployed stack cannot be reached by simply sending a header. When Cognito/JWT is added, these
headers will be removed and the caller will be identified from the validated token instead.

---

## 1. `POST /api/v1/recruiter/applications`

Records a job application that a recruiter has **already** made externally, on behalf of a student assigned to
them. Nothing is submitted to the job site; the details are stored in HireMeBot. The call is not idempotent: send
the same job link twice for the same student and the second call is a `409`.

### Request

**Headers**

| Header | Required | Description |
|---|---|---|
| `Content-Type` | recommended | `application/json`. Not enforced: the body is always parsed as JSON |
| `X-Dev-Recruiter-Id` | yes | positive integer: the recruiter's `user_id` |

**Body**

```json
{
  "studentId": 1000000002,
  "application": {
    "jobTitle": "Senior Java Backend Developer",
    "companyName": "Amazon",
    "jobLocation": "Austin, TX",
    "jobLink": "https://www.amazon.jobs/en/jobs/1234567",
    "jobSource": "Amazon Careers"
  },
  "documents": {
    "resumeObjectId": 1000000003,
    "coverLetterObjectId": 1000000004
  },
  "questions": [
    { "question": "Are you willing to relocate?", "answer": "Yes" },
    { "question": "What is your expected salary?", "answer": "$110,000 annually" }
  ]
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `studentId` | integer | yes | positive; must be assigned to the recruiter in the header |
| `application.jobTitle` | string | yes | not blank, max 255 characters |
| `application.companyName` | string | yes | not blank, max 255 characters |
| `application.jobLocation` | string | yes | not blank, max 255 characters |
| `application.jobLink` | string | yes | absolute `http` or `https` URL with a host, max 1000 characters. Used to detect duplicates: the **exact** (trimmed) string is compared, so a trailing slash or a different query string counts as a different job |
| `application.jobSource` | string | yes | not blank, max 100 characters |
| `documents.resumeObjectId` | integer | yes | positive; must exist in the object store |
| `documents.coverLetterObjectId` | integer | no | positive when present; must exist in the object store |
| `questions` | array | no | omit or send `[]` for none; max 50 entries |
| `questions[].question` | string | yes, per entry | not blank, max 5000 characters |
| `questions[].answer` | string | yes, per entry | not blank, max 5000 characters |

Behavior to know about:

- **Strings are trimmed** before validation and storage.
- **The body is strict.** Unknown fields (at any level), wrong types (`"studentId": "1000000002"` or `1.5`),
  and anything after the JSON document are rejected with `400`. In particular, `recruiterId` in the body is
  rejected: the recruiter comes only from the header.
- `appliedAt` is not accepted; the server records the time of the call (UTC). `isViewed` starts as `false`.
- The resume and cover-letter ids are only checked for existence, not for belonging to the student.
- The application, its document link and its questions are saved in one transaction: either all of it is stored or none.

### Responses

#### `201 Created`

```json
{
  "success": true,
  "message": "Application recorded successfully",
  "applicationId": 1000000005
}
```

| Field | Type | Description |
|---|---|---|
| `success` | boolean | `true` |
| `message` | string | `Application recorded successfully` |
| `applicationId` | integer | id of the new application |

#### Error responses

Checks run in the order below, and the first failure is returned. For example, a missing header is reported
before a malformed body, and a malformed body before field validation.

| # | Status | `error` | `message` | Cause |
|---|---|---|---|---|
| 1 | 404 | `NOT_FOUND` | `Route not found` | path is not `/api/v1/recruiter/applications` |
| 2 | 405 | `METHOD_NOT_ALLOWED` | `Method not allowed; use POST` | method is not `POST`; response has header `Allow: POST` |
| 3 | 403 | `FORBIDDEN` | `Recruiter authentication is not configured` | `ALLOW_DEV_RECRUITER_HEADER` is not `true` on the server |
| 4 | 400 | `BAD_REQUEST` | `Missing required header X-Dev-Recruiter-Id` | header absent or blank |
| 5 | 400 | `BAD_REQUEST` | `X-Dev-Recruiter-Id must be a positive number` | header is not a positive integer (`abc`, `0`, `-5`, `1.5`, or too large for 64 bits) |
| 6 | 400 | `BAD_REQUEST` | `Request body is required` | body missing, blank, or the JSON literal `null` |
| 7 | 400 | `BAD_REQUEST` | `Request body is not valid base64` | body flagged as base64 by the gateway but not decodable |
| 8 | 400 | `BAD_REQUEST` | `Unknown field '<name>' is not allowed` | the body contains a field that is not in the schema, e.g. `recruiterId` (names longer than 50 characters are truncated) |
| 9 | 400 | `BAD_REQUEST` | `Malformed or invalid JSON request body` | invalid JSON, a JSON array/string instead of an object, a wrong value type, or trailing content |
| 10 | 400 | `VALIDATION_ERROR` | `Request validation failed` | one or more field rules failed; **all** problems are listed in `details` (see below) |
| 11 | 403 | `FORBIDDEN` | `Recruiter is not assigned to this student` | no active assignment: the student is not assigned to this recruiter, either user does not exist or is soft-deleted, or the assignment was removed while the request ran |
| 12 | 403 | `FORBIDDEN` | `Resume object does not exist` | `documents.resumeObjectId` not found |
| 13 | 403 | `FORBIDDEN` | `Cover letter object does not exist` | `documents.coverLetterObjectId` was sent but not found |
| 14 | 409 | `CONFLICT` | `An application for this student and job link already exists` | the student already has an application with this `jobLink`. Concurrent identical requests are safe: exactly one gets `201`, the rest get `409` |
| 15 | 503 | `SERVICE_UNAVAILABLE` | `The service is temporarily unavailable. Please try again later.` | database unreachable or connection pool timed out; nothing was stored, so the request can be retried |
| 16 | 500 | `INTERNAL_ERROR` | `An unexpected error occurred.` | any other failure; nothing was stored. Includes a server that is missing its database configuration |

Notes:

- Rows 11 to 13 are all `403`. Use `message` (or treat them as one case) to tell them apart; the `error` code is
  `FORBIDDEN` for all three. A student id that does not exist also returns row 11, never a `404`.
- A transient database deadlock is retried by the server (up to 3 attempts) before it is reported as `500`.

**`VALIDATION_ERROR` details.** The `details` array can contain any of these strings (`i` is the zero-based index
in `questions`):

| Detail |
|---|
| `studentId is required` / `studentId must be a positive number` |
| `application is required` |
| `application.jobTitle is required` / `application.jobTitle must be at most 255 characters` |
| `application.companyName is required` / `... must be at most 255 characters` |
| `application.jobLocation is required` / `... must be at most 255 characters` |
| `application.jobLink is required` / `application.jobLink must be at most 1000 characters` |
| `application.jobLink must be an absolute http or https URL` |
| `application.jobSource is required` / `application.jobSource must be at most 100 characters` |
| `documents is required` |
| `documents.resumeObjectId is required` / `documents.resumeObjectId must be a positive number` |
| `documents.coverLetterObjectId must be a positive number when provided` |
| `questions must contain at most 50 entries` |
| `questions[i] must not be null` |
| `questions[i].question is required` / `questions[i].question must be at most 5000 characters` |
| `questions[i].answer is required` / `questions[i].answer must be at most 5000 characters` |

Example:

```json
{
  "success": false,
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "details": [
    "application.jobTitle is required",
    "application.jobLink must be an absolute http or https URL"
  ]
}
```

### Example

```bash
curl -i -X POST http://127.0.0.1:3000/api/v1/recruiter/applications \
  -H "Content-Type: application/json" \
  -H "X-Dev-Recruiter-Id: 1000000001" \
  -d '{
    "studentId": 1000000002,
    "application": {
      "jobTitle": "Senior Java Backend Developer",
      "companyName": "Amazon",
      "jobLocation": "Austin, TX",
      "jobLink": "https://www.amazon.jobs/en/jobs/1234567",
      "jobSource": "Amazon Careers"
    },
    "documents": { "resumeObjectId": 1000000003 }
  }'
```

---

## 2. `GET /api/v1/recruiter/students`

Lists the **active students assigned to the requesting recruiter**. It supplies the *Assigned Student* dropdown on
Step 1 of the Apply Job form: show `fullName` (and optionally `email`) as the label, use `studentId` as the value, and
send that `studentId` in the [POST above](#1-post-apiv1recruiterapplications). Everything is returned in one response;
there is no pagination. It is read-only.

### Request

**Headers**

| Header | Required | Description |
|---|---|---|
| `X-Dev-Recruiter-Id` | yes | positive integer: the recruiter's `user_id` (development only, see [Authentication](#authentication-development-only)) |
| `Accept` | no | `application/json`; not enforced |

No body and no query parameters. The recruiter is identified **only** by the header: a `recruiterId` in the query
string is ignored, never trusted.

**Which students are returned.** Those with a row in `recruiter_student_assignments` for this recruiter whose user is
not soft-deleted. A student assigned to another recruiter is never returned. The schema has no separate "active"
flag, so "active" means `users.is_deleted = FALSE`.

**Order.** Alphabetical by `firstName`, then `lastName`, then `studentId`, as applied by MySQL's default
case-insensitive collation.

### Responses

#### `200 OK`

```json
{
  "success": true,
  "data": {
    "recruiterId": 1000000001,
    "totalStudents": 3,
    "students": [
      {
        "studentId": 1000000002,
        "firstName": "John",
        "lastName": "Smith",
        "fullName": "John Smith",
        "email": "john.smith@example.com",
        "assignedAt": "2026-10-20T14:30:00Z"
      },
      {
        "studentId": 1000000004,
        "firstName": "Michael",
        "lastName": "Brown",
        "fullName": "Michael Brown",
        "email": "michael.brown@example.com",
        "assignedAt": "2026-10-22T19:45:00Z"
      },
      {
        "studentId": 1000000003,
        "firstName": "Sarah",
        "lastName": "Johnson",
        "fullName": "Sarah Johnson",
        "email": "sarah.johnson@example.com",
        "assignedAt": "2026-10-21T16:15:00Z"
      }
    ]
  }
}
```

All values are illustrative.

| Field | Type | Description |
|---|---|---|
| `data.recruiterId` | integer | the recruiter from the header |
| `data.totalStudents` | integer | number of entries in `students` |
| `data.students` | array | the assigned active students; `[]` when there are none (never `null`) |
| `students[].studentId` | integer | student id: use as the dropdown value |
| `students[].firstName`, `lastName` | string | trimmed |
| `students[].fullName` | string | trimmed first and last name joined by a single space |
| `students[].email` | string | |
| `students[].assignedAt` | string | when the assignment was made: a UTC instant ending in `Z`, e.g. `2026-10-20T14:30:00Z`. Convert it for display. `null` only if the database value is empty |

Deliberately **not** returned: phone number, verification flags, deletion flag, manager or recruiter details.

**A recruiter with no assigned students is still `200`:**

```json
{ "success": true, "data": { "recruiterId": 1000000001, "totalStudents": 0, "students": [] } }
```

#### Error responses

Checks run in the order below, and the first failure is returned.

| # | Status | `error` | `message` | Cause |
|---|---|---|---|---|
| 1 | 404 | `NOT_FOUND` | `Route not found` | path is not one of the recruiter routes |
| 2 | 405 | `METHOD_NOT_ALLOWED` | `Method not allowed; use GET` | method is not `GET`; response has header `Allow: GET` |
| 3 | 403 | `FORBIDDEN` | `Recruiter authentication is not configured` | `ALLOW_DEV_RECRUITER_HEADER` is not `true` on the server |
| 4 | 400 | `BAD_REQUEST` | `Missing required header X-Dev-Recruiter-Id` | header absent or blank |
| 5 | 400 | `BAD_REQUEST` | `X-Dev-Recruiter-Id must be a positive number` | header is not a positive integer (`abc`, `0`, `-5`, `1.5`, or too large for 64 bits) |
| 6 | 403 | `FORBIDDEN` | `Recruiter does not exist or is not active` | no `recruiters` row for that id, the id belongs to a user who is not a recruiter, or the recruiter is soft-deleted |
| 7 | 503 | `SERVICE_UNAVAILABLE` | `The service is temporarily unavailable. Please try again later.` | database unreachable or connection pool timed out; safe to retry |
| 8 | 500 | `INTERNAL_ERROR` | `An unexpected error occurred.` | any other failure, including a server that is missing its database configuration |

Notes:

- A recruiter with no students is **not** an error: that is a `200` with an empty `students` array.
- An unknown recruiter is a `403`, not a `404`, consistent with the POST above.
- The two recruiter routes are matched on **path and method**: `POST` to `/api/v1/recruiter/students` is a `405` with
  `Allow: GET`, and `GET` to `/api/v1/recruiter/applications` is a `405` with `Allow: POST`.

### Example

```bash
curl -i "http://127.0.0.1:3000/api/v1/recruiter/students" \
  -H "Accept: application/json" \
  -H "X-Dev-Recruiter-Id: 1000000001"
```

```powershell
curl.exe -i "http://127.0.0.1:3000/api/v1/recruiter/students" `
  -H "Accept: application/json" `
  -H "X-Dev-Recruiter-Id: 1000000001"
```

---

## 3. `GET /api/v1/student/applications`

The student dashboard in one call. Returns the student's **profile** (for the greeting and the Profile page),
**today's** applications (newest first, in pages of 20, all pages in one response) and **statistics** (monthly
progress, today's progress, Monday-to-Friday weekly activity). The backend
always reports the current date; there is **no `date` parameter and no pagination parameters**. Page navigation and
caching are the client's job.

### Request

**Headers**

| Header | Required | Description |
|---|---|---|
| `X-Dev-Student-Id` | yes | positive integer: the student's `user_id` |

No body. `Content-Type` is not needed.

**Query parameters**

None. The parameters `date`, `startDate`, `endDate`, `month`, `week`, `page` and `size` are **rejected with 400**
(matched case-insensitively) so a client is never silently given a different period than it expects. Any other
parameter is ignored.

**Reporting date and periods.** "Today", the current month, and Monday-to-Friday of the current week are calendar
ranges in the reporting zone (`APP_TIMEZONE`, default `America/Chicago`), resolved once per request from the server
clock. Days are 24 hours except at daylight saving (2026-11-01 is 25 hours, 2026-03-08 is 23). An application
belongs to the day/week/month it was recorded in *in that zone*, so one recorded at 8 PM Chicago time on Oct 29
(01:00 UTC Oct 30) counts under Oct 29.

**Order.** Newest first by `appliedAt`; ties broken by `applicationId`, highest first. The order holds across pages.

### Responses

#### `200 OK`

```json
{
  "success": true,
  "data": {
    "studentId": 1000000002,
    "date": "2026-10-29",
    "student": {
      "firstName": "John",
      "lastName": "Smith",
      "email": "john.smith@example.com",
      "phoneE164": "+17135550123",
      "phoneVerified": false
    },
    "totalApplications": 35,
    "pageSize": 20,
    "totalPages": 2,
    "pages": {
      "1": [
        {
          "applicationId": 1000000035,
          "jobTitle": "Senior Java Backend Developer",
          "companyName": "Amazon",
          "jobLocation": "Austin, TX",
          "jobLink": "https://example.com/jobs/35",
          "jobSource": "Amazon Careers",
          "appliedAt": "2026-10-29T10:30:00-05:00"
        }
      ],
      "2": [
        {
          "applicationId": 1000000015,
          "jobTitle": "Software Engineer",
          "companyName": "Microsoft",
          "jobLocation": "Houston, TX",
          "jobLink": "https://example.com/jobs/15",
          "jobSource": "LinkedIn",
          "appliedAt": "2026-10-29T09:15:00-05:00"
        }
      ]
    },
    "statistics": {
      "asOfDate": "2026-10-29",
      "timezone": "America/Chicago",
      "monthlyProgress": { "month": "2026-10", "applied": 820, "target": 1100, "remaining": 280, "percentage": 75 },
      "todayProgress":   { "date": "2026-10-29", "applied": 35, "target": 50, "remaining": 15, "percentage": 70 },
      "weeklyActivity":  {
        "weekStartDate": "2026-10-26",
        "targetPerWeekday": 50,
        "days": [
          { "day": "Mon", "date": "2026-10-26", "applications": 50 },
          { "day": "Tue", "date": "2026-10-27", "applications": 44 },
          { "day": "Wed", "date": "2026-10-28", "applications": 50 },
          { "day": "Thu", "date": "2026-10-29", "applications": 38 },
          { "day": "Fri", "date": "2026-10-30", "applications": 0 }
        ]
      }
    }
  }
}
```

(Page 1 would contain 20 entries; one is shown for brevity.)

| Field | Type | Description |
|---|---|---|
| `data.studentId` | integer | the student from the header |
| `data.date` | string | **today** in the reporting zone, `YYYY-MM-DD` |
| `data.student` | object | the requesting student's profile; always present on a `200`, even when there are no applications today |
| `student.firstName`, `student.lastName` | string | from the student's user record, trimmed |
| `student.email` | string | from the student's user record |
| `student.phoneE164` | string or `null` | the phone number **exactly as stored in the database** (surrounding whitespace removed), or `null` if none is stored. The field is always present. It is E.164 only if it was stored that way: see the note below |
| `student.phoneVerified` | boolean | the database's `phone_verified` flag, as stored. Show a "Verified" badge only when this is `true` |
| `data.totalApplications` | integer | **today's** application count, across all pages (equals `statistics.todayProgress.applied`) |
| `data.pageSize` | integer | always `20` |
| `data.totalPages` | integer | number of pages; `0` when there are none today |
| `data.pages` | object | keys are page numbers as **strings** (`"1"`, `"2"`, ...), from 1, no gaps, no empty pages |
| `...[].applicationId` | integer | |
| `...[].jobTitle`, `companyName`, `jobLocation`, `jobLink`, `jobSource` | string | as recorded |
| `...[].appliedAt` | string | ISO-8601 with the reporting-zone offset, e.g. `2026-10-29T10:30:00-05:00` |
| `statistics.asOfDate` | string | the reporting date, `YYYY-MM-DD` |
| `statistics.timezone` | string | reporting zone id, e.g. `America/Chicago` |
| `statistics.monthlyProgress` | object | `month` (`YYYY-MM`), `applied`, `target` (weekdays in month * daily target), `remaining` (`max(target-applied,0)`), `percentage` (`round(applied/target*100)`, not capped) |
| `statistics.todayProgress` | object | `date`, `applied`, `target` (daily target, default 50), `remaining`, `percentage` (not capped) |
| `statistics.weeklyActivity` | object | `weekStartDate` (Monday), `targetPerWeekday`, and `days`: exactly five Mon-Fri entries, each `{ day, date, applications }` |

Page sizes: 10 give one page of 10; 20 give one page of 20; 21 give 20 and 1; 35 give 20 and 15; 50 give 20, 20, 10.

**No applications today is still `200`.** The lists are empty, but the profile is still returned and the statistics
carry real history (this example also shows a student with no phone stored, so `phoneE164` is `null`):

```json
{
  "success": true,
  "data": {
    "studentId": 1000000002,
    "date": "2026-10-29",
    "student": {
      "firstName": "John",
      "lastName": "Smith",
      "email": "john.smith@example.com",
      "phoneE164": null,
      "phoneVerified": false
    },
    "totalApplications": 0,
    "pageSize": 20,
    "totalPages": 0,
    "pages": {},
    "statistics": {
      "asOfDate": "2026-10-29",
      "timezone": "America/Chicago",
      "monthlyProgress": { "month": "2026-10", "applied": 785, "target": 1100, "remaining": 315, "percentage": 71 },
      "todayProgress":   { "date": "2026-10-29", "applied": 0, "target": 50, "remaining": 50, "percentage": 0 },
      "weeklyActivity":  { "weekStartDate": "2026-10-26", "targetPerWeekday": 50, "days": [ "... five entries ..." ] }
    }
  }
}
```

**About `student.phoneE164`.** The database stores the phone in a free-form `VARCHAR(16)` and does not enforce any
format, so the API returns whatever is stored and never reformats or guesses a number: it never adds a country code
(`7135550123` stays `7135550123`, it does **not** become `+17135550123`) and never assumes a US number. The field keeps
the name `phoneE164` for the frontend contract, but the value is **not validated**: it is a true E.164 number only if it
was stored that way (for example `+17135550123`), and it may also be digits without a `+`, or contain spaces,
parentheses or dashes. Treat it as a display string, and do not pass it to anything that requires E.164 (such as an SMS
provider) without validating it first. It is `null` only when no phone is stored. `phoneVerified` is independent of the
number: it is never inferred from it and reports the database flag as stored.

Only the requesting student's profile, applications and counts are ever returned. Statistics come from aggregate SQL, not
from the page list. Weekday counts are bucketed by the reporting-zone date, and future weekdays report 0.

#### Error responses

Checks run in the order below, and the first failure is returned.

| # | Status | `error` | `message` | Cause |
|---|---|---|---|---|
| 1 | 404 | `NOT_FOUND` | `Route not found` | path is not `/api/v1/student/applications` |
| 2 | 405 | `METHOD_NOT_ALLOWED` | `Method not allowed; use GET` | method is not `GET`; response has header `Allow: GET` |
| 3 | 403 | `FORBIDDEN` | `Student authentication is not configured` | `ALLOW_DEV_STUDENT_HEADER` is not `true` on the server |
| 4 | 400 | `BAD_REQUEST` | `Missing required header X-Dev-Student-Id` | header absent or blank |
| 5 | 400 | `BAD_REQUEST` | `X-Dev-Student-Id must be a positive number` | header is not a positive integer (`abc`, `0`, `-5`, `1.5`, or too large for 64 bits) |
| 6 | 400 | `BAD_REQUEST` | `Query parameter '<name>' is not accepted; this endpoint always reports the current date and returns all pages` | a deprecated parameter was sent: `date`, `startDate`, `endDate`, `month`, `week`, `page` or `size` |
| 7 | 404 | `NOT_FOUND` | `Student not found` | the student does not exist, is soft-deleted, or the id belongs to a user who is not a student |
| 8 | 503 | `SERVICE_UNAVAILABLE` | `The service is temporarily unavailable. Please try again later.` | database unreachable or connection pool timed out; safe to retry |
| 9 | 500 | `INTERNAL_ERROR` | `An unexpected error occurred.` | any other failure, including a server that is missing its database configuration |

Notes:

- A student with no applications today is **not** a `404`; that is a `200` with empty `pages` (and their profile).
- The `404` in row 7 and the `404` in row 1 share the `NOT_FOUND` code. Their `message` differs.
- Row 7 returns only the error envelope: no profile, application or statistics data is ever returned for a student
  who is not found, and a different student's data is never substituted.

#### Frontend caching

The response carries `data.date` and `statistics.asOfDate`/`timezone`. A cached response must be invalidated when
the reporting date changes, even if the client's own (~1 hour) cache has not expired. Applications created during
the cache window do not appear until the next refresh, and neither do changes to the profile (name, email, phone,
verification). The payload is private student data (name, email, phone): do not configure shared/public caching for it.

### Example

```bash
curl -i "http://127.0.0.1:3000/api/v1/student/applications" \
  -H "X-Dev-Student-Id: 1000000002"
```

---

## Status code summary

| Status | POST recruiter applications | GET recruiter students | GET student applications |
|---|---|---|---|
| **200** OK | not used | assigned students returned (including none) | today's applications and statistics returned (including none today) |
| **201** Created | application recorded | not used | not used |
| **400** Bad Request | bad header, bad body, or failed field validation | missing or invalid `X-Dev-Recruiter-Id` | bad header, or a deprecated query parameter was sent |
| **403** Forbidden | dev header not enabled; recruiter not assigned (or user missing/deleted); resume or cover letter not found | dev header not enabled; recruiter does not exist or is deleted | dev header not enabled |
| **404** Not Found | unknown route | unknown route | unknown route; student not found or deleted |
| **405** Method Not Allowed | any method but `POST` (`Allow: POST`) | any method but `GET` (`Allow: GET`) | any method but `GET` (`Allow: GET`) |
| **409** Conflict | duplicate application for this student and job link | not used | not used |
| **500** Internal Server Error | unexpected failure | unexpected failure | unexpected failure |
| **503** Service Unavailable | database temporarily unreachable (retry) | database temporarily unreachable (retry) | database temporarily unreachable (retry) |

Retry guidance: `503` is transient and safe to retry for every endpoint (nothing was stored on a failed `POST`).
Do not blindly retry `400`, `403`, `404`, `405` or `409`; they will fail the same way until the request or the data
changes. Retrying a `500` is reasonable but may repeat the failure.

### Responses that do not come from this application

API Gateway can answer before a request reaches a Lambda, for example when no route matches the method and path.
Those responses use API Gateway's own format, not the error shape above. Locally, `sam local start-api` returns
`403` with `{"message": "Missing Authentication Token"}` for a path or method it has no route for. Because each
route is registered with an exact path and method, the application's own `404 Route not found` and `405` responses
are a safety net behind the gateway. Clients should treat any error body that lacks `"success": false` as a
generic failure.

---

## Not implemented yet

- Authentication and authorization (Cognito/JWT); the `X-Dev-*` headers are a stand-in and will be removed.
- Pagination controls (`page`, `limit`) on the GET: it intentionally returns every page at once.
- Rate limiting, CORS configuration and API versioning beyond the `/v1` path prefix.
- A deployed base URL.
