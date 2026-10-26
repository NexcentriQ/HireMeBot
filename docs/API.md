# HireMeBot API Reference

Two endpoints, each backed by its own Lambda:

| Endpoint | Purpose | Lambda |
|---|---|---|
| [`POST /api/v1/recruiter/applications`](#1-post-apiv1recruiterapplications) | A recruiter records a job application they already made for an assigned student | RecruiterLambda |
| [`GET /api/v1/student/applications`](#2-get-apiv1studentapplications) | A student lists the applications recorded for them on one calendar day | StudentLambda |

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
| Timestamps | ISO-8601 in UTC with a `Z` suffix, e.g. `2026-10-25T15:30:00Z` |
| Calendar days | `YYYY-MM-DD`, interpreted in the application time zone (`America/Chicago` by default) |
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
| `FORBIDDEN` | 403 | the caller is not allowed to do this, or a referenced object does not exist |
| `NOT_FOUND` | 404 | unknown route, or the student does not exist |
| `METHOD_NOT_ALLOWED` | 405 | the route exists but not for this HTTP method (response includes an `Allow` header) |
| `CONFLICT` | 409 | the application already exists |
| `INTERNAL_ERROR` | 500 | unexpected server error |
| `SERVICE_UNAVAILABLE` | 503 | the database is temporarily unreachable; safe to retry |

### Authentication (development only)

| Endpoint | Header | Server flag that enables it |
|---|---|---|
| POST recruiter applications | `X-Dev-Recruiter-Id: <recruiter user_id>` | `ALLOW_DEV_RECRUITER_HEADER=true` |
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

## 2. `GET /api/v1/student/applications`

Returns **every** application recorded for the student on one calendar day, newest first. The server splits them
into pages of 20 and returns **all pages in one response**; there is no `page` parameter. Page navigation and
caching are the client's job.

### Request

**Headers**

| Header | Required | Description |
|---|---|---|
| `X-Dev-Student-Id` | yes | positive integer: the student's `user_id` |

No body. `Content-Type` is not needed.

**Query parameters**

| Parameter | Required | Format | Description |
|---|---|---|---|
| `date` | no | `YYYY-MM-DD`, year 1970 to 9998 | the calendar day to list, in the application time zone. If omitted, **today** in that zone. An empty value (`?date=`) is an error, not "omitted" |

Other query parameters are ignored.

**How the day is computed.** `date=2026-10-25` means 00:00:00 to 24:00:00 on that day in `America/Chicago`
(configurable on the server). Days are 24 hours long except at daylight saving: 2026-11-01 is 25 hours and
2026-03-08 is 23. An application falls on the day in which it was recorded *in that zone*, so one recorded at
8 PM Chicago time on Oct 25 (01:00 UTC on Oct 26) is listed under `2026-10-25`.

**Order.** Newest first by `appliedAt`; applications with the same timestamp are ordered by `applicationId`,
highest first. This order holds across pages: reading page 1, then 2, and so on, gives the full day in order.

### Responses

#### `200 OK`

```json
{
  "success": true,
  "data": {
    "studentId": 1000000002,
    "date": "2026-10-25",
    "totalApplications": 21,
    "pageSize": 20,
    "totalPages": 2,
    "pages": {
      "1": [
        {
          "applicationId": 1000000025,
          "jobTitle": "Senior Java Backend Developer",
          "companyName": "Amazon",
          "jobLocation": "Austin, TX",
          "jobLink": "https://www.amazon.jobs/en/jobs/1234567",
          "jobSource": "Amazon Careers",
          "appliedAt": "2026-10-25T15:30:00Z"
        }
      ],
      "2": [
        {
          "applicationId": 1000000004,
          "jobTitle": "Software Engineer",
          "companyName": "Microsoft",
          "jobLocation": "Houston, TX",
          "jobLink": "https://example.com/jobs/30",
          "jobSource": "LinkedIn",
          "appliedAt": "2026-10-25T12:00:00Z"
        }
      ]
    }
  }
}
```

(Page 1 would contain 20 entries; one is shown for brevity.)

| Field | Type | Description |
|---|---|---|
| `success` | boolean | `true` |
| `data.studentId` | integer | the student from the header |
| `data.date` | string | the day that was queried (the resolved day when `date` was omitted) |
| `data.totalApplications` | integer | number of applications on that day, across all pages |
| `data.pageSize` | integer | always `20` |
| `data.totalPages` | integer | number of pages; `0` when there are none |
| `data.pages` | object | keys are page numbers as **strings** (`"1"`, `"2"`, ...), starting at 1, with no gaps and no empty pages. Each value is an array of applications |
| `...[].applicationId` | integer | |
| `...[].jobTitle`, `companyName`, `jobLocation`, `jobLink`, `jobSource` | string | as recorded |
| `...[].appliedAt` | string | UTC instant, `YYYY-MM-DDTHH:MM:SSZ` |

Page sizes: 10 applications give one page of 10; 20 give one page of 20; 21 give pages of 20 and 1; 35 give 20
and 15; 50 give 20, 20 and 10.

**No applications on that day is still `200`:**

```json
{
  "success": true,
  "data": {
    "studentId": 1000000002,
    "date": "2026-10-25",
    "totalApplications": 0,
    "pageSize": 20,
    "totalPages": 0,
    "pages": {}
  }
}
```

Only the requesting student's applications are ever returned.

#### Error responses

Checks run in the order below, and the first failure is returned.

| # | Status | `error` | `message` | Cause |
|---|---|---|---|---|
| 1 | 404 | `NOT_FOUND` | `Route not found` | path is not `/api/v1/student/applications` |
| 2 | 405 | `METHOD_NOT_ALLOWED` | `Method not allowed; use GET` | method is not `GET`; response has header `Allow: GET` |
| 3 | 403 | `FORBIDDEN` | `Student authentication is not configured` | `ALLOW_DEV_STUDENT_HEADER` is not `true` on the server |
| 4 | 400 | `BAD_REQUEST` | `Missing required header X-Dev-Student-Id` | header absent or blank |
| 5 | 400 | `BAD_REQUEST` | `X-Dev-Student-Id must be a positive number` | header is not a positive integer (`abc`, `0`, `-5`, `1.5`, or too large for 64 bits) |
| 6 | 400 | `BAD_REQUEST` | `Query parameter 'date' must be a valid date in YYYY-MM-DD format` | not a real calendar date in exactly that format: `25-10-2026`, `2026/10/25`, `2026-1-5`, `2026-02-30`, `2026-13-01`, or an empty value. Sending `date` twice is also rejected when API Gateway joins the values as `a,b` (the local `sam local` emulator may keep only one value instead) |
| 7 | 400 | `BAD_REQUEST` | `Query parameter 'date' is outside the supported range` | well-formed but the year is before 1970 or after 9998 |
| 8 | 404 | `NOT_FOUND` | `Student not found` | the student does not exist, is soft-deleted, or the id belongs to a user who is not a student |
| 9 | 503 | `SERVICE_UNAVAILABLE` | `The service is temporarily unavailable. Please try again later.` | database unreachable or connection pool timed out; safe to retry |
| 10 | 500 | `INTERNAL_ERROR` | `An unexpected error occurred.` | any other failure, including a server that is missing its database configuration |

Notes:

- A student with no applications is **not** a `404`; that is a `200` with empty `pages`.
- The `404` in row 8 and the `404` in row 1 share the `NOT_FOUND` code. Their `message` differs.

### Example

```bash
curl -i "http://127.0.0.1:3000/api/v1/student/applications?date=2026-10-25" \
  -H "X-Dev-Student-Id: 1000000002"
```

---

## Status code summary

| Status | POST recruiter applications | GET student applications |
|---|---|---|
| **200** OK | not used | applications returned (including none) |
| **201** Created | application recorded | not used |
| **400** Bad Request | bad header, bad body, or failed field validation | bad header or bad `date` |
| **403** Forbidden | dev header not enabled; recruiter not assigned (or user missing/deleted); resume or cover letter not found | dev header not enabled |
| **404** Not Found | unknown route | unknown route; student not found or deleted |
| **405** Method Not Allowed | any method but `POST` (`Allow: POST`) | any method but `GET` (`Allow: GET`) |
| **409** Conflict | duplicate application for this student and job link | not used |
| **500** Internal Server Error | unexpected failure | unexpected failure |
| **503** Service Unavailable | database temporarily unreachable (retry) | database temporarily unreachable (retry) |

Retry guidance: `503` is transient and safe to retry for both endpoints (nothing was stored on a failed `POST`).
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
