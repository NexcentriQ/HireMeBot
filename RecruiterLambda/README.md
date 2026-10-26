# RecruiterLambda

`POST /api/v1/recruiter/applications`: a recruiter records a job application they already made externally on
behalf of an assigned student. Nothing is submitted to the job site; the details are stored in HireMeBot (MySQL).

> **Security status: development only.** The recruiter is identified by the `X-Dev-Recruiter-Id` header. That is
> **not authentication**. The header is honoured only when `ALLOW_DEV_RECRUITER_HEADER=true` (set in the local
> `env.json`, `false` in the template). Do not deploy this API publicly until Cognito/JWT replaces it.

## Layout

```
org.hmb
├── handler/RecruiterLambdaHandler   routing, dev header, JSON in/out, error mapping
├── dto/RecruiterRequest             request record + validation (trim, length, URL checks)
├── dto/RecruiterResponse            success body
├── service/ApplicationService       transaction: assignment check -> document check -> insert
├── repository/AssignmentRepository  recruiter-student assignment lookup
├── repository/ApplicationRepository INSERT into applications (409 / 403 mapping from MySQL errors)
├── repository/DocumentRepository    object_store_entries lookup, application_documents insert
└── repository/QuestionRepository    application_questions inserts
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

## Tests

```bash
mvn test                                  # unit tests only; the DB integration test is skipped
RUN_DB_TESTS=true DB_HOST=... DB_NAME=... DB_USERNAME=... DB_PASSWORD=... mvn test   # also runs ApplicationFlowIntegrationTest
```

The suite is deliberately small: request validation, handler status codes (400/403/404/405/201), service
transaction behaviour (commit, rollback, 403, 409, 503, deadlock retry) and the duplicate-key mapping.

`ApplicationFlowIntegrationTest` writes to the configured database (it creates its own users and removes them).
Point it at a development database, never production. It covers the soft-delete filter, the real unique
constraint, and concurrent duplicate submissions.
