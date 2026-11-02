# RecruiterLambda

One Lambda, two routes (matched on path **and** method):

| Route | Purpose |
|---|---|
| `POST /api/v1/recruiter/applications` | a recruiter records a job application they already made externally on behalf of an assigned student. Nothing is submitted to the job site; the details are stored in HireMeBot (MySQL) |
| `GET /api/v1/recruiter/students` | lists the recruiter's active assigned students, to fill the Apply Job form's *Assigned Student* dropdown (see [below](#get-apiv1recruiterstudents)) |

> **Security status: development only.** The recruiter is identified by the `X-Dev-Recruiter-Id` header. That is
> **not authentication**. The header is honoured only when `ALLOW_DEV_RECRUITER_HEADER=true` (set in the local
> `env.json`, `false` in the template), for **both** routes. Do not deploy this API publicly until Cognito/JWT
> replaces it.

## Layout

```
org.hmb
├── handler/RecruiterLambdaHandler     route table (path + method), dev header, JSON in/out, error mapping
├── dto/RecruiterRequest               POST request record + validation (trim, length, URL checks)
├── dto/RecruiterResponse              POST success body
├── dto/AssignedStudentDto             one student in the GET response
├── dto/AssignedStudentsResponse       GET success body
├── service/ApplicationService         POST: transaction, assignment check -> document check -> insert
├── service/RecruiterStudentService    GET: recruiter check, then the assigned-students query (read-only)
├── repository/AssignmentRepository    active-assignment check (POST) and assigned-students query (GET)
├── repository/RecruiterRepository     recruiter exists and is not soft-deleted (GET)
├── repository/ApplicationRepository   INSERT into applications (409 / 403 mapping from MySQL errors)
├── repository/DocumentRepository      object_store_entries lookup, application_documents insert
└── repository/QuestionRepository      application_questions inserts
```

`DatabaseConfig` (HikariCP from the `DB_*` variables, created lazily and reused while warm), `ApiException`,
`ErrorResponse` and the logging config live in the shared [`common`](../common) module, which StudentLambda uses too.

## How the schema is used

- **Duplicate prevention:** `applications` has `UNIQUE (student_id, job_link_hash)` where `job_link_hash` is a
  generated `SHA2(job_link)`. The insert simply attempts the row and maps MySQL error 1062 to **409**. There is no
  SELECT-before-INSERT, so concurrent submissions cannot create two rows.
- **Assignment validation:** the service first runs the active-assignment query (recruiter and student both exist
  and neither `users.is_deleted`) -> **403** if there is no match. The composite FK
  `fk_application_assignment (recruiter_id, student_id)` is a second line of defence: if the assignment is removed
  concurrently, MySQL error 1452 is mapped to **403**.
- **Documents:** the resume (required) and cover letter (optional) must exist in `object_store_entries`, otherwise
  **403**. They are linked through `application_documents` in the same transaction as the application.
- **Questions:** optional (may be empty). If sent, every question/answer pair is validated and inserted into
  `application_questions` in the same transaction, so a failure there rolls back the application too.
- `application_id`, `created_at`, `updated_at`, `applied_at` and `job_link_hash` come from the database;
  `is_viewed` is always `FALSE`.

### Schema limitations that affect this endpoint

1. `is_deleted` is not enforced by any FK, so the soft-delete check lives in the assignment SQL.
2. `object_store_entries.student_id` is not checked against the request's `studentId`; a recruiter could attach
   another student's object. Intentionally not validated yet (decision pending).
3. Duplicate detection hashes the raw `job_link`: `.../1234567` and `.../1234567/` or a different query string are
   different links. The link is stored as given, not normalised.
4. `UNIQUE (student_id)` on `recruiter_student_assignments` means a student has exactly one recruiter.

## Configuration

Environment variables (see `../.env.example` and `../env.json.example`):

| Variable | Notes |
|---|---|
| `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | required |
| `DB_PORT` | default `3306` |
| `DB_SSL_MODE` | default `VERIFY_IDENTITY` (verified TLS). `DISABLED` only for a local MySQL without TLS |
| `DB_TRUSTSTORE_URL`, `DB_TRUSTSTORE_PASSWORD`, `DB_TRUSTSTORE_TYPE` | only for providers with a private CA (e.g. Aiven): a PKCS12/JKS truststore holding the CA cert |
| `ALLOW_DEV_RECRUITER_HEADER` | must be `true` to accept `X-Dev-Recruiter-Id` |

## Run locally

Requires JDK 21, Maven, Docker and the SAM CLI.

```bash
cp env.json.example env.json      # then fill in real values; env.json is git-ignored
mvn clean install -DskipTests     # required: sam build resolves the parent pom and `common` from ~/.m2
sam build
sam local start-api --env-vars env.json
```

### curl

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
    "documents": { "resumeObjectId": 1000000011, "coverLetterObjectId": 1000000012 },
    "questions": [ { "question": "Are you willing to relocate?", "answer": "Yes" } ]
  }'
```

### Postman

- `POST http://127.0.0.1:3000/api/v1/recruiter/applications`
- Headers: `Content-Type: application/json`, `X-Dev-Recruiter-Id: <recruiter user_id>`
- Body: raw JSON, same as above. `recruiterId` must **not** be in the body (it is rejected with 400).

Success: `201`

```json
{ "success": true, "message": "Application recorded successfully", "applicationId": 1000000042 }
```

`applicationId` is an addition to the specified two-field body.

Errors use `{ "success": false, "error": "<CODE>", "message": "...", "details": [ ... ] }`.

| Status | When |
|---|---|
| 400 | bad/missing field, malformed JSON, unknown field (e.g. `recruiterId`), missing/invalid dev header |
| 403 | recruiter not assigned, user soft-deleted, resume/cover-letter object not found, dev header disabled |
| 404 / 405 | other path / other method (405 sends `Allow: POST`) |
| 409 | the student already has an application with this job link |
| 503 | database unreachable or pool timeout |
| 500 | anything else (details only in logs) |

The test data in the database must match the IDs you send: a recruiter row, a student row, a row in
`recruiter_student_assignments` linking them, and `object_store_entries` rows for the resume and cover letter.

## GET /api/v1/recruiter/students

Lists the **active students assigned to the requesting recruiter**: those with a row in
`recruiter_student_assignments` for this recruiter whose user is not soft-deleted. The schema has no separate
"active" flag, so "active" means `users.is_deleted = FALSE`. Read-only, one response, no pagination, no query
parameters. Another recruiter's students are never returned (the query filters by the recruiter from the header, and
`UNIQUE (student_id)` means a student has exactly one recruiter).

- **Request:** header `X-Dev-Recruiter-Id: <recruiter user_id>`. A `recruiterId` query parameter is ignored.
- **Order:** `first_name`, then `last_name`, then `student_id`, done by MySQL (default case-insensitive collation).
- **Fields:** `studentId`, `firstName`, `lastName`, `fullName` (trimmed names joined by one space), `email`,
  `assignedAt`. Phone, verification flags, deletion flag and manager details are deliberately not returned.
- **`assignedAt`** is a UTC instant ending in `Z` (e.g. `2026-10-20T14:30:00Z`). `assigned_at` is a `TIMESTAMP`
  defaulted by `CURRENT_TIMESTAMP`, and every pooled connection runs in UTC (see `common` `DatabaseConfig`), so the
  suffix is accurate; the frontend localizes it. An integration test checks this against the database.
- **Logging:** only the recruiter id and the student count are logged, never names or emails.

```bash
curl -i "http://127.0.0.1:3000/api/v1/recruiter/students" -H "Accept: application/json" -H "X-Dev-Recruiter-Id: 1000000001"
```

```powershell
curl.exe -i "http://127.0.0.1:3000/api/v1/recruiter/students" -H "Accept: application/json" -H "X-Dev-Recruiter-Id: 1000000001"
```

Postman: `GET http://127.0.0.1:3000/api/v1/recruiter/students` with headers `Accept: application/json` and
`X-Dev-Recruiter-Id: <recruiter user_id>`; no body.

Success: `200`

```json
{
  "success": true,
  "data": {
    "recruiterId": 1000000001,
    "totalStudents": 2,
    "students": [
      { "studentId": 1000000002, "firstName": "John", "lastName": "Smith", "fullName": "John Smith",
        "email": "john.smith@example.com", "assignedAt": "2026-10-20T14:30:00Z" },
      { "studentId": 1000000003, "firstName": "Sarah", "lastName": "Johnson", "fullName": "Sarah Johnson",
        "email": "sarah.johnson@example.com", "assignedAt": "2026-10-21T16:15:00Z" }
    ]
  }
}
```

A recruiter with no students gets `200` with `"totalStudents": 0, "students": []`.

| Status | When |
|---|---|
| 200 | success, including a recruiter with no assigned students |
| 400 | missing or non-positive `X-Dev-Recruiter-Id` |
| 403 | `ALLOW_DEV_RECRUITER_HEADER` is not `true`; recruiter does not exist, is not a recruiter, or is soft-deleted |
| 404 / 405 | unknown path / other method (405 sends `Allow: GET`; `GET` on `/applications` sends `Allow: POST`) |
| 503 | database unreachable or pool timeout |
| 500 | anything else (details only in logs) |

Compare the response with the database (use your recruiter's id):

```sql
SELECT rsa.recruiter_id, rsa.student_id, u.first_name, u.last_name, u.email, rsa.assigned_at
FROM recruiter_student_assignments rsa
JOIN students s ON s.student_id = rsa.student_id
JOIN users u ON u.user_id = s.student_id
WHERE rsa.recruiter_id = 1000000001
  AND u.is_deleted = FALSE
ORDER BY u.first_name, u.last_name, rsa.student_id;
```

`assigned_at` in the query result is shown in your Workbench session's time zone, so it can differ from the API's UTC
value by your UTC offset. Run `SET time_zone = '+00:00';` first to compare directly.

## Tests

```bash
mvn test                                  # unit tests only; the DB integration tests are skipped
RUN_DB_TESTS=true DB_HOST=127.0.0.1 DB_NAME=... DB_USERNAME=... DB_PASSWORD=... DB_SSL_MODE=REQUIRED \
  mvn -pl RecruiterLambda test -Dtest='ApplicationFlowIntegrationTest,RecruiterStudentsIntegrationTest'
```

The unit suite covers request validation, handler routing and status codes for both routes (400/403/404/405/200/201),
the POST service's transaction behaviour (commit, rollback, 403, 409, 503, deadlock retry) and the duplicate-key mapping,
and the GET service (counts, order preserved, empty list, inactive recruiter, 503/500, name and timestamp mapping).

The integration tests write to the configured database (each test creates its own uniquely named users and removes
them). Point them at a development database, never production. Run from the host with `127.0.0.1` (not
`host.docker.internal`); MySQL 8's `caching_sha2_password` over a non-TLS connection may need `DB_SSL_MODE=REQUIRED`.

- `ApplicationFlowIntegrationTest` (POST): the soft-delete filter, the real unique constraint, concurrent duplicates.
- `RecruiterStudentsIntegrationTest` (GET): alphabetical order (including last-name tie-break), no leakage between
  recruiters, soft-deleted students excluded, soft-deleted / unknown / non-recruiter users rejected, the one-recruiter
  per student constraint, and `assignedAt` coming back as the current UTC instant.
