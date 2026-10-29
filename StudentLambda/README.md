# StudentLambda

`GET /api/v1/student/applications`: the student dashboard in one call. It returns **today's** applications
(newest first, split into pages of 20, all pages in one response) plus **statistics**: monthly progress, today's
progress, and Monday-to-Friday weekly activity. The frontend caches the whole response and navigates pages itself.

There is **no `date` parameter and no pagination parameters**. The backend always reports the current date in the
reporting zone and returns every page at once.

> **Security status: development only.** The student is identified by the `X-Dev-Student-Id` header. That is
> **not authentication**. The header is honoured only when `ALLOW_DEV_STUDENT_HEADER=true` (set in your local
> `env.json`, `false` in the template, so a deployed stack answers 403). Do not expose this API publicly until
> Cognito/JWT replaces it.

## Layout

```
org.hmb
├── handler/StudentLambdaHandler            route + method, dev header, deprecated-param rejection, JSON out
├── service/StudentApplicationService       resolves "today", loads today's apps, paginates, attaches statistics
├── service/StudentStatisticsService        reporting periods, weekday targets, percentages, Mon-Fri activity
├── repository/StudentRepository            exists and not soft-deleted
├── repository/StudentApplicationRepository today's applications query (no LIMIT/OFFSET/DATE())
├── repository/StudentStatisticsRepository  one aggregate query: month, today and each weekday count
├── dto/StudentApplicationDto, StudentApplicationsResponse
├── dto/StudentStatisticsDto, MonthlyProgressDto, TodayProgressDto, WeeklyActivityDto
├── config/AppConfig                        APP_TIMEZONE
└── config/StatisticsConfig                 DAILY_APPLICATION_TARGET
```

`DatabaseConfig`, `ApiException` and `ErrorResponse` come from the shared [`common`](../common) module.
**After changing `common` or any pom, run `mvn clean install -DskipTests` before `sam build`.**

## Time handling

`applications.applied_at` is a `DATETIME` written by `CURRENT_TIMESTAMP` on a connection whose session zone is
forced to UTC (see `common` `DatabaseConfig`), so stored values are UTC wall-clock. The reporting zone
(`APP_TIMEZONE`, default `America/Chicago`) defines calendar days and "today":

- **Today** is resolved once per request from an injectable clock, so applications and all statistics use the
  same day even around midnight.
- Every period (today, the current month, Monday-to-Friday of the current week) is converted to a half-open UTC
  range. Day boundaries come from `LocalDate.atStartOfDay(zone)`, so daylight-saving days are 23 or 25 hours, never
  assumed to be 24. Weekday counts are bucketed by the **Chicago** date, not the UTC date, so an evening
  application does not slip into the next day.
- `appliedAt` in the response is rendered in the reporting zone with its offset, e.g. `2026-10-29T10:30:00-05:00`
  (the instant is unchanged; only the rendering differs from the old UTC `...Z`).

## Statistics

Computed from the database with aggregate SQL (one `SUM(CASE WHEN ...)` query covering month, today and each
weekday), never by counting the page list.

- **Monthly:** `applied` this month, `target = weekdaysInMonth * DAILY_APPLICATION_TARGET`,
  `remaining = max(target - applied, 0)`, `percentage = round(applied / target * 100)` (not capped at 100).
- **Today:** `target = DAILY_APPLICATION_TARGET` (default 50), counted even on weekends.
- **Weekly:** exactly five entries Mon-Fri with real dates; days with no applications (including future days)
  report 0.

Monthly and weekly statistics reflect real history even when there are no applications today.

## Request

| | |
|---|---|
| Method / path | `GET /api/v1/student/applications` |
| Header | `X-Dev-Student-Id: <positive student user_id>` (case-insensitive name) |
| Query | none. `date`, `startDate`, `endDate`, `month`, `week`, `page`, `size` are rejected with 400 |

## Response (`200`)

```json
{
  "success": true,
  "data": {
    "studentId": 1000000002,
    "date": "2026-10-29",
    "totalApplications": 35,
    "pageSize": 20,
    "totalPages": 2,
    "pages": {
      "1": [ { "applicationId": 1000000035, "jobTitle": "...", "companyName": "...", "jobLocation": "...",
               "jobLink": "...", "jobSource": "...", "appliedAt": "2026-10-29T10:30:00-05:00" } ],
      "2": [ { "applicationId": 1000000015, "...": "..." } ]
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

`data.date` and `data.totalApplications` are about **today**. Page 1 holds 20 applications (the last page may hold
fewer); keys are strings `"1"`, `"2"`, ... With no applications today: `totalApplications: 0`, `totalPages: 0`,
`"pages": {}`, `todayProgress.applied: 0`, but monthly and weekly still carry real counts.

| Status | When |
|---|---|
| 200 | success, including a student with no applications today |
| 400 | missing or non-positive `X-Dev-Student-Id`; any deprecated query parameter present |
| 403 | `ALLOW_DEV_STUDENT_HEADER` is not `true` |
| 404 | student does not exist or is soft-deleted; unknown path |
| 405 | method other than GET (sends `Allow: GET`) |
| 503 | database unreachable or pool timeout |
| 500 | anything else (details only in the logs) |

## Configuration

| Variable | Notes |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_MODE` | database, as for RecruiterLambda |
| `APP_TIMEZONE` | reporting zone, default `America/Chicago` |
| `DAILY_APPLICATION_TARGET` | per-weekday target, default `50` |
| `ALLOW_DEV_STUDENT_HEADER` | must be `true` to accept `X-Dev-Student-Id` |

## Run locally

Requires JDK 21, Maven, Docker (running) and the SAM CLI.

```bash
mvn clean test                              # unit tests; the database tests are skipped
mvn clean install -DskipTests               # puts the parent pom and `common` where sam build can find them
sam build
sam local start-api --env-vars env.json     # serves both Lambdas on http://127.0.0.1:3000
```

### Postman

- `GET http://localhost:3000/api/v1/student/applications`
- Header: `X-Dev-Student-Id: 1000000002`
- No query parameters.

### curl

```bash
curl -i "http://localhost:3000/api/v1/student/applications" -H "X-Dev-Student-Id: 1000000002"
```

### Verify against MySQL

Today's applications (for `today` = 2026-10-29 Chicago, CDT; use the UTC bounds for the day you are testing):

```sql
SELECT application_id, job_title, company_name, applied_at
FROM applications
WHERE student_id = 1000000002
  AND applied_at >= '2026-10-29 05:00:00'   -- local midnight, in UTC
  AND applied_at <  '2026-10-30 05:00:00'
ORDER BY applied_at DESC, application_id DESC;

-- Monthly total (should equal statistics.monthlyProgress.applied)
SELECT COUNT(*) FROM applications
WHERE student_id = 1000000002
  AND applied_at >= '2026-10-01 05:00:00' AND applied_at < '2026-11-01 05:00:00';
```

## Frontend caching notes

- The response carries `data.date` and `statistics.asOfDate` / `statistics.timezone`. The frontend must invalidate
  its cache when the reporting date changes, even if its own (~1 hour) cache has not expired.
- Applications created during the cache window will not appear until the frontend refreshes.
- The response contains private student data: do not configure shared/public caching for it.

## Tests

```bash
mvn test                     # unit tests (StudentLambda: 67)
# Integration tests need a MySQL with hiremebot_db_V1 loaded. Run from the host with 127.0.0.1.
# MySQL 8 caching_sha2 over a non-TLS connection may need DB_SSL_MODE=REQUIRED (or allow public key retrieval).
RUN_DB_TESTS=true DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=... DB_USERNAME=... DB_PASSWORD=... DB_SSL_MODE=REQUIRED \
  mvn -pl StudentLambda test -Dtest=StudentApplicationFlowIntegrationTest
```

The integration test writes to the configured database: each test creates its own uniquely named users and deletes
them afterwards, touching nothing else. Point it at a development database.

| Required scenario | Covered by |
|---|---|
| 0 / 10 / 20 / 21 / 35 / 50 applications -> pages | service unit test + integration against real rows |
| Newest first; equal timestamps by id desc; no duplicate across pages | service unit + integration |
| Only today / only this student | integration |
| Deprecated `date` (and other) parameters -> 400 | handler unit test |
| Monthly count, weekday target (incl. Feb leap/non-leap, 20-23 weekday months) | statistics unit test |
| Today count incl. weekends and above-target; percentages; remaining never negative | statistics unit test |
| Weekly Mon-Fri, zero-fill, week spanning months/years, DST across weeks | statistics unit test |
| Timezone bucketing (evening app -> correct weekday), month exclusion | statistics integration test |
| Missing header / invalid id -> 400; nonexistent or soft-deleted -> 404; DB down -> 503 | handler + service unit tests |
