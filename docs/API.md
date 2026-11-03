# HireMeBot API Reference

Five endpoints, served by three Lambdas:

| Endpoint | Purpose | Lambda |
|---|---|---|
| [`POST /api/v1/recruiter/applications`](#1-post-apiv1recruiterapplications) | A recruiter records a job application they already made for an assigned student | RecruiterLambda |
| [`GET /api/v1/recruiter/students`](#2-get-apiv1recruiterstudents) | A recruiter lists their active assigned students (fills the Apply Job form's student dropdown) | RecruiterLambda |
| [`GET /api/v1/student/applications`](#3-get-apiv1studentapplications) | A student's dashboard: today's recorded applications plus monthly/daily/weekly progress statistics | StudentLambda |
| [`POST /api/v1/documents/upload-url`](#4-post-apiv1documentsupload-url) | A recruiter checks for a duplicate and gets a presigned S3 PUT URL for a student's resume | DocumentManagementLambda |
| [`POST /api/v1/documents/register`](#5-post-apiv1documentsregister) | A recruiter registers the uploaded resume (after verification) and receives its `objectId` | DocumentManagementLambda |

> **Status: development only.** Authentication is not implemented yet. Callers are identified by temporary
> `X-Dev-*` headers that are **not authentication** and are accepted only when the server is started with the
> matching flag. Do not expose these APIs publicly until Cognito/JWT replaces them (see
> [Authentication](#authentication-development-only)). The two document endpoints are stricter: they accept the
> header only under `sam local`, so a deployed stack answers `403` to every document call until production
> authentication exists.

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
| POST documents upload-url / register | `X-Dev-Recruiter-Id: <recruiter user_id>` | `ALLOW_DEV_RECRUITER_HEADER=true` **and** running under `sam local`; refused in every deployed environment |
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
| `documents.resumeObjectId` | integer | yes | positive; must be a **resume of this same student** (`object_store_entries` row with this `studentId` and type `RESUME`), such as one returned by [`register`](#5-post-apiv1documentsregister) |
| `documents.coverLetterObjectId` | integer | no | positive when present; must be a **cover letter of this same student** (type `COVER_LETTER`) |
| `questions` | array | no | omit or send `[]` for none; max 50 entries |
| `questions[].question` | string | yes, per entry | not blank, max 5000 characters |
| `questions[].answer` | string | yes, per entry | not blank, max 5000 characters |

Behavior to know about:

- **Strings are trimmed** before validation and storage.
- **The body is strict.** Unknown fields (at any level), wrong types (`"studentId": "1000000002"` or `1.5`),
  and anything after the JSON document are rejected with `400`. In particular, `recruiterId` in the body is
  rejected: the recruiter comes only from the header.
- `appliedAt` is not accepted; the server records the time of the call (UTC). `isViewed` starts as `false`.
- The resume and cover-letter ids must belong to the application's student and have the matching document type. A
  missing object, another student's object and an object of the wrong type all get the **same** `403`, so nothing
  about other students' documents is revealed. A resume cannot be attached as a cover letter or the reverse.
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
| 12 | 403 | `FORBIDDEN` | `Resume object does not exist` | `documents.resumeObjectId` is not a resume of this student: it does not exist, belongs to another student, or is not of type `RESUME` |
| 13 | 403 | `FORBIDDEN` | `Cover letter object does not exist` | `documents.coverLetterObjectId` was sent but is not a cover letter of this student (missing, another student's, or the wrong type) |
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

## Document upload (resumes): endpoints 4 and 5

A recruiter uploads a student's resume **directly to a private S3 bucket**, then registers it. The file never passes
through the API. Two API calls bracket one direct upload:

```
 1. Browser: SHA-256 of the file's bytes (Web Crypto)
 2. POST /api/v1/documents/upload-url   -> ALREADY_EXISTS (reuse objectId)   or   UPLOAD_REQUIRED (+ URL, headers, token)
 3. Browser: PUT the file to the presigned URL, sending exactly the returned headers      (straight to S3)
 4. POST /api/v1/documents/register     -> objectId  (the document is now registered in MySQL)
```

The returned `objectId` is what [`POST /api/v1/recruiter/applications`](#1-post-apiv1recruiterapplications) takes as
`documents.resumeObjectId`. Uploading a resume and attaching it to an application are separate operations; uploading
never creates an application.

**Rules that apply to both calls**

- **Only `RESUME` is enabled.** `COVER_LETTER` exists in the model but is disabled (`400 DOCUMENT_TYPE_NOT_ENABLED`).
- **Allowed files:** `.pdf` (`application/pdf`) and `.docx`
  (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`), at most **5 MB**. The extension and the
  declared content type must agree.
- **Global uniqueness by content.** The SHA-256 of the bytes is unique across the whole system. The same bytes can never
  be registered twice, by anyone, under any document type. A document belongs to exactly one student and is never shared.
- **Authorization.** The caller must be an active recruiter assigned to the student. The recruiter is the authenticated
  identity, never a field in the body.
- **Nothing is written to MySQL until the upload has been verified** (the `register` call).
- Responses carry `Cache-Control: no-store`: they contain upload URLs and tokens, which are credentials. Never log them.

> **Authentication for these two endpoints is local-development only.** The recruiter is identified by
> `X-Dev-Recruiter-Id`, and the server accepts that header **only when it runs under `sam local`**. In any deployed
> environment these endpoints answer `403 Recruiter authentication is not configured` to every request, whatever
> headers are sent. **Production authentication (a validated Cognito/JWT identity mapped to a recruiter) is a
> prerequisite before these APIs are exposed publicly.**

---

## 4. `POST /api/v1/documents/upload-url`

Checks whether these bytes are already registered and, if not, returns a short-lived presigned S3 `PUT` URL. It writes
nothing to MySQL or to S3.

### Request

**Headers:** `Content-Type: application/json`, `X-Dev-Recruiter-Id: <recruiter user_id>` (local development only).

```json
{
  "studentId": 1000000005,
  "documentType": "RESUME",
  "fileName": "Akhil_Resume.pdf",
  "contentType": "application/pdf",
  "fileSize": 245760,
  "checksum": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `studentId` | integer | yes | positive; the recruiter must be assigned to this student |
| `documentType` | string | yes | `RESUME` (enabled) or `COVER_LETTER` (disabled) |
| `fileName` | string | yes | at most 255 characters; its extension (`pdf` or `docx`) selects the format. Used only to read the extension: it is not stored and never becomes part of the S3 key |
| `contentType` | string | yes | must equal the format's type: `application/pdf` for `.pdf`; the long `...wordprocessingml.document` type for `.docx` |
| `fileSize` | integer | yes | 1 to 5,242,880 bytes; the exact size of the file |
| `checksum` | string | yes | SHA-256 of the file's bytes: 64 hexadecimal characters (upper case is accepted and lower-cased). **Not** a hash of the file name or path |

The body is strict: unknown fields (including `recruiterId`), wrong types and trailing content are rejected with `400`.

### Responses

#### `200 OK`, `status: "UPLOAD_REQUIRED"`

```json
{
  "success": true,
  "status": "UPLOAD_REQUIRED",
  "uploadRequired": true,
  "objectKey": "students/1000000005/resumes/123e4567-e89b-12d3-a456-426614174000.pdf",
  "uploadUrl": "https://<bucket>.s3.<region>.amazonaws.com/students/1000000005/resumes/123e...pdf?X-Amz-...",
  "uploadToken": "<signed upload intent>",
  "expiresIn": 300,
  "requiredHeaders": {
    "Content-Type": "application/pdf",
    "x-amz-checksum-sha256": "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
    "x-amz-tagging": "doc-state=pending"
  }
}
```

| Field | Description |
|---|---|
| `objectKey` | chosen by the server; pass it back unchanged to `register`. Never invent or alter it |
| `uploadUrl` | presigned S3 `PUT` URL, valid for `expiresIn` seconds (300). A credential: never log it |
| `uploadToken` | signed proof that this exact upload was authorized for this recruiter (valid 30 minutes). A credential: never log it. Pass it to `register` |
| `requiredHeaders` | the headers to send **exactly** with the `PUT`. They are part of the signature: a missing or different value makes S3 reject the upload (`403 SignatureDoesNotMatch`). `x-amz-checksum-sha256` is the base64 form of your hex checksum, computed by the server: just echo it |

**Performing the `PUT`:** `PUT <uploadUrl>` with `requiredHeaders` and the raw file bytes as the body. Do **not** send the
application's JWT or any other `Authorization` header to S3, do not put AWS credentials in the browser, and do not set
`Content-Length` (the browser sets it, and the signature requires it to equal `fileSize`). S3 itself rejects a body
whose SHA-256 or size differs from what was authorized. Call `register` only after S3 returns `200`.

#### `200 OK`, `status: "ALREADY_EXISTS"`

```json
{ "success": true, "status": "ALREADY_EXISTS", "uploadRequired": false, "objectId": 1000000001 }
```

The same student has already uploaded these exact bytes as the same type, and the file is still there. Do not upload
again; use `objectId`. Suggested message: *"This resume has already been uploaded."*

#### Error responses

Checks run in this order and the first failure is returned. All use the standard error envelope.

| # | Status | `error` | Cause |
|---|---|---|---|
| 1 | 404 | `NOT_FOUND` | path is not an endpoint of this API |
| 2 | 405 | `METHOD_NOT_ALLOWED` | method is not `POST` (`Allow: POST`) |
| 3 | 403 | `FORBIDDEN` (`Recruiter authentication is not configured`) | dev authentication is not permitted here: any deployed environment, or the flag is off |
| 4 | 400 | `BAD_REQUEST` | `X-Dev-Recruiter-Id` missing or not a positive integer |
| 5 | 400 | `BAD_REQUEST` | body missing, malformed JSON, wrong type, or contains an unknown field |
| 6 | 400 | `DOCUMENT_TYPE_NOT_ENABLED` | the type is not enabled (`COVER_LETTER` in V1). Reported before other field errors |
| 7 | 400 | `VALIDATION_ERROR` | a field rule failed; every problem is in `details`, e.g. `fileName must end with one of: pdf, docx`, `contentType must be application/pdf for a .pdf file`, `fileSize must be between 1 and 5242880 bytes`, `checksum must be a SHA-256 value: 64 hexadecimal characters` |
| 8 | 403 | `FORBIDDEN` (`Recruiter does not exist or is not active`) | the caller is not an active recruiter (a student id, a manager id, a deleted or unknown user) |
| 9 | 403 | `FORBIDDEN` (`Recruiter is not assigned to this student`) | not assigned, either user deleted, or the student does not exist |
| 10 | 409 | `DUPLICATE_DOCUMENT` | these bytes are registered to **another student**, or to this student under **another document type**. The message is generic (`This document is already registered.`): it never names the other student, a key or an id |
| 11 | 409 | `DOCUMENT_UNAVAILABLE` | these bytes are registered for this student but the stored file is missing. It cannot be re-uploaded (the checksum is unique). Contact support |
| 12 | 503 | `SERVICE_UNAVAILABLE` | database or S3 temporarily unavailable; safe to retry |
| 13 | 500 | `INTERNAL_ERROR` | anything else (cause only in the logs) |

A **presigned URL that has expired** is not an error from this API: S3 answers the `PUT` with `403`. Call `upload-url`
again for a fresh URL.

### Example

```bash
curl -i -X POST http://127.0.0.1:3000/api/v1/documents/upload-url \
  -H "Content-Type: application/json" -H "X-Dev-Recruiter-Id: 1000000001" \
  -d '{"studentId":1000000005,"documentType":"RESUME","fileName":"Akhil_Resume.pdf","contentType":"application/pdf","fileSize":245760,"checksum":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}'
```

---

## 5. `POST /api/v1/documents/register`

Called **after** the `PUT` to S3 succeeded. It verifies what S3 actually holds, then records the document in MySQL and
returns its `objectId`. It is idempotent and safe to retry.

### Request

**Headers:** `Content-Type: application/json`, `X-Dev-Recruiter-Id: <recruiter user_id>` (local development only).

```json
{
  "studentId": 1000000005,
  "documentType": "RESUME",
  "objectKey": "students/1000000005/resumes/123e4567-e89b-12d3-a456-426614174000.pdf",
  "checksum": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  "uploadToken": "<the uploadToken from upload-url>"
}
```

Every field must be exactly what `upload-url` authorized. The `checksum` in this body is **never trusted by itself**: it
is compared with the signed token and with the SHA-256 that S3 recorded for the object.

### What is verified before anything is stored

1. The caller is an active recruiter, still assigned to the student.
2. The token's signature, expiry and recruiter; and that its student, type, key and checksum equal the request.
3. The key is exactly a server-generated key for this student and type (nothing client-chosen is accepted).
4. S3 `HeadObject`: the object exists, its size and content type equal what was authorized, and the SHA-256 S3 stored
   (validated against the bytes at upload time) equals the authorized checksum.
5. Basic format check, see *Limitations* below.

Only then is the row inserted. The database `UNIQUE(hashed_value)` constraint is the final arbiter of duplicates, so two
simultaneous registrations of the same bytes can never both succeed.

### Responses

#### `201 Created` (registered by this call) or `200 OK` (already registered)

```json
{
  "success": true,
  "status": "SUCCESS",
  "objectId": 1000000002,
  "studentId": 1000000005,
  "documentType": "RESUME",
  "message": "Resume uploaded successfully"
}
```

`200` has the same body: it means the document was already registered, so a retry (for example after a timeout or a
lost response) returns the **same** `objectId` and creates nothing new. The same bytes already registered for the same
student also return that existing `objectId`.

#### Error responses

| # | Status | `error` | Cause |
|---|---|---|---|
| 1-5 | 404 / 405 / 403 / 400 | as for `upload-url` | route, method, dev-auth, header, body |
| 6 | 400 | `DOCUMENT_TYPE_NOT_ENABLED` / `VALIDATION_ERROR` | type disabled, or a field is missing or malformed (`details` lists them) |
| 7 | 403 | `FORBIDDEN` | not an active recruiter, or not assigned to the student |
| 8 | 400 | `UPLOAD_TOKEN_EXPIRED` | the token (30 minutes) expired: request a new upload URL and upload again |
| 9 | 403 | `UPLOAD_TOKEN_INVALID` | token malformed, forged, issued to another recruiter, bound to different values than the request, or the key is not the one the server issued |
| 10 | 409 | `DUPLICATE_DOCUMENT` | these bytes belong to another student or another document type |
| 11 | 409 | `DOCUMENT_UNAVAILABLE` | already registered, but the stored file is missing. Contact support |
| 12 | 400 | `UPLOAD_NOT_FOUND` | no object at that key: the `PUT` did not happen or failed. Request a new upload URL and upload again |
| 13 | 400 | `UPLOAD_VERIFICATION_FAILED` | S3's object does not match what was authorized (size, content type or checksum). Upload again |
| 14 | 400 | `INVALID_FILE_FORMAT` | the file does not look like a valid PDF or DOCX (see Limitations) |
| 15 | 503 | `SERVICE_UNAVAILABLE` | database or S3 temporarily unavailable; **retry the same request** |
| 16 | 500 | `INTERNAL_ERROR` | anything else (cause only in the logs) |

**Retrying `register`:** keep `objectKey` and `uploadToken` until you receive a success. On `503` or a network error,
send the identical request again: it is idempotent. Do not call `upload-url` again for the same file in that case; the
bytes are already in S3. Only when the token has expired (`UPLOAD_TOKEN_EXPIRED`) or the object is missing do you start
over from `upload-url`.

### Limitations of the file checks (read this before relying on them)

The format check is deliberately **basic**: it reads only the first few bytes and the tail of the file (two small ranged
reads), never the whole file.

- **PDF:** starts with `%PDF-` and contains `%%EOF` in the last 1 KB (catches truncated uploads).
- **DOCX:** starts with the zip signature `PK 03 04` and contains the zip end-of-central-directory record in the last
  ~64 KB (catches truncated or non-zip files).

Magic bytes and trailer markers are **trivially forged** and prove neither that a file is valid nor that it is safe. Any
complete zip (an XLSX, a JAR) passes the DOCX check, and a file with a PDF header can still contain active content or
fail to open. **There is no malware scanning.** Registered documents must be treated as untrusted. A scanner (for
example GuardDuty Malware Protection for S3 or ClamAV) and safe-serving rules (`Content-Disposition: attachment`, no
inline rendering) are prerequisites before any download or preview feature is built. The checksum proves only that the
stored bytes are the bytes the browser hashed; it says nothing about their content.

### Example

```bash
curl -i -X POST http://127.0.0.1:3000/api/v1/documents/register \
  -H "Content-Type: application/json" -H "X-Dev-Recruiter-Id: 1000000001" \
  -d '{"studentId":1000000005,"documentType":"RESUME","objectKey":"<objectKey>","checksum":"<sha256>","uploadToken":"<uploadToken>"}'
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

The two document endpoints share these codes (`200` / `201` / `400` / `403` / `404` / `405` / `409` / `500` / `503`), with
the specific `error` values listed in their own sections above; `register` returns `201` when it created the document
and `200` when it was already registered.

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

- Authentication and authorization (Cognito/JWT); the `X-Dev-*` headers are a stand-in and will be removed. This is a
  hard prerequisite for exposing any endpoint, and for the document endpoints it is the only way they can work once
  deployed.
- Pagination controls (`page`, `limit`) on the GET: it intentionally returns every page at once.
- Rate limiting, and API versioning beyond the `/v1` path prefix. (CORS is configured for one explicit portal origin.)
- Reading, downloading and deleting documents, cover-letter upload, and malware scanning of uploads.
- A deployed base URL.
