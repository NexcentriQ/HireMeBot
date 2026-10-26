# StudentLambda

`GET /api/v1/student/applications?date=YYYY-MM-DD`: returns every application a recruiter recorded for the
student on one calendar day, newest first, split into pages of 20 **in Java** and returned **all at once** in a
single JSON response. The frontend handles page navigation and caching.

> **Security status: development only.** The student is identified by the `X-Dev-Student-Id` header. That is
> **not authentication**. The header is honoured only when `ALLOW_DEV_STUDENT_HEADER=true` (set in your local
> `env.json`, `false` in the template, so a deployed stack answers 403). Do not expose this API publicly until
> Cognito/JWT replaces it.

## Layout

```
org.hmb
├── handler/StudentLambdaHandler           route + method, dev header, `date` parsing, JSON out, error mapping
├── service/StudentApplicationService      student check, day -> UTC range, load all rows, paginate in Java
├── repository/StudentRepository           exists and not soft-deleted
├── repository/StudentApplicationRepository  the parameterized applications query (no LIMIT/OFFSET/DATE())
├── dto/StudentApplicationDto, StudentApplicationsResponse
└── config/AppConfig                       APP_TIMEZONE
```

`DatabaseConfig` (HikariCP from the `DB_*` variables), `ApiException` and `ErrorResponse` (the shared error format)
and the logging config come from the shared [`common`](../common) module, the same classes RecruiterLambda uses.
**After changing `common`, run `mvn clean install -DskipTests` before `sam build`** (see Run locally).

## Timestamps: what `applied_at` means

`applications.applied_at` is a `DATETIME` with no zone. The recruiter endpoint never writes it; the column default
`CURRENT_TIMESTAMP` does, evaluated in the **connection's session time zone**. `DatabaseConfig` forces that zone to
**UTC** (`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`), so stored values are **UTC wall-clock**.
`StudentApplicationFlowIntegrationTest.connectionsRunInUtcSoTheAppliedAtDefaultIsUtc` checks this against the
database.

Consequences:

- The `date` parameter is a calendar day in `APP_TIMEZONE` (default `America/Chicago`). It is converted to a
  half-open UTC range: `2026-10-25` -> `applied_at >= '2026-10-25 05:00:00' AND applied_at < '2026-10-26 05:00:00'`
  (CDT, UTC-5). Days are 24 hours except at daylight saving: `2026-11-01` is 25 hours
  (`11-01 05:00` to `11-02 06:00`), `2026-03-08` is 23 hours (`03-08 06:00` to `03-09 05:00`).
- `appliedAt` in the response is a real UTC instant, e.g. `2026-10-25T15:30:00Z`.
- Omitting `date` means "today" in `APP_TIMEZONE`, not in UTC.
- Rows you insert by hand in Workbench with `NOW()` use the **server's** zone, not UTC, so they can land on
  the wrong day. Insert them with `UTC_TIMESTAMP()` or an explicit UTC value.

## Request

| | |
|---|---|
| Method / path | `GET /api/v1/student/applications` |
| Header | `X-Dev-Student-Id: <positive student user_id>` (case-insensitive name) |
| Query | `date=YYYY-MM-DD` (optional; years 1970 to 9998) |

## Response

`200`:

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
      "1": [ { "applicationId": 1000000021, "jobTitle": "...", "companyName": "...", "jobLocation": "...",
               "jobLink": "...", "jobSource": "...", "appliedAt": "2026-10-25T15:30:00Z" } ],
      "2": [ { "applicationId": 1000000001, "...": "..." } ]
    }
  }
}
```

Pages hold 20 applications each (the last may hold fewer), numbered from 1 with no empty trailing pages. With no
applications: `totalApplications: 0`, `totalPages: 0`, `"pages": {}` (still `200`).

Errors use the RecruiterLambda format `{ "success": false, "error": "<CODE>", "message": "...", "details": [...] }`:

| Status | When |
|---|---|
| 400 | missing or non-positive `X-Dev-Student-Id`; `date` not a real `YYYY-MM-DD` (including `2026-02-30`, a blank value, or an unsupported year) |
| 403 | `ALLOW_DEV_STUDENT_HEADER` is not `true` |
| 404 | student does not exist or is soft-deleted; unknown path |
| 405 | method other than GET (sends `Allow: GET`) |
| 503 | database unreachable or pool timeout |
| 500 | anything else (details only in the logs) |

## Run locally

Requires JDK 21, Maven, Docker (running) and the SAM CLI.

```bash
mvn clean test                              # unit tests; the database tests are skipped
mvn clean install -DskipTests               # puts the parent pom and `common` where sam build can find them
sam build
sam local start-api --env-vars env.json     # serves both Lambdas on http://127.0.0.1:3000
```

The `mvn clean install` step is required: `sam build` compiles each function in a temporary copy of its own folder
and resolves the parent pom and `hiremebot-common` from your local Maven repository (`~/.m2`), not from the sibling
folders. Skipping it fails with "dependencies.dependency.version for hiremebot-common is missing", or worse,
packages an old copy of `common`.

`env.json` needs a `StudentFunction` block (see `../env.json.example`), with `DB_HOST=host.docker.internal` for a
MySQL running on your machine, `DB_SSL_MODE=DISABLED` for a local MySQL without TLS, and
`ALLOW_DEV_STUDENT_HEADER=true`.

### Postman

- `GET http://localhost:3000/api/v1/student/applications?date=2026-10-25`
- Header: `X-Dev-Student-Id: 1000000002`

### curl

```bash
curl -i "http://localhost:3000/api/v1/student/applications?date=2026-10-25" \
  -H "X-Dev-Student-Id: 1000000002"
```

### Verify against MySQL

For `date=2026-10-25` (America/Chicago, CDT), the same rows the API returns, in the same order:

```sql
SELECT application_id, job_title, company_name, applied_at
FROM applications
WHERE student_id = 1000000002
  AND applied_at >= '2026-10-25 05:00:00'   -- local midnight, expressed in UTC
  AND applied_at <  '2026-10-26 05:00:00'
ORDER BY applied_at DESC, application_id DESC;
```

Use the UTC bounds for the day you ask for (see the daylight-saving examples above). Page `n` of the response is rows
`(n-1)*20 + 1` to `n*20` of this result.

## Tests

```bash
mvn test                     # 51 unit tests
RUN_DB_TESTS=true DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=... DB_USERNAME=... DB_PASSWORD=... DB_SSL_MODE=DISABLED \
  mvn -pl StudentLambda test -Dtest=StudentApplicationFlowIntegrationTest
```

The integration test writes to the configured database: each test creates its own uniquely named users and deletes
them afterwards, touching nothing else. Point it at a development database. Run from the host, use `127.0.0.1`
(not `host.docker.internal`, which only resolves inside Docker).

| Required scenario | Covered by |
|---|---|
| 10 / 20 / 21 / 35 / 50 applications, and 0 | service unit test (page-size matrix) and the same counts against real MySQL |
| No duplicates across pages; concatenated pages keep order | both of the above |
| Other students' data excluded | integration: `returnsOnlyTheRequestedStudentsApplications` |
| Only the selected date, exact boundaries | integration: `returnsOnlyTheRequestedDateAndIncludesExactBoundaries` |
| Identical timestamps ordered by id descending | integration: `identicalTimestampsAreOrderedByApplicationIdDescending` |
| Date omitted -> today in the app zone | service unit test |
| Invalid date / missing header / invalid id -> 400 | handler unit tests |
| Nonexistent / soft-deleted student -> 404 | service + handler unit tests; integration with real rows |
| Database unavailable -> 503 | service + handler unit tests |
| Daylight-saving days | service unit tests (23/24/25-hour days, contiguity) and integration tests on 2026-03-08 and 2026-11-01 |
