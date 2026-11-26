# HireMeBot Backend: Pre-Deployment Readiness, Security and Infrastructure Audit

| | |
|---|---|
| Audit date | 2026-11-19 |
| Repository | `NexcentriQ/HireMeBot`, branch `master` (up to date with `origin/master`) |
| Commit audited | `3446a40` ("Registration") **plus a large uncommitted working tree** (53 `git status` entries: staged `hiremebot_db_V1.sql`; modified `template.yaml`, `pom.xml`, `common`, Recruiter and Document Lambdas, docs; deleted `DocumentManagementLambda/.../DevAuth.java`, `DocumentReconciliationService` and its tests; untracked `common/src/main/java/org/hmb/{security,repository,model,logging}`, `common/src/test`, the whole `Registration/` module source, new auth tests, `docs/RECRUITER_AUTH_API.md`, `docs/STUDENT_AUTH_API.md`, `docs/DOCUMENT_CLEANUP_DESIGN.md`) |
| Toolchain used | Amazon Corretto 21.0.12, Apache Maven 3.9.16, SAM CLI 1.165.0, local MySQL 26.7.0 (Windows) |
| Scope | Read-only inspection of all source, POMs, `template.yaml`, `samconfig.toml`, SQL, docs, Postman collections; read-only AWS CLI inspection of `us-east-2`; safe test runs in an isolated scratch schema |
| Changes made by this audit | This file only. No tracked file was modified, nothing committed or pushed, nothing deployed, no AWS/Cognito/IAM/secret change, no real user data touched. Build outputs (`*/target`, `.aws-sam/build`, both git-ignored) were regenerated. The scratch schema `hmb_audit_9e3b07f8` was created for the test run and dropped afterwards; `hiremebot_db_v1` row counts were verified unchanged before and after. |

> **Important caveat.** Most of the authentication code being audited (all of `common/.../security`, the whole Registration
> module, the new auth tests and docs) is **uncommitted or untracked**. It exists only in this working copy. Commit it
> (after review) before any deployment pipeline is built, otherwise a clean checkout will not contain the audited code.

---

## A. Executive Summary

### Readiness

| Target | Verdict | Reason |
|---|---|---|
| 1. AWS development deployment | **NOT READY** | There is no AWS-reachable MySQL (read-only inspection of `us-east-2` found no RDS/Aurora instance, no Secrets Manager secret, no stack, only the default VPC) and the template has no VPC configuration (INF-01). Three of the four Lambdas (Recruiter, Student, Document) can only receive database settings as plaintext environment variables, and the template hard-codes those to `""` with no parameters (INF-02). Once INF-01 and INF-02 are done, a development stack is **READY WITH CONDITIONS**: the student dashboard route will answer `403` for every caller (fail-closed) and only `http://localhost:5173` will pass CORS. |
| 2. Staging deployment | **NOT READY** | Everything in (1), plus: the student dashboard API has no real authentication (AUTHZ-01), real Google-issued Cognito tokens have never been tested end to end (TEST-01/02), the Cognito app client only allows `localhost` callback URLs (COG-01), one CORS origin cannot serve two portals (CORS-01), no staging stack/pipeline exists (OPS-02), and the build can package a stale `common` jar from `~/.m2` (OPS-03). |
| 3. Production (public) deployment | **NOT READY** | Everything in (2), plus vulnerable Jackson versions (DEP-01), no monitoring/alarms or log retention for three functions (OPS-01), no backup/restore plan (OPS-04), no throttling/rate limiting (RATE-01), database connection scaling not designed (INF-03), least-privilege database accounts not created (IAM-01), and Cognito native password/SMS sign-in left enabled (COG-02). |

### The three P0 blockers

1. **AUTHZ-01: `GET /api/v1/student/applications` still has no authentication.** Verified in the current working
   tree: the route has no API Gateway authorizer (`template.yaml:190-195`) and the Lambda identifies the student only
   from the client-supplied `X-Dev-Student-Id` header (`StudentLambdaHandler.java:138-160`). With the shipped flag
   (`ALLOW_DEV_STUDENT_HEADER="false"`, `template.yaml:189`) it fails closed (`403` for everybody, the student
   dashboard does not work). If the flag were ever set to `true` on a deployed function, **anyone on the internet could
   read any student's name, email, phone and applications by changing one header** (no `AWS_SAM_LOCAL` gate, unlike the
   recruiter header). It also never checks `account_status`.
2. **INF-01: no database that a deployed Lambda can reach**, and none of the VPC/subnet/security-group/NAT plumbing.
3. **INF-02: no secure way to give Recruiter, Student and Document Lambdas their database credentials** (only
   Registration supports Secrets Manager).

### What is in good shape (verified)

* Cognito access-token verification in `common` (`JwtAccessTokenVerifier`) is strict: RS256-only key selector (no
  `alg=none`, no HS256 confusion), exact issuer, exact `client_id`, `token_use=access`, `openid` scope, required
  claims, `exp` with 30 s skew, `iat` not in the future, 8 KB max token, remote JWKS with timeouts, size limit and
  rate-limited refresh; tokens are never logged. Lambdas verify the token themselves even behind the API Gateway
  authorizer, and require the authorizer `sub` to match.
* Recruiter workspace and document APIs (4 routes) resolve the actor only from the verified token, then require a
  `recruiters` row, a `GOOGLE` identity, `account_status='ACTIVE'`, `is_deleted=FALSE` and (per request) an active
  recruiter-student assignment. `PENDING` and `SUSPENDED` recruiters are refused with `403`; suspension in MySQL takes
  effect on the next request. Client-supplied `recruiterId` cannot select the actor.
* Registration (4 routes) never trusts client email/role/status: email comes from Cognito UserInfo (must be verified),
  the user must be a Google-federated Cognito user (AdminGetUser), the insert of `users` + role row +
  `user_identities` is one transaction, recruiters are always `PENDING` with `manager_id NULL`, and an existing email
  or Cognito subject is refused rather than silently linked.
* Document upload uses server-generated S3 keys, an HMAC upload token binding recruiter, student, type, key, checksum,
  size and content type, presigned PUTs that sign length/type/SHA-256, and post-upload size/type/checksum/magic-byte
  verification. The bucket blocks public access, enforces TLS and SSE-S3.
* `mvn clean verify` with MySQL integration tests: **733 tests, 732 passed, 0 failures, 1 skipped** (the real-token live
  test, no token available). `sam validate --lint` and `sam build` succeed.
* No credentials, private keys, real tokens or AWS access keys were found in tracked files or git history (one AWS-key
  pattern in a unit test is a fake placeholder, see C.8).

---

## B. Backend Inventory

### B.1 Maven modules (root `pom.xml:10-16`)

Parent `helloworld:HireMeBot` (Java 21 via `maven.compiler.source/target`, no `release`, no enforcer). Managed
versions: AWS SDK BOM 2.57.7, `aws-lambda-java-core` 1.2.2, `aws-lambda-java-events` 3.11.0, Jackson 2.17.2,
HikariCP 5.1.0, `mysql-connector-j` 8.4.0, `nimbus-jose-jwt` 10.10, SLF4J 2.0.13, JUnit 5.10.2, Mockito 5.12.0,
shade 3.5.3, surefire 3.2.5.

| Module | Purpose | Lambda handler (SAM resource) | API endpoints | Direct dependencies | AWS resources | Database access | Authentication | Authorization checks | Tests (this run) | Deployment config |
|---|---|---|---|---|---|---|---|---|---|---|
| `common` (`hiremebot-common`) | Shared DB pool, errors, request ids, Cognito token verification, recruiter authenticator, account lookup | none (library) | none | jackson-annotations, HikariCP, slf4j-api, mysql-connector-j (runtime), nimbus-jose-jwt (**optional**) | Cognito JWKS (HTTPS) | `AccountProfileRepository`: SELECT users/user_identities/students/recruiters/managers by `cognito_sub` | `JwtAccessTokenVerifier`, `BearerToken`, `RecruiterAuthenticator`, `DevAuth` | Recruiter: Google identity, recruiter row, not deleted, not suspended, ACTIVE | 27 | Packaged into each Lambda; **SAM resolves it from `~/.m2`, not from source** (OPS-03) |
| `RecruiterLambda` | Recruiter workspace: list assigned students, record an application | `org.hmb.handler.RecruiterLambdaHandler::handleRequest` (`RecruiterFunction`, `template.yaml:109-160`) | `POST /api/v1/recruiter/applications`, `GET /api/v1/recruiter/students` | lambda-core/events, jackson-databind, common, nimbus, slf4j | none besides API GW; **no IAM policies** | SELECT users/recruiters/assignments/students/object_store_entries; INSERT applications, application_documents, application_questions (one transaction, deadlock retry x3) | Cognito access token (API GW authorizer + in-Lambda verification); local-only dev header | ACTIVE recruiter + active assignment; documents must belong to the student; strict JSON (unknown fields rejected) | 103 | 512 MB, 20 s (global), x86_64; DB settings `""` placeholders |
| `StudentLambda` | Student dashboard: today's applications + statistics | `org.hmb.handler.StudentLambdaHandler::handleRequest` (`StudentFunction`, `template.yaml:162-195`) | `GET /api/v1/student/applications` | lambda-core/events, jackson-databind, common, slf4j (no nimbus) | none; no IAM policies | SELECT students/users (profile), applications (today), aggregate statistics | **None.** `X-Dev-Student-Id` header when `ALLOW_DEV_STUDENT_HEADER=true`; otherwise `403` | **None** beyond `is_deleted=FALSE`; no `account_status` check | 131 | 512 MB, 20 s; **no authorizer** |
| `DocumentManagementLambda` | Resume upload: presigned PUT + registration of verified object | `org.hmb.handler.DocumentLambdaHandler::handleRequest` (`DocumentManagementFunction`, `template.yaml:266-354`) | `POST /api/v1/documents/upload-url`, `POST /api/v1/documents/register` | lambda-core/events, common, jackson-databind, nimbus, s3, secretsmanager, url-connection-client, slf4j | `DocumentsBucket` (S3), `UploadSigningSecret` (Secrets Manager) | SELECT recruiter/assignment/object_store_entries; INSERT object_store_entries | Cognito access token (authorizer + in-Lambda); local-only dev header | ACTIVE recruiter + active assignment on both calls; HMAC token bound to recruiter/student/type/key/checksum | 185 | IAM: S3 Put/PutTagging/GetObject on `students/*`, ListBucket, GetSecretValue on the signing secret |
| `Registration` | Unified "Continue with Google" for both portals: account lookup and registration | `org.hmb.handler.RegistrationHandler::handleRequest` (`RegistrationFunction`, `template.yaml:370-456`) | `GET /api/v1/auth/me`, `POST /api/v1/students/register`, `GET /api/v1/recruiters/auth/me`, `POST /api/v1/recruiters/register` | lambda-core/events, common, jackson-databind, nimbus, cognitoidentityprovider, secretsmanager, url-connection-client, slf4j | Cognito (AdminGetUser, hosted-domain `/oauth2/userInfo`), Secrets Manager (DB secret, optional) | SELECT/INSERT users, students, recruiters, user_identities (one transaction) | Cognito access token (authorizer + in-Lambda `CognitoAccessTokenVerifier` wrapper) | Email from UserInfo (verified), Google federation via AdminGetUser, no linking by email, role mismatch refused | 287 (1 skipped) | 1024 MB, 20 s; only function with `DB_SECRET_ARN`; `DB_NAME` hard-coded `hiremebot_db_V1` (`template.yaml:387`) |

### B.2 Module dependency map

```text
                     +---------------------------------------------+
                     | common (hiremebot-common)                   |
                     |  config/DatabaseConfig (HikariCP, MySQL)    |
                     |  exception/ApiException, ErrorResponse      |
                     |  logging/RequestIds                         |
                     |  security/JwtAccessTokenVerifier, BearerToken,
                     |           CognitoAuthConfig, RecruiterAuthenticator, DevAuth
                     |  repository/AccountProfileRepository        |
                     |  nimbus-jose-jwt is OPTIONAL here           |
                     +----------------------+----------------------+
                                            |
        +------------------+----------------+-----------------+--------------------+
        |                  |                                  |                    |
 RecruiterLambda     StudentLambda                 DocumentManagementLambda    Registration
 (+nimbus)           (no nimbus; uses only          (+nimbus, S3,              (+nimbus, Cognito IdP SDK,
                      DatabaseConfig/errors)         Secrets Manager)           Secrets Manager; own
                                                                                RegistrationDataSource,
                                                                                own error envelope "code")

 External: API Gateway HTTP API (JWT authorizer "CognitoAccessToken")
           Cognito user pool us-east-2_F582IgoSY (JWKS, /oauth2/userInfo, AdminGetUser)
           MySQL (local today; no AWS database exists)
           S3 DocumentsBucket + Secrets Manager UploadSigningSecret (template-defined, not deployed)
```

### B.3 Complete API inventory

All nine routes found in code are present in `template.yaml`; no route exists in code without a SAM event, and no SAM
event exists without code. There are no Lambda Function URLs and no REST APIs.

| Method | Path | Lambda | Authentication | Authorization | Database access | CORS | Risk | Deployment status |
|---|---|---|---|---|---|---|---|---|
| GET | `/api/v1/auth/me` | RegistrationFunction | Cognito JWT authorizer (`openid`) + in-Lambda verification + authorizer-`sub` match | Student account rules: deleted/suspended/type-mismatch refused, non-Google refused; unknown user gets `SIGNUP_FORM` | SELECT users, user_identities, role tables | Single `PortalOrigin` | Low | Not deployed (unverified in AWS) |
| POST | `/api/v1/students/register` | RegistrationFunction | Same + Cognito UserInfo (email, `email_verified`) + AdminGetUser (Google federation) | Refuses existing sub/email, role conflict; status from `PHONE_VERIFICATION_REQUIRED` | INSERT users, students, user_identities (1 transaction) | Single `PortalOrigin` | Low | Not deployed |
| GET | `/api/v1/recruiters/auth/me` | RegistrationFunction | Same as `/auth/me` | Recruiter rules: deleted/type-mismatch/suspended refused; PENDING gets `APPROVAL_PENDING` | SELECT | Single `PortalOrigin` | Low | Not deployed |
| POST | `/api/v1/recruiters/register` | RegistrationFunction | Same as student register | Always `PENDING`, `manager_id NULL`; no linking | INSERT users, recruiters, user_identities (1 transaction) | Single `PortalOrigin` | Low | Not deployed |
| GET | `/api/v1/recruiter/students` | RecruiterFunction | Cognito JWT authorizer + in-Lambda verification | ACTIVE, non-deleted, Google recruiter; returns only own active assignments | SELECT assignments, users | Single `PortalOrigin` | Low | Not deployed |
| POST | `/api/v1/recruiter/applications` | RecruiterFunction | Same | ACTIVE recruiter + active assignment; resume/cover-letter objects must belong to the student and type; composite FK backstop | INSERT applications, application_documents, application_questions | Single `PortalOrigin` | Low | Not deployed |
| POST | `/api/v1/documents/upload-url` | DocumentManagementFunction | Same | ACTIVE recruiter + active assignment; server-generated key | SELECT object_store_entries (duplicate check) | Single `PortalOrigin` (+ S3 bucket CORS PUT) | Low | Not deployed |
| POST | `/api/v1/documents/register` | DocumentManagementFunction | Same | ACTIVE recruiter + assignment + HMAC token claims + exact key shape + S3 object verification | INSERT object_store_entries | Single `PortalOrigin` | Low | Not deployed |
| GET | `/api/v1/student/applications` | StudentFunction | **None (public route).** Dev header only | **None.** Student chosen by client header; `is_deleted` only | SELECT users, students, applications | Single `PortalOrigin`; **`X-Dev-Student-Id` allowed** | **Critical if flag enabled; otherwise non-functional (403)** | Not deployed |

**Specific classifications (Phase 1):**

| Question | Result |
|---|---|
| APIs without authorizers | `GET /api/v1/student/applications` only (`template.yaml:190-195`). Unintentionally unprotected: the docs (`docs/API.md:26-28,903`) and the template comment (`template.yaml:187-189`) call it "not yet migrated". There are **no intentionally public routes**. |
| APIs that trust client-provided user IDs | `GET /api/v1/student/applications` (`X-Dev-Student-Id`, when the flag is on). Recruiter and document APIs take `studentId` from the body but only as the *target*, checked against the caller's assignment; `recruiterId` in the body is rejected (strict parser) and never used as the actor. |
| APIs using development authentication | Student applications (always). Recruiter + document routes only under `sam local` with no `Authorization` header (see C.5). |
| APIs not validating account status | Student applications (no `account_status` check, `StudentRepository.java:19-30`). The recruiter assignment query checks the recruiter's status but not the student's (`AssignmentRepository.java:17-29`); see AUTHZ-03. |
| APIs lacking ownership checks | Student applications (no caller identity at all). |
| APIs allowing access to other users' records | Student applications if `ALLOW_DEV_STUDENT_HEADER=true` (any caller, any student). None found elsewhere. |
| APIs not represented in SAM | None. |
| Authorizer configuration differing from intent | Student route (no authorizer). All other 8 routes: `Authorizer: CognitoAccessToken`, `AuthorizationScopes: [openid]`, matching the in-Lambda check. Note API Gateway's JWT authorizer does not check `token_use`; ID tokens carry no `scope` claim, so with scopes configured API Gateway rejects them (`403`) and the Lambdas reject them too. |

### B.4 Lambda runtime configuration (from `template.yaml`)

| Function | Runtime / arch | Memory | Timeout | Reserved concurrency | VPC | Log group retention | IAM policies |
|---|---|---|---|---|---|---|---|
| RecruiterFunction | java21 / x86_64 | 512 MB | 20 s | none | none | **not defined (never expires)** | none (basic execution role) |
| StudentFunction | java21 / x86_64 | 512 MB | 20 s | none | none | **not defined** | none |
| DocumentManagementFunction | java21 / x86_64 | 512 MB | 20 s | none | none | **not defined** | S3 + signing-secret read (`template.yaml:309-334`) |
| RegistrationFunction | java21 / x86_64 | 1024 MB | 20 s | none | none | 30 days (`template.yaml:458-462`) | `cognito-idp:AdminGetUser` on the pool; conditional `secretsmanager:GetSecretValue` on the DB secret (`template.yaml:401-418`) |
| HTTP API access logs | n/a | | | | | 30 days (`template.yaml:104-107`), no headers logged | |

No layers, no SnapStart, no aliases/versions, no provisioned concurrency.

### B.5 API contracts vs documentation (Phase 13)

Frontend source code is **not in this repository**, so the frontend itself is **NOT VERIFIED**. Backend code was
compared with `docs/API.md`, `docs/STUDENT_AUTH_API.md`, `docs/RECRUITER_AUTH_API.md` and the three Postman
collections in `Registration/postman/`.

| Item | Result |
|---|---|
| Routes | All nine documented routes match `template.yaml` and handler routing. |
| Student sign-in/sign-up responses | `AccountLookupResponse` (`registered`, `nextStep` = `DASHBOARD` / `SIGNUP_FORM` / `ACCOUNT_PENDING`) and `ApiResponse` (`code: STUDENT_REGISTERED`) match `STUDENT_AUTH_API.md` sections 3-4. |
| Recruiter sign-in/sign-up responses | `RecruiterAccountLookupResponse` (`RECRUITER_DASHBOARD` / `RECRUITER_SIGNUP_FORM` / `APPROVAL_PENDING`), `RECRUITER_REGISTRATION_SUBMITTED` match `RECRUITER_AUTH_API.md` sections 3-4. |
| Recruiter dashboard APIs accept Cognito Bearer tokens | Yes in code and docs (`docs/API.md:78-114`); verified only with locally signed tokens. |
| Pending/approved routing | Server returns `APPROVAL_PENDING` for PENDING, `RECRUITER_DASHBOARD` for ACTIVE; workspace APIs return `403 "Your recruiter account is waiting for approval"` for PENDING. Matches docs. |
| Error envelope | **Two shapes.** Registration: `{"success":false,"code":...,"message":...,"fieldErrors":[...],"requestId":...}` (`Registration/.../dto/ApiResponse.java:12-19`), validation code `VALIDATION_FAILED`. Others: `{"success":false,"error":...,"message":...,"details":[...]}` (`common/.../exception/ErrorResponse.java:9`), validation code `VALIDATION_ERROR`, no `requestId` in the body. Deployed API Gateway adds a third shape: `{"message":"Unauthorized"}` (401) / `{"message":"Forbidden"}` (403, e.g. missing scope). All three are documented (`docs/API.md:17-19,890-897`, `STUDENT_AUTH_API.md` section 9, `RECRUITER_AUTH_API.md` section 11), so this is a frontend-integration risk, not a defect (ERR-01). |
| CORS | One origin for both portals (CORS-01). `X-Dev-Student-Id` is allowed cross-origin. |
| Session refresh | Documented (`STUDENT_AUTH_API.md` section 7, `RECRUITER_AUTH_API.md` section 8): access token 60 min, the frontend refreshes with the refresh token (5 days, no rotation, confirmed in Cognito). |
| Pagination | `GET /api/v1/student/applications` rejects `page`/`size` and returns all pages of today's list at once (`StudentLambdaHandler.java:52`); `GET /api/v1/recruiter/students` returns every assigned student (no pagination). Both documented (`docs/API.md:905`). |
| Request validation | Registration: 4 KB body cap, `415` for non-JSON, unknown fields (e.g. `email`, `accountStatus`) ignored and only their names logged (`RegistrationRequestParser.java:65,92`). Recruiter: strict (unknown fields `400`), max 50 questions, 5000 chars per question/answer (`RecruiterRequest.java:26-32`). Matches docs. |
| Postman | Collections use a secret variable `cognitoAccessToken` (current value only, not committed). The only literal tokens are deliberately fake (`kid":"fake"`). No student-dashboard collection exists. |

---

## C. Security Findings

### C.1 Cognito JWT validation (Phase 2)

Actual classes: `common/src/main/java/org/hmb/security/{JwtAccessTokenVerifier, BearerToken, CognitoAuthConfig,
RecruiterAuthenticator, VerifiedAccessToken, DevAuth, TokenVerifier}`, `common/.../repository/AccountProfileRepository`,
and in Registration `security/{CognitoAccessTokenVerifier (wraps the common verifier for the "code" envelope),
CognitoUserInfoClient, CognitoAdminUserLookup}` and `service/CognitoIdentityService`.

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Signature against trusted Cognito JWKS | Pass | `JwtAccessTokenVerifier.java:88-101` (JWKS URL derived from configured region/pool, not from the token) |
| 2 | Only approved algorithms | Pass (RS256 only) | `JwtAccessTokenVerifier.java:72-73` |
| 3 | No `alg=none` bypass | Pass | Key selector is RS256-only; unit tests cover `none` |
| 4 | No HS256/RS256 confusion | Pass | Same; HMAC keys are never selected |
| 5 | Issuer | Pass (exact match) | `JwtAccessTokenVerifier.java:74-82` |
| 6 | App client | Pass (`client_id` exact; access tokens have no `aud`) | `:79` |
| 7 | `token_use=access` | Pass | `:78` (ID tokens refused) |
| 8 | Required scopes | Pass (`openid`) | `:154-155`; matches `AuthorizationScopes: [openid]` and the app client's allowed scopes `openid email` |
| 9 | Expiration | Pass | Nimbus `DefaultJWTClaimsVerifier`, required `exp` |
| 10 | `iat` / skew | Pass (30 s; `iat` must not be in the future) | `:58,83,142` |
| 11 | JWKS rotation | Pass | Remote JWKS cache with rate-limited refresh on unknown `kid` (`:94-101`); unknown `kid` = reject; `KeySourceException` = `503` (`:132-134`) |
| 12 | Missing/malformed header | Pass | Case-insensitive `Bearer` + strict base64url regex, 8 KB cap (`RecruiterAuthenticator.java:57,130-140`; `RegistrationHandler.java:85`) -> `401` |
| 13 | Invalid tokens | Pass | `401 UNAUTHORIZED "Authentication is required."`, reason logged only |
| 14 | No token leakage in logs | Pass | Only reasons are logged (`:178`); access-log format excludes headers (`template.yaml:98-101`); grep of all `LOG.*` calls found no token/email/name logging |
| 15 | No untrusted claim decoding as authentication | Pass | Claims are read only after verification; authorizer claims are used only to *cross-check* `sub` (`RecruiterAuthenticator.java:146-149`, `RegistrationHandler.java:309`) |

**Local vs deployed:** under `sam local` the authorizer does not run, so the in-Lambda verification is the only gate;
it is tested (JVM, locally signed RS256 keys). Deployed API Gateway behaviour (401 `{"message":"Unauthorized"}`,
403 for missing scope) is **NOT VERIFIED**: nothing is deployed.

### C.2 OAuth and identity mapping

| Check | Result |
|---|---|
| Google federation | Registration: `CognitoIdentityService` calls UserInfo (subject must equal the token `sub`, email present and well-formed, `email_verified` true) and AdminGetUser (same `sub`, user enabled, status `EXTERNAL_PROVIDER`, `identities` contains Google). Recruiter workspace: `AccountProfileRepository` requires the stored identity provider to be `GOOGLE`. |
| Cognito `sub` | Unique (`user_identities.uq_cognito_sub`), regex-checked, primary lookup key. |
| `email_verified` | Required at registration (`403 EMAIL_NOT_VERIFIED`). The Google IdP attribute mapping includes `email_verified` (confirmed read-only in Cognito). Lookups of already-registered users do not re-read it. |
| Native Cognito vs Google | Native users are refused by the backend (AdminGetUser check at registration; provider check afterwards). The user pool itself still allows native sign-up/sign-in (COG-02). |
| Duplicate subjects / emails | DB-enforced unique `cognito_sub` and `email`; `AccountRegistrar` refuses an existing sub or email inside the transaction and re-checks after a duplicate-key race. |
| Account linking | **No automatic linking.** A verified email that already belongs to another identity returns `409 ACCOUNT_IDENTITY_CONFLICT` (or the deleted/suspended refusal) and nothing is written (`AccountRules.refuseEmailOwner`, `RecruiterAccountRules.refuseEmailOwner`). Matching email alone cannot attach a new Cognito subject to an existing user. |
| Role confusion | One Google account = one role: a student token on recruiter routes gets `403 ACCOUNT_TYPE_MISMATCH` (sign-in) / `403 "This account is not a recruiter account"` (workspace). Both portals share one app client; role is decided server-side only. |

### C.3 Session and token revocation

| Question | Finding |
|---|---|
| Cognito token revocation enabled? | Yes (`EnableTokenRevocation: true` on the app client, read-only inspection). |
| Would JWT verification accept a revoked but unexpired access token? | **Yes.** Neither API Gateway's JWT authorizer nor `JwtAccessTokenVerifier` consults Cognito. Revoked/global-signed-out/Cognito-disabled users keep working until `exp`. Registration's *registration* path calls UserInfo (which does reject revoked tokens); the known-account `/auth/me` and `/recruiters/auth/me` paths do not. |
| Does MySQL suspension take effect immediately? | **Recruiter APIs: yes** (status read on every request). **Student dashboard: no** (no status check, AUTHZ-01). |
| Access-token lifetime | 60 minutes (ID token 60 min, refresh token 5 days, refresh-token rotation off). |
| Stolen-token reuse risk | Up to 60 minutes per stolen access token; up to 5 days for a stolen refresh token unless revoked (revocation does work for refresh tokens). |
| Recommendation (SEC-01, P2) | No blacklist. Rely on MySQL `account_status` (already immediate for recruiters; add to students in AUTHZ-01) as the kill switch; reduce the access-token lifetime to 15-30 min if the product accepts more frequent refreshes; enable refresh-token rotation; document "suspend in MySQL + AdminUserGlobalSignOut in Cognito" as the incident procedure. |

### C.4 Authorization and BOLA/IDOR (Phase 3)

| Scenario | Result | Evidence |
|---|---|---|
| Recruiter checks performed server-side | Valid token AND `user_identities` match AND `recruiters` row AND ACTIVE AND not deleted (`RecruiterAuthenticator.java:154-184`), then per-request ACTIVE + assignment (`AssignmentRepository.java:17-29`, `RecruiterAccessRepository.java:16-40`) | Code + MySQL ITs |
| Recruiter A lists Recruiter B's students | Not possible: query keyed by authenticated recruiter id | `RecruiterStudentsIntegrationTest` |
| Recruiter A creates application for unassigned student | `403 "Recruiter is not assigned to this student"`; composite FK `fk_application_assignment` as a backstop | `ApplicationService.java:115-139`, `hiremebot_db_V1.sql:111-115` |
| Recruiter A attaches another student's resume | `403 "Resume object does not exist"` (ownership + type check) | `ApplicationService.java:127-132` |
| Recruiter A requests upload URL / registers document for unassigned student | `403` | `DocumentUploadService.java:274-281` |
| Client modifies object key / reuses another recruiter's upload token | `403 UPLOAD_TOKEN_INVALID` (HMAC claims bound to recruiter, student, type, key, checksum; key must equal the server pattern) | `DocumentUploadService.java:283-305` |
| Changing `recruiterId` / `userId` in body or query | Body: unknown field -> `400`; query: ignored; actor always from token | `RecruiterRequest`, `docs/API.md:100-101` |
| PENDING recruiter on ACTIVE-only APIs | `403 "Your recruiter account is waiting for approval"` | `RecruiterAuthenticator.java:176-177` |
| Suspended recruiter with valid token | `403 "Your recruiter account has been suspended"` (next request after the MySQL update) | `:172-173` |
| Student token on recruiter APIs | `403 "This account is not a recruiter account"` | `:171` |
| Student A reads Student B's applications | **Possible by design if `ALLOW_DEV_STUDENT_HEADER=true`**: set `X-Dev-Student-Id` to B's `user_id`. With the shipped `false`, everybody gets `403` | `StudentLambdaHandler.java:138-160` (AUTHZ-01) |
| Student/recruiter reads another user's resume | No download endpoint exists in V1; nothing to exploit. | `docs/API.md:907` |
| Database FKs mistaken for authorization? | No: FKs are a backstop only; every check is in application code first. One gap: `application_documents` does not tie the object to the same student at DB level (DB-05). | |
| Student account status in recruiter flows | The assignment query checks the recruiter's status and the student's `is_deleted`, **not the student's `account_status`**: an ACTIVE recruiter can still record applications/upload resumes for a SUSPENDED or PENDING student (AUTHZ-03, business-rule decision). | `AssignmentRepository.java:17-29` |

### C.5 Development authentication paths (Phase 4): complete list

| # | Location | Mechanism | Can it authenticate in an AWS deployment? | Status |
|---|---|---|---|---|
| 1 | `StudentLambda/.../StudentLambdaHandler.java:38-46,65-68,138-160` | `X-Dev-Student-Id` trusted when `ALLOW_DEV_STUDENT_HEADER=true`. **No `AWS_SAM_LOCAL` gate, no token path, no authorizer on the route.** | **Yes, if the flag is set** (console edit or template change). Template hard-codes `"false"` (`template.yaml:189`); not a parameter. | **P0 (AUTHZ-01)** |
| 2 | `common/.../security/RecruiterAuthenticator.java:96-126` + `DevAuth.java:23-31` | `X-Dev-Recruiter-Id` only when (a) no `Authorization` header at all, (b) `ALLOW_DEV_RECRUITER_HEADER=true`, (c) `AWS_SAM_LOCAL=true`. A malformed/invalid bearer never falls back (`401`). Logs a warning when the flag is set without `sam local`. | Not through API Gateway: the JWT authorizer rejects any request without `Authorization` before the Lambda runs. Only a principal with `lambda:InvokeFunction` *and* `lambda:UpdateFunctionConfiguration` (to set both variables; `AWS_SAM_LOCAL` is not a Lambda-reserved name) could use it. | P2 hardening (DEV-01) |
| 3 | `DocumentManagementLambda/.../security/SigningSecretProvider.java:40` | `UPLOAD_TOKEN_SECRET_DEV` used instead of Secrets Manager only when `DevAuth.permitted` | Same as #2 | P2 (DEV-01) |
| 4 | `common/.../logging/RequestIds.java` and `Registration/.../RegistrationDataSource.java:64` | Read `AWS_SAM_LOCAL` only to choose a request id / suppress a warning | No auth effect | OK |
| 5 | `template.yaml:81` | CORS `AllowHeaders` includes `X-Dev-Student-Id` for the deployed API | Enables browser use of the dev header if #1 is ever enabled | P2 (DEV-02), removed by AUTHZ-01 |
| 6 | `template.yaml:141,189,297` | Flags hard-coded `"false"` in every function | Defaults are safe | OK |
| 7 | `env.json.example:23` (and the git-ignored `env.json`) | Flags `"true"` for local runs | Local only; git-ignored | OK |
| 8 | `.env.example:24,43-62` | `ALLOW_DEV_STUDENT_HEADER=false`, then a stray JSON block with `"true"` | Not consumed by SAM | P2 (DOC-01) |
| 9 | Tests (`RecruiterAuthenticatorTest`, `*AuthTest`, `*TokenAuthIntegrationTest`, `SecurityTest`) and Postman `RecruiterWorkspace` | Assert token precedence, refusal outside `sam local`, `401` for bad token + dev header | Test only | OK |

Valid Cognito tokens always take precedence; a malformed bearer never falls back to a dev header (verified in code and
tests). No other bypass (query-string identity, unsigned-JWT decode, `X-Forwarded-*` trust, IP allow-list) was found.

### C.6 Registration security (Phase 5)

| Rule | Student | Recruiter |
|---|---|---|
| Email only from trusted Cognito identity | Yes (UserInfo, lower-cased) | Yes |
| Email must be verified | Yes (`403 EMAIL_NOT_VERIFIED`) | Yes |
| Cognito subject unique | Yes (DB unique + pre-check) | Yes |
| Status | ACTIVE when `PHONE_VERIFICATION_REQUIRED=false` (template default `"false"`); PENDING otherwise (and when the variable is missing) | Always `PENDING` (`RecruiterAccountRules.REGISTRATION_STATUS`); client cannot set it (only `firstName`, `lastName`, `phoneNumber`, `acceptedTerms` are read) |
| Phone | `phone_verified=FALSE` always (`UserRepository` insert) | Same |
| Terms | `acceptedTerms` must be JSON `true`; `terms_accepted_at` (server clock) and `terms_version` (template parameter, **no default**) stored | Same |
| `manager_id` | n/a | `NULL` |
| Activation | n/a | Only the manual `UPDATE` in `Registration/db/recruiter-approval.sql`; no API can activate. No audit trail of who approved (DB-08). |
| Duplicate registration | `409 ACCOUNT_ALREADY_REGISTERED`, nothing written | Same |
| Existing student becoming recruiter | Refused (`ACCOUNT_TYPE_MISMATCH` / `ACCOUNT_IDENTITY_CONFLICT`), never converted | Same |
| Atomicity | One transaction (users + role row + identity), rollback on any failure; verified by MySQL ITs including concurrent duplicate attempts | Same |

### C.7 S3 and document security (Phase 9)

| Item | Finding |
|---|---|
| Presigned upload | PUT only, 300 s expiry, signs `Content-Type`, `Content-Length`, `x-amz-checksum-sha256` and the `doc-state` tag (`S3DocumentStorageService.java:80-105`). |
| Presigned download | **None exist** (no read/download API in V1). |
| Object key | Server-generated `students/{studentId}/{type}/{uuid}.{pdf,docx}`; client keys must match exactly. |
| Ownership | Recruiter must be ACTIVE and assigned on both calls; upload token binds recruiter/student/type/key/checksum. |
| Allowed types / size | PDF and DOCX by extension + MIME + magic bytes (`%PDF-`...`%%EOF`, ZIP local header + end-of-central-directory); resume max 5 MB (configurable 1-50); cover letters disabled. |
| Bucket | Public access block (all four), `BucketOwnerEnforced`, SSE-S3 (AES256), TLS-only bucket policy, `DeletionPolicy: Retain`, CORS PUT from `PortalOrigin` only. No versioning, no lifecycle (abandoned `pending` uploads accumulate), SSE-KMS not used. |
| Cross-student data | Global `UNIQUE(hashed_value)` means the same file uploaded for two students is refused (`409 DUPLICATE_DOCUMENT`/`DOCUMENT_UNAVAILABLE`), which also tells a recruiter that *some* student already has a file with that exact SHA-256 (DB-03). |
| Malicious files | Only format/magic-byte checks; PDFs with JavaScript or DOCX macros-by-relationship are not detected. No malware scanning (S3-01). Because no download/preview path exists, exposure today is limited to operators opening files manually; scanning becomes **required** before any download, preview or sharing feature, and is recommended before public launch (e.g. Amazon GuardDuty Malware Protection for S3). |
| Signing secret | Generated 64-char secret, read via Secrets Manager; no rotation configured (S3-02). |

The local development bucket configured in `env.json` (created manually, outside the template) has public access
blocked and SSE, but **no TLS-only bucket policy and no versioning**; it should not be reused for staging/production.

### C.8 Secrets and IAM review (Phase 8)

| Check | Result |
|---|---|
| Secrets in tracked files / history | None found. Scanned the full `git log -p` for AWS key ids, private keys and password assignments. One AWS access-key-id *pattern* in `DocumentManagementLambda/src/test/java/org/hmb/storage/S3DocumentStorageServiceTest.java:63` is a non-functional placeholder paired with the literal secret `fake-secret-key-for-unit-tests` (secret scanners may flag it; allow-list it). |
| `env.json` | Contains real local database credentials; it is git-ignored and was never committed (checked history). Not reproduced here. |
| `env.json.example`, `.env.example` | Placeholders only. |
| Postman collections | Secret variable placeholders; the literal tokens are fake. |
| Secrets in logs | None found. `BearerToken.toString()` and `AccountProfile.toString()` are redacted (`common/.../security/BearerToken.java:11`, `common/.../model/AccountProfile.java:28`); Registration logs duplicate-key *index names*, never values (`Registration/.../service/DatabaseFailures.java:85-93`). The DB password is passed to Hikari separately (`DatabaseConfig.java:67`), but the optional truststore password is appended to the JDBC URL (`DatabaseConfig.java:98-101`), where a driver error or debug log could expose it (SEC-02, P2). |
| Wildcard IAM | None in function policies. `s3:*` appears only in the **Deny** insecure-transport statement. |
| `cognito-idp:AdminGetUser` | Registration only, scoped to the one user-pool ARN. Required (Google-federation proof). No other Cognito admin actions. |
| `secretsmanager:GetSecretValue` | Document: the signing secret only. Registration: the DB secret ARN only (conditional). Recruiter/Student: none (and they have no secret support, INF-02). If the DB secret uses a customer-managed KMS key, `kms:Decrypt` on that key is missing. |
| S3 | Document only: `PutObject`, `PutObjectTagging`, `GetObject` on `students/*`, `ListBucket` on the bucket. `ListBucket` is broader than needed (`HeadObject` needs only `GetObject`; `ListBucket` only changes 403-vs-404 semantics) - acceptable, P2 to restrict with `s3:prefix`. No `DeleteObject`. |
| CloudWatch Logs | SAM basic execution role (create group/stream, put events). |
| VPC permissions | Not present; `AWSLambdaVPCAccessExecutionRole` (ENI management) is needed once `VpcConfig` is added (SAM adds it automatically when `VpcConfig` is set). |
| Cross-account | None. |
| Hard-coded account ids / ARNs | None; ARNs use `${AWS::AccountId}`/`${AWS::Partition}`. Environment-specific defaults: `CognitoUserPoolId`, `CognitoAppClientId`, `CognitoDomain` default to the real dev pool (`template.yaml:19-33`) and `PortalOrigin` defaults to localhost. |
| Database principal | All four local Lambda configurations connect as the MySQL `root` account (IAM-01). |
| Deployer identity | AWS CLI on this machine uses an IAM **user** with long-lived access keys (IAM-02, verification of its permissions was out of scope). |

### C.9 Error handling and logging (Phase 11)

| Check | Result |
|---|---|
| Status codes | Consistent: 400/401/403/404/405/409/413/415 (Registration)/500/503; `503` for DB and JWKS outages with "safe to retry" semantics. |
| Stack traces / SQL in responses | None: handlers map exceptions to fixed messages; `DatabaseOutageTest`/`RecruiterDatabaseOutageTest` and handler tests assert this. |
| Tokens / passwords in logs | None found (see C.1 #14, C.8). |
| PII in logs | Only internal numeric ids (`user_id`, `student_id`, `recruiter_id`) and outcomes; no names, emails, phones. Log groups for 3 functions never expire (OPS-01). |
| Request ids | Registration returns `requestId` in error bodies; Recruiter and Document log it but do not return it; Student Lambda has no request id at all (ERR-02). |
| Cognito outage | JWKS failure -> `503` (`JwtAccessTokenVerifier.java:132-134`); UserInfo 3 s connect / 5 s request (`CognitoUserInfoClient.java:29-30`) and AdminGetUser 8 s total (`CognitoAdminUserLookup.java:51-56`) -> `503`. |
| Database outage | Hikari `connectionTimeout` 5 s, `initializationFailTimeout=-1` (init never fails), mapped to `503`. |
| Retries | The recruiter application transaction is re-run on deadlock, max 3 attempts (`ApplicationService.java:30-31,83-91`); registration resolves duplicate-key races by re-reading. No blind retries of non-idempotent work. |
| Body limits | Registration 4 KB. Recruiter and Document handlers have **no explicit limit** (API Gateway allows up to 10 MB; Jackson default stream constraints apply) (SEC-03). |
| Envelope differences | See B.5 and ERR-01. No breaking change recommended during the audit. |

---

## D. Infrastructure Findings

### D.1 Current AWS state (read-only CLI, `us-east-2`)

| Resource | Observed |
|---|---|
| CloudFormation stacks / Lambda functions / API Gateway HTTP APIs | None |
| RDS / Aurora instances or clusters | None |
| Secrets Manager secrets | None |
| VPC | Default VPC only (no private subnets, NAT gateways or VPC endpoints created for this project) |
| S3 | A manually created development document bucket (public access blocked, SSE-S3, no TLS-only policy, no versioning) |
| Cognito user pool `us-east-2_F582IgoSY` | ESSENTIALS tier, deletion protection on, MFA **off**, self sign-up **allowed**, sign-in by email **and phone**, auto-verify email and phone, allowed first-auth factors include **PASSWORD and SMS_OTP**, no Lambda triggers, no threat protection |
| App client `5rgjoisv64qhe70oulfla17id9` | Public client (no secret), authorization-code grant, scopes `openid email`, IdPs `COGNITO` and `Google`, callback `http://localhost:5173/auth/callback` only, logout `http://localhost:5173/` only, auth flows `ALLOW_REFRESH_TOKEN_AUTH`, `ALLOW_USER_AUTH`, `ALLOW_USER_PASSWORD_AUTH`, access/ID token 60 min, refresh token 5 days, token revocation on, prevent-user-existence-errors on, refresh-token rotation off |
| Deployer credentials | An IAM user with long-lived access keys (name intentionally not recorded) |

### D.2 Database location and reachability (Phase 6)

* The only database is a **local MySQL on the developer's Windows machine** (`127.0.0.1`, MySQL 26.7.0,
  `require_secure_transport=OFF`, `max_connections=151`). A deployed Lambda cannot reach it. **There is no
  AWS-hosted MySQL, no chosen provider, and no network design** (INF-01).
* Recommended target: Amazon RDS for MySQL (or Aurora MySQL) in private subnets, Multi-AZ for production, automated
  backups, TLS required, `lower_case_table_names=0` (default on Linux) - which makes the schema name case-sensitive
  (DB-01).

**Networking prerequisites (all missing):**

| Need | Why | Option |
|---|---|---|
| VPC with >=2 private subnets (2 AZs) | Lambdas must run in the VPC to reach a private RDS | New VPC or dedicated subnets; add `VpcConfig` (SubnetIds, SecurityGroupIds) to the four DB-using functions |
| Security groups | Lambda SG egress 3306 to DB SG; DB SG ingress 3306 **only from the Lambda SG** | No `0.0.0.0/0` on 3306 |
| HTTPS egress to Cognito | `JwtAccessTokenVerifier` downloads the JWKS from `cognito-idp.us-east-2.amazonaws.com`; Registration calls the **hosted UI domain** `/oauth2/userInfo` (no VPC endpoint exists for the hosted domain) | **NAT gateway** (one per AZ for production) is required |
| Secrets Manager | DB secret (Registration), signing secret (Document) | NAT, or interface endpoint `com.amazonaws.us-east-2.secretsmanager` |
| Cognito IdP API (`AdminGetUser`) | Registration | NAT, or interface endpoint `cognito-idp` (endpoint does not cover JWKS/hosted domain in all cases, so NAT is still needed) |
| S3 | Document Lambda `HeadObject`/tagging and presigning | Gateway endpoint for S3 (free) or NAT; presigning itself needs no network |
| CloudWatch Logs | Lambda service writes logs outside the VPC | No endpoint required |
| DNS | Private RDS endpoint resolution | VPC DNS support/hostnames enabled |
| IAM for ENIs | Lambda in VPC | SAM adds `AWSLambdaVPCAccessExecutionRole` when `VpcConfig` is set |

### D.3 Database credentials and TLS

| Function | Credential source supported by code | Template today | TLS (`DB_SSL_MODE`) |
|---|---|---|---|
| Registration | Secrets Manager (`DB_SECRET_ARN`, `username`/`password`; host/port/dbname from env first, secret as fallback) or plain env | `DB_SECRET_ARN` parameter (default `""`), `DB_HOST`/`DB_PORT` `""`, `DB_NAME` hard-coded `hiremebot_db_V1` (`template.yaml:387`), which overrides the secret's `dbname` | Code default `VERIFY_IDENTITY`; template `""` -> default |
| Recruiter | **Env vars only** (`common/.../DatabaseConfig.java`) | All `DB_*` `""`, no parameters (`template.yaml:122-135`) | Template `""` -> `VERIFY_IDENTITY`; local `env.json` `REQUIRED` |
| Student | **Env vars only** | All `""` (`template.yaml:174-182`) | Local `env.json` **`DISABLED`** |
| Document | **Env vars only** | All `""` (`template.yaml:279-287`) | Local `env.json` `REQUIRED` |

Findings: INF-02 (no secret support for three functions, plaintext password in Lambda configuration would be the only
option); local SSL settings are inconsistent (Student `DISABLED`) but the deployed default `VERIFY_IDENTITY` is
correct. `VERIFY_IDENTITY` against RDS needs the RDS CA bundle: Java's default truststore may not contain the RDS
root CA, so `DB_TRUSTSTORE_URL` (a file packaged in the artifact or a layer) or `REQUIRED` plus a documented decision
is needed - **NOT VERIFIED** against a real RDS endpoint.

### D.4 Connection management

| Item | Finding |
|---|---|
| Pool | HikariCP per Lambda container: `maximumPoolSize=2`, `minimumIdle=0`, `connectionTimeout=5 s`, `validationTimeout=3 s`, `idleTimeout=60 s`, `maxLifetime=5 min`, `initializationFailTimeout=-1` (`DatabaseConfig.java:69-77`); Registration has its own lazily created pool. |
| Reuse across warm invocations | Yes (static/lazy singletons). |
| Timeouts | Connect 5 s, socket 15 s (`DatabaseConfig.java:83-104`). No per-transaction timeout; MySQL `innodb_lock_wait_timeout` applies. Lambda timeout 20 s. |
| Leaks | All JDBC usage is try-with-resources (inspected). |
| Deadlocks | Recruiter application transaction retries (3 attempts); registration maps duplicate keys. |
| Concurrency pressure | Worst case = 2 connections x concurrent containers x 4 functions. With the default account concurrency (1000) this can exceed any small RDS instance's `max_connections` (INF-03). Mitigation: reserved concurrency per function and/or RDS Proxy (RDS Proxy also smooths Lambda connection churn and supports IAM auth). |

### D.5 SAM / CloudFormation audit (Phase 7)

`sam validate --lint`: **valid, no lint findings**. `sam build`: **succeeded** (artifacts: Document 17.8 MB, Registration
17.0 MB, Recruiter 8.5 MB, Student 7.7 MB).

| Area | Finding | ID |
|---|---|---|
| Runtime / handlers / CodeUri | `java21`, x86_64, handlers and CodeUri correct for all four functions | OK |
| Build of `common` | `sam build` builds each function module separately; `hiremebot-common` is resolved from the **local `~/.m2` repository**, not from `common/` source. A stale installed jar silently ships old authentication code unless `mvn install` of `common` runs first. | OPS-03 |
| Memory / timeout | 512 MB / 20 s (Registration 1024 MB). Not measured (no load test). Java cold starts at 512 MB are typically several seconds; SnapStart not enabled. | PERF-01 |
| Authorizers | 8 of 9 routes use `CognitoAccessToken` with scope `openid`; Student route has none. | AUTHZ-01 |
| Authorizer config | Issuer `https://cognito-idp.${AWS::Region}.amazonaws.com/${CognitoUserPoolId}`, audience `CognitoAppClientId` (API Gateway matches `client_id` for access tokens). Correct. | OK |
| CORS | Single `PortalOrigin` (default `http://localhost:5173`), methods GET/POST/OPTIONS, headers `Content-Type, Authorization, X-Dev-Student-Id`, `MaxAge: 600`, credentials not enabled (bearer tokens, not cookies) (`template.yaml:71-82`). S3 bucket CORS also uses `PortalOrigin` (`:228`). | CORS-01, DEV-02 |
| Environment-specific values | Real dev Cognito ids as defaults; `DB_NAME` hard-coded; `TermsVersion` has no default (deployment fails without it, which is intended); no `Stage`/environment parameter, no resource-name suffixes; `samconfig.toml` has only a default environment. | OPS-02 |
| Concurrency / throttling | No reserved concurrency, no `DefaultRouteSettings` throttling on the HTTP API. | RATE-01, INF-03 |
| WAF | Not configured. HTTP APIs cannot be associated with AWS WAF directly; it would need CloudFront in front, or a REST API. | RATE-01 |
| Logs | API access log group + Registration log group: 30 days. Recruiter/Student/Document: implicit log groups, **never expire**. | OPS-01 |
| Alarms / dashboards | None. | OPS-01 |
| Versions / aliases | None (no `AutoPublishAlias`, no gradual deployment). | OPS-02 |
| Outputs | Route URLs, bucket name, function ARNs and IAM role ARNs (`template.yaml:464-515`). | OK |
| Description | Still "Sample SAM Template" boilerplate. | DOC-01 |

### D.6 Cognito configuration risks

| ID | Finding | Impact |
|---|---|---|
| COG-01 | Callback/logout URLs are localhost only | Vercel portals cannot complete sign-in until the owner adds production URLs (exact HTTPS URLs, no wildcards). |
| COG-02 | User pool allows self sign-up with password, SMS OTP and phone username; `ALLOW_USER_PASSWORD_AUTH` on the app client; MFA off; no threat protection | Not an authentication bypass for this backend (native users are refused at registration and on every recruiter request), but it lets anyone create native users and trigger SMS sends (cost/abuse), and widens the attack surface. Recommend: Google as the only IdP on the app client, remove password/SMS flows, disable self-service native sign-up if the hosted UI is not needed for it, consider threat protection. |
| COG-03 | Refresh-token rotation off, 5-day refresh tokens | Stolen refresh token usable for 5 days unless revoked. |
| SEC-01 | Access tokens 60 min, no revocation check | See C.3. |

### D.7 CORS for two portals

One `PortalOrigin` cannot serve separate Student and Recruiter Vercel deployments (different origins). HTTP API
`CorsConfiguration.AllowOrigins` accepts a list, so the fix is a second parameter (e.g. `StudentPortalOrigin`,
`RecruiterPortalOrigin`) or a comma-delimited list parameter, and the same list in the S3 bucket CORS (only the
recruiter origin needs S3 PUT). Do **not** use `*`. Vercel preview URLs change per deployment; use a fixed staging
domain instead of allowing preview wildcards.

### D.8 Performance and scalability (Phase 12)

No load tests exist, so **capacity is NOT VERIFIED**.

| Area | Observation |
|---|---|
| Cold start | Java 21 + AWS SDK + Jackson + Nimbus, 8-18 MB artifacts, no SnapStart: expect multi-second cold starts (estimate, not measured). SnapStart for Java 21 is a low-risk option (be careful with Hikari pools and JWKS caches initialised before the snapshot). |
| JWKS caching | Cached per container with rate-limited refresh. Good. |
| Cognito calls | Registration: UserInfo + AdminGetUser only on registration; `/auth/me` paths verify locally. Good. |
| Queries | Student statistics and today's list rely on `applications(student_id, ...)`; the DDL has `UNIQUE(student_id, job_link_hash)` but no `(student_id, applied_at)` index (DB-07). No N+1 patterns found (applications + documents + questions inserted in one transaction; listings are single queries). |
| Pagination | Recruiter students: unbounded list (bounded in practice by assignments) (PERF-02). Student applications: today's rows only. |
| Transactions | Short; no external calls inside DB transactions (Registration does Cognito calls before opening the transaction). |
| Rate limits | None at API Gateway (RATE-01). |

### D.9 Production operations (Phase 15)

| Item | Status | Required before |
|---|---|---|
| CI/CD pipeline | Missing (no `.github/workflows`, no buildspec) | Staging |
| Build artifact integrity / reproducibility | Partially implemented (Maven + SAM build work; `~/.m2` common jar, JAVA_HOME must be 21, no enforcer, no lock of plugin versions beyond the POM) | Staging |
| Deployment approvals | Missing | Production |
| Staging environment | Missing (no stage parameter, single `samconfig` env) | Staging |
| Environment-specific configuration | Partially implemented (parameters exist for Cognito, origin, terms; DB config missing) | Dev |
| Lambda versions / aliases | Missing | Recommended (production) |
| CloudWatch logging | Partially implemented (3 log groups without retention; no structured JSON logs) | Staging |
| Metrics / alarms (API 4xx/5xx, Lambda errors/throttles/duration, DB connections/CPU/storage) | Missing | Production |
| Database availability alarms | Missing (no database) | Production |
| Secrets rotation | Missing (signing secret, future DB secret) | Production (DB), recommended (signing secret) |
| Backups / restore | Missing (local DB only; no documented restore test) | Production |
| Incident response / rollback / runbook | Missing (only the recruiter-approval SQL procedure exists) | Production |
| Health checks | Not applicable for Lambda; synthetic canary recommended | Recommended |
| WAF / throttling / rate limiting | Missing | Production (throttling); WAF recommended |
| Cost controls (budgets, concurrency caps, SMS spend limits) | Missing | Production |
| Data retention | Partially implemented (S3 `Retain`, no lifecycle; logs partly unlimited) | Production |
| Privacy / deletion procedure | Missing (soft-delete column exists; no process for S3 objects, Cognito user, logs) | Production |

---

## E. Database Findings

Sources: repository DDL `hiremebot_db_V1.sql` (staged, uncommitted), `Registration/db/recruiter-approval.sql`, the
repositories' SQL, and **read-only** queries against the local `hiremebot_db_v1` (schema metadata and aggregate counts
only; no row contents were read into this report).

### E.1 Schema summary

| Table | Keys / constraints (DDL line) | Notes |
|---|---|---|
| `users` | PK `user_id`; `UNIQUE(email)` (`:11`); `account_status ENUM('PENDING','ACTIVE','SUSPENDED') DEFAULT 'PENDING'` (`:17`); `terms_accepted_at`, `terms_version` nullable (`:19-20`); `is_deleted` (`:22`) | **No `UNIQUE(first_name, last_name)`** (owner's rule, see E.2). `phone VARCHAR(16) NOT NULL`, not unique. Timestamps are `TIMESTAMP` (year-2038 limit). |
| `user_identities` | `uq_cognito_sub`, `uq_user_provider (user_id, provider)`, FK -> users `ON DELETE CASCADE` (`:40-44`) | `provider ENUM('GOOGLE','COGNITO')` |
| `managers`, `recruiters`, `students` | PK = FK to `users` (`:47-63`); `recruiters.manager_id` nullable FK `RESTRICT` | Nothing prevents one `user_id` from being in both `students` and `recruiters` at DB level (role exclusivity is application-only, DB-09). |
| `recruiter_student_assignments` | `UNIQUE(recruiter_id, student_id)`, `UNIQUE(student_id)` (`:74-75`), FKs | One recruiter per student. `assigned_by` must be a manager. |
| `applications` | `UNIQUE(student_id, job_link_hash)` (`:108`); composite FK `fk_application_assignment (recruiter_id, student_id)` -> assignments `ON DELETE RESTRICT ON UPDATE CASCADE` (`:111-115`); `applied_at DATETIME` | `ON UPDATE CASCADE` + `UNIQUE(student_id)` means reassigning a student by `UPDATE recruiter_id` silently **rewrites the recruiter on all historical applications** (DB-04). No `(student_id, applied_at)` index (DB-07). |
| `object_store_entries` | `UNIQUE(object_key)`, **global `UNIQUE(hashed_value)`** (`:129-130`) | The same file cannot be stored for two students (DB-03). |
| `application_documents` | FKs to application and objects `ON DELETE CASCADE`, `UNIQUE(application_id)` (`:142-148`) | DB does not enforce that the resume/cover-letter object belongs to the application's student or has the right `document_type` (DB-05; enforced in `ApplicationService`). |
| `application_questions` | FK to application | |
| `otp_verifications` | (`:163-175`) | **Unused by any code** (DB-06). |
| All | `AUTO_INCREMENT = 1000000000` (`:177-183`) | |

**Character set / collation:** `CREATE DATABASE hiremebot_db_V1;` (`:1`) has no explicit charset; the local server
default is `utf8mb4` / `utf8mb4_0900_ai_ci` (accent- and case-insensitive). On RDS the parameter-group default
applies, which may differ (DB-06). **Database name:** the DDL and the template use `hiremebot_db_V1`, the local server
stores it as `hiremebot_db_v1` because Windows forces `lower_case_table_names=1`. On Linux RDS (`=0`) the names are
case-sensitive, so the database must be created with exactly the name every function uses (DB-01).

**Migrations:** there is no migration tool (Flyway/Liquibase) and no versioned migration history; the single DDL file
is the only source of truth, and it is staged but not committed (DB-06).

**Live local database vs DDL:** schema matches the DDL except where noted; row counts: users 5, students 2, recruiters
2, identities 3, applications 2, objects 2 (unchanged by this audit).

### E.2 The `UNIQUE (first_name, last_name)` discrepancy (owner's business rule)

| Item | Finding |
|---|---|
| Repository DDL | Absent (`hiremebot_db_V1.sql:5-26`). |
| Local `hiremebot_db_v1` | Absent (verified via `information_schema.statistics`). |
| Application code | Ready for it: `UniqueKeyCatalog` recognises a unique index on `(first_name, last_name)` by its columns, whatever it is called (`Registration/.../repository/UniqueKeyCatalog.java:22,36`), and maps it to `409 NAME_ALREADY_REGISTERED` (`RegistrationException.java:115-117`, `AccountRegistrar.java:139-153`). `FullNameConstraintMySqlIntegrationTest` and `RecruiterFullNameConstraintMySqlIntegrationTest` create the index in their scratch schema and pass. |
| Consequence of the gap | The owner's rule is **not enforced**: two users with the same first and last name can register today. |
| Consequence of adding it | Because the collation is accent/case-insensitive, "Jose Smith" and "josé smith" collide; genuinely different people with common names are refused; and the `409` lets a signed-in Google user probe whether a full name is registered (low sensitivity, but note it for privacy review). Applies across students **and** recruiters (one `users` table). |
| Existing duplicates | **0** duplicate `(first_name, last_name)` groups in the local database (checked with an aggregate `GROUP BY ... HAVING COUNT(*) > 1`; no names read). |

Proposed migration (**not applied; requires owner approval**; run on each environment's database separately):

```sql
-- 1. Pre-check: must return zero rows. If not, the owner decides how to resolve each pair before continuing.
SELECT first_name, last_name, COUNT(*) AS n
FROM users
GROUP BY first_name, last_name
HAVING COUNT(*) > 1;

-- 2. Online add (InnoDB builds unique secondary indexes in place; brief metadata lock).
ALTER TABLE users
  ADD CONSTRAINT uq_users_full_name UNIQUE (first_name, last_name),
  ALGORITHM = INPLACE, LOCK = NONE;

-- Rollback, if required:
-- ALTER TABLE users DROP INDEX uq_users_full_name;
```

Also add the same constraint to `hiremebot_db_V1.sql` so new environments match.

### E.3 Least-privilege database accounts (proposal for IAM-01)

All local functions connect as MySQL `root`. Proposed per-function users (names illustrative; owner to create):

| Account | Grants on `hiremebot_db_V1` |
|---|---|
| `hmb_registration` | `SELECT, INSERT` on `users`, `students`, `recruiters`, `user_identities`; `SELECT` on `managers` |
| `hmb_recruiter` | `SELECT` on `users`, `user_identities`, `students`, `recruiters`, `managers`, `recruiter_student_assignments`, `object_store_entries`; `SELECT, INSERT` on `applications`, `application_documents`, `application_questions` |
| `hmb_student` | `SELECT` on `users`, `students`, `applications` (current queries: `StudentRepository.java:26-27`, `StudentApplicationRepository.java:36`, `StudentStatisticsRepository.java:42`); after AUTHZ-01 also `SELECT` on `user_identities`, `recruiters`, `managers` (`AccountProfileRepository.java:40-47`) |
| `hmb_documents` | `SELECT` on `users`, `user_identities`, `recruiters`, `managers`, `students`, `recruiter_student_assignments`; `SELECT, INSERT` on `object_store_entries` |
| Operator (approvals, assignments) | Separate named human account with `UPDATE` on `users.account_status`, `INSERT/UPDATE` on assignments; never used by Lambdas |

Every account should be created with `REQUIRE SSL` and its password stored in Secrets Manager. Validate the grants by
running the MySQL integration suites under these users in staging.

### E.4 Data-integrity and sensitive-data observations

* PII stored: names, email, phone (students and recruiters), job applications, resume objects (S3). Encryption at rest
  depends on the future RDS configuration (enable storage encryption) and S3 SSE (already on).
* Soft delete (`is_deleted`) is honoured by every query inspected; there is no hard-delete/erasure procedure covering
  MySQL rows, S3 objects and the Cognito user (OPS privacy item).
* Recruiter approval (`Registration/db/recruiter-approval.sql`) is a manual `UPDATE` with no record of who approved or
  when, beyond `updated_at` (DB-08).

---

## F. Testing Findings

### F.1 Results of this audit's run

Command (from the repository root, `JAVA_HOME` = Corretto 21, MySQL integration enabled, `DB_NAME` pointed at the
scratch schema `hmb_audit_9e3b07f8`, which was created from the repository DDL and dropped afterwards):
`mvn clean verify`. Result: **BUILD SUCCESS**.

| Module | Tests run | Failures | Errors | Skipped | Previously reported |
|---|---:|---:|---:|---:|---:|
| common | 27 | 0 | 0 | 0 | 27 |
| RecruiterLambda | 103 | 0 | 0 | 0 | 103 |
| StudentLambda | 131 | 0 | 0 | 0 | 131 |
| DocumentManagementLambda | 185 | 0 | 0 | 0 | 185 |
| Registration | 287 | 0 | 0 | 1 | 285 |
| **Total** | **733** | **0** | **0** | **1** | **731** |

* The two extra Registration tests are `CognitoLiveSmokeTest` (2 tests): one fetched the **real** user pool's JWKS and
  confirmed a forged token is rejected (passed); the other, which needs a real access token, was **skipped** (no
  authorized token available).
* Registration MySQL tests create their own `hmb_reg_it_*` schemas; Recruiter/Student/Document MySQL tests write to
  whatever `DB_NAME` is configured, so **they must always be pointed at a scratch schema** (they are not destructive to
  other rows, but they do insert fixtures).
* `sam validate --lint`: valid. `sam build`: success. `sam local start-api`: **not run** (Docker daemon not running;
  not started by the audit).

### F.2 Coverage matrix

Legend: U = unit/mocked, J = JVM handler test with locally signed RS256 tokens, M = MySQL integration (scratch schema),
L = live AWS, - = none.

| Area | common | Recruiter | Student | Document | Registration | Gap |
|---|---|---|---|---|---|---|
| Authentication (JWT validity, alg, iss, client, token_use, exp, scope) | U, J | J (`RecruiterLambdaHandlerAuthTest`, `RecruiterTokenAuthIntegrationTest`) | **- (no token auth exists)** | J (`DocumentLambdaHandlerAuthTest`, `DocumentTokenAuthIntegrationTest`) | U, J, L (JWKS only) | Real Google-issued token never tested (TEST-01); deployed authorizer never exercised (TEST-02) |
| Dev-header gating | U | J | U (flag on/off only) | J | n/a | Student: no test that the header is refused when deployed, because it is not |
| Authorization (role, status, assignment) | U | J, M | U (deleted only) | J, M | U, M | Student cross-account access untestable until AUTHZ-01 |
| Registration (student/recruiter) | - | - | - | - | U, M, end-to-end local | - |
| Account lookup (`/auth/me`) | - | - | - | - | U, M | - |
| DB transactions / rollback | - | M | - | M | M | - |
| Recruiter assignments | - | M | - | M | - | Reassignment (DB-04) not tested |
| Applications | - | U, M | U, M | - | - | Student account status not tested |
| Documents | - | M (attach) | - | U, M, S3 presign (no network) | - | No real S3 upload test; no malicious-file corpus |
| Account status transitions (PENDING->ACTIVE->SUSPENDED) | U | J, M | - | J | M (`RecruiterManualApprovalMySqlIntegrationTest`) | Student suspension (AUTHZ-01) |
| Duplicate accounts / identities / names | - | - | - | M (duplicate documents) | M (incl. full-name index) | - |
| Concurrency | - | M (concurrent duplicate applications) | - | M | M (concurrent registration) | No load test (PERF-01) |
| Database outage | - | U | U | U | U (`DatabaseOutageTest`, `RecruiterDatabaseOutageTest`) | - |
| Infrastructure configuration | - | - | - | - | - | No `cfn-guard`/policy tests; template checked only by `sam validate --lint` |

### F.3 Real Cognito token testing: NOT VERIFIED

No authorized, non-sensitive test session was available to the audit, and no token was extracted from any browser,
log or account. **All token tests above are JVM tests with locally generated RS256 keys**, plus one live JWKS fetch.
The following plan should be executed by the owner (or with an owner-provided test account) against `sam local` and
then against the deployed development stack.

**Preparation (safe):**

1. Create three Google test accounts that contain no real personal data (e.g. `hmb.test.recruiter.active@...`,
   `...recruiter.pending@...`, `...student@...`). Register them through the portals or Postman collections in
   `Registration/postman/`.
2. In the **staging/development** database only, approve one recruiter (`Registration/db/recruiter-approval.sql`),
   leave one `PENDING`, and later set one to `SUSPENDED`. Assign one test student to the active recruiter.
3. Obtain access tokens through the hosted UI (authorization code + PKCE) into a Postman **secret** variable
   (`cognitoAccessToken`, current value only, never exported). Never paste tokens into tickets, chats or reports.
4. For deployed tests, set `{{baseUrl}}` to the stack's API URL; for local tests, `http://127.0.0.1:3000`.

**Expected results** (deployed column = API Gateway authorizer in front; local = Lambda only):

| # | Scenario | Request | Expected (deployed) | Expected (`sam local`) |
|---|---|---|---|---|
| 1 | ACTIVE recruiter, real token | `GET /api/v1/recruiter/students`; `GET /api/v1/recruiters/auth/me` | `200` list of own students; `200 nextStep=RECRUITER_DASHBOARD` | same |
| 2 | PENDING recruiter | `GET /api/v1/recruiter/students`; `POST /api/v1/documents/upload-url`; `GET /api/v1/recruiters/auth/me` | `403 {"success":false,"error":"FORBIDDEN","message":"Your recruiter account is waiting for approval"}`; auth/me `200 nextStep=APPROVAL_PENDING` | same |
| 3 | SUSPENDED recruiter (set after token issued) | same as #2, reusing the old token | `403 "Your recruiter account has been suspended"`; auth/me `403 code=ACCOUNT_SUSPENDED` | same |
| 4 | Student token on recruiter API | `GET /api/v1/recruiter/students`; `GET /api/v1/recruiters/auth/me` | `403 "This account is not a recruiter account"`; `403 code=ACCOUNT_TYPE_MISMATCH` | same |
| 5 | No token | any protected route | `401 {"message":"Unauthorized"}` (API Gateway) | `401 {"success":false,"error":"UNAUTHORIZED","message":"Authentication is required."}` (Registration: `"code":"UNAUTHORIZED"`) |
| 6 | Expired token (> 60 min old) | any protected route | `401 {"message":"Unauthorized"}` | `401 UNAUTHORIZED` |
| 7 | Forged token (signature byte changed) | any protected route | `401 {"message":"Unauthorized"}` | `401 UNAUTHORIZED` |
| 8 | Token from a different user pool | any protected route | `401 {"message":"Unauthorized"}` (issuer mismatch) | `401 UNAUTHORIZED` |
| 9 | Token from a different app client of the same pool | any protected route | `401 {"message":"Unauthorized"}` (audience/client_id mismatch) | `401 UNAUTHORIZED` |
| 9b | ID token instead of access token | any protected route | `403 {"message":"Forbidden"}` (no `openid` scope claim) | `401 UNAUTHORIZED` (`token_use`) |
| 10 | Cross-recruiter student | Active recruiter `POST /api/v1/recruiter/applications` / `POST /api/v1/documents/upload-url` with a `studentId` not assigned to them | `403 "Recruiter is not assigned to this student"` / `403` | same |
| 11 | Cross-student applications | `GET /api/v1/student/applications` with student A's token and `X-Dev-Student-Id` = B | **Currently: no authorizer; `403 "Student authentication is not configured"` for every caller. After AUTHZ-01: `200` with A's own data only; the header must be ignored.** | Currently: with the local flag on, returns B's data (demonstrates AUTHZ-01) - use only test accounts |
| 12 | Unauthorized document access | `POST /api/v1/documents/register` with an upload token issued to recruiter A, called by recruiter B; or a modified `objectKey` | `403 UPLOAD_TOKEN_INVALID "Upload authorization is not valid. Request a new upload URL."` | same |

Also verify CORS preflight (`OPTIONS` from each portal origin returns the origin; another origin gets no
`Access-Control-Allow-Origin`), and that API Gateway access logs show `authorizerError` for #5-#9.

---

## G. Dependency Findings

**Method:** the resolved Maven dependency set of every module (runtime and test) was checked against the **OSV.dev**
vulnerability database, and the advisory details were re-fetched from OSV while writing this report. OWASP
Dependency-Check / NVD and GitHub Dependabot were **not**
run (no NVD API key, no repository security settings access), so absence of an OSV advisory is **not proof** of absence
of vulnerabilities. No dependency was upgraded.

### G.1 Verified vulnerable dependencies

| Dependency (resolved) | Advisory (OSV / CVE) | Severity (OSV) | Fixed in | Applicable to this code? |
|---|---|---|---|---|
| `jackson-core` 2.17.2 | GHSA-p6pp-m3f8-5c89 / CVE-2026-89407 - ReDoS in `NumberInput.PATTERN_FLOAT` | High | 2.18.11 | **Possibly**: reachable when textual numbers are coerced while binding untrusted request bodies (`RecruiterLambdaHandler.java:276`, `DocumentLambdaHandler.java:260`). Bounded by API Gateway's 10 MB limit and Lambda timeout, but still a DoS vector. |
| `jackson-core` 2.17.2 | GHSA-7hhh-6rmp-j9qf / CVE-2026-89425 - unbounded error-token growth in `UTF8DataInputJsonParser` | High | 2.18.11 | Unlikely: code parses `String`/`byte[]`, never `DataInput`. |
| `jackson-core` 2.17.2 | GHSA-72hv-8253-57qq / CVE-2026-18401 and GHSA-r7wm-3cxj-wff9 / CVE-2026-68494 - async parser number-length bypass | Moderate / High | 2.18.6 / 2.18.8 | No: the non-blocking (async) parser is not used. |
| `jackson-databind` 2.17.2 | GHSA-cxp5-3px4-pw24 / CVE-2026-91777 - quadratic forward-reference completion | High | 2.18.11 | Unlikely: requires object-identity (`@JsonIdentityInfo`) handling; none found in main code. |
| `jackson-databind` 2.17.2 | GHSA-wv8q-qhhj-9h54 / CVE-2026-91776 - unknown raw type ids retained | High | 2.18.11 | No: polymorphic type handling not used (no `@JsonTypeInfo`, no default typing). |
| `jackson-databind` 2.17.2 | GHSA-j3rv-43j4-c7qm / CVE-2026-54512, GHSA-rmj7-2vxq-3g9f / CVE-2026-54513, GHSA-gx83-3vf8-gh7j / CVE-2026-83557 - PolymorphicTypeValidator bypasses | High / High / Moderate | 2.18.8 / 2.18.8 / 2.18.10 | No: no polymorphic typing. |
| `jackson-databind` 2.17.2 | GHSA-hgj6-7826-r7m5 / CVE-2026-54514, GHSA-vvgp-rfg2-7rr6 / CVE-2026-77310 - `InetSocketAddress`/`InetAddress` DNS resolution (SSRF) | Moderate | 2.18.8 / 2.18.9 | No: no such DTO fields. |
| `jackson-databind` 2.17.2 | GHSA-wjgm-6hv5-3cvf / CVE-2026-19032 - `Path` deserialization | Moderate | 2.18.10 | No: no `Path` fields. |
| `jackson-databind` 2.17.2 | GHSA-q4xh-88c3-wmh7 / CVE-2026-68497 - `Duration`/`XMLGregorianCalendar` number parse DoS | High | 2.18.10 | No: no such fields. |
| `jackson-databind` 2.17.2 | GHSA-3pjw-73gf-8qr5 / CVE-2026-59888 (`@JsonIgnore` on records + naming strategy), GHSA-5gvw-p9qm-jgwh / CVE-2026-59889 (`@JsonView` + `@JsonUnwrapped`), GHSA-5jmj-h7xm-6q6v / CVE-2026-54515 (case-insensitive properties) | Moderate | 2.18.8 / 2.18.9 / 2.18.9 | No: none of those features are configured. |
| `protobuf-java` 3.25.1 (transitive via `mysql-connector-j` 8.4.0) | GHSA-735f-pc8j-v9w8 / CVE-2024-7254 - recursion DoS | High | 3.25.5 | No: only used by MySQL X DevAPI, which the code does not use (classic JDBC on 3306). |

**Recommendation (DEP-01, P1):** move all Jackson artifacts to one consistent patched line (import
`com.fasterxml.jackson:jackson-bom` **2.18.11 or later** in the root `dependencyManagement`; 2.18 keeps Java 8+ API
compatibility). Upgrade risk: low-moderate (2.18 tightened some coercion and `StreamReadConstraints` defaults; also
check that the AWS SDK/Lambda libraries do not pin an older Jackson). Regression tests: full `mvn clean verify` with
MySQL ITs, plus the request-parsing suites (`RecruiterRequestTest`, `RegistrationRequestParserTest`,
`DocumentLambdaHandlerTest`), JWT tests (Nimbus does not depend on Jackson) and a `sam local` smoke test.
Independently, set explicit `StreamReadConstraints` / body-size limits (SEC-03).

**Recommendation (DEP-02, P2):** exclude `com.google.protobuf:protobuf-java` from `mysql-connector-j` (or pin 3.25.5+).
Risk: none for classic JDBC; verify with the MySQL ITs.

### G.2 Other dependency observations

| Item | Finding | ID |
|---|---|---|
| `nimbus-jose-jwt` 10.10 | No OSV advisory found. Declared `<optional>true</optional>` in `common`, so every Lambda that uses `RecruiterAuthenticator` must declare it itself (Recruiter, Document, Registration do; **Student does not** and must add it when migrating, AUTHZ-01). Verify the version against the Nimbus changelog before production. | DEP-03 |
| `mysql-connector-j` 8.4.0, HikariCP 5.1.0, AWS SDK 2.57.7, `aws-lambda-java-core` 1.2.2, SLF4J 2.0.13 | No OSV advisories found. | - |
| `aws-lambda-java-events` 3.11.0 | Old (brings `joda-time` 2.6). No advisories found; upgrade is optional. | DEP-03 |
| `slf4j-simple` | Plain-text logging to stderr; no structured JSON/MDC (request id must be added manually). | OPS-01 |
| Build reproducibility | `maven.compiler.source/target=21` instead of `maven.compiler.release`; no `maven-enforcer-plugin` (Java/Maven version, dependency convergence); default `JAVA_HOME` on this machine is JDK 11 (build fails unless switched); shade plugin used, no SBOM. | OPS-03 |
| Download sources | Maven Central only (no custom repositories in any POM). | OK |
| Shaded artifacts | Contain only expected libraries (AWS SDK clients, Jackson, Nimbus, HikariCP, MySQL driver, SLF4J); test dependencies are `test`-scoped. Artifact size 7.7-17.8 MB. | OK |

---

## H. Deployment Configuration

### H.1 SAM parameters

| Parameter | Current default | Required action |
|---|---|---|
| `PortalOrigin` | `http://localhost:5173` | Replace with the exact HTTPS origin(s); split into Student and Recruiter origins (CORS-01) |
| `CognitoUserPoolId` | Real dev pool | Pass explicitly per environment; use a separate pool (or at least app client) for production |
| `CognitoAppClientId` | Real dev client | Same |
| `CognitoDomain` | Real dev hosted domain | Same |
| `TermsVersion` | **None (must be supplied)** | Owner supplies the published terms version |
| `PhoneVerificationRequired` | `"false"` | Keep `false` for V1 (no SMS verification exists) |
| `DatabaseSecretArn` | `""` | Must be the DB secret ARN in any AWS environment |
| **Missing:** `Environment`/`Stage` | - | Add; use in resource names, tags, and `samconfig.toml` environments |
| **Missing:** DB host/port/name/SSL parameters and secret ARNs for Recruiter, Student, Document | - | Add after INF-02 |
| **Missing:** `VpcSubnetIds`, `LambdaSecurityGroupId` | - | Add after INF-01 |
| **Missing:** reserved concurrency / throttling parameters | - | Add (INF-03, RATE-01) |

### H.2 Environment variables per function

| Variable | Registration | Recruiter | Student | Document | Notes |
|---|---|---|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | yes (`DB_NAME` hard-coded) | `""` | `""` | `""` | INF-02, DB-01 |
| `DB_USERNAME`, `DB_PASSWORD` | from secret | `""` (plaintext only) | `""` | `""` | INF-02, IAM-01 |
| `DB_SECRET_ARN` | parameter | n/a (unsupported) | n/a | n/a | INF-02 |
| `DB_SSL_MODE`, `DB_TRUSTSTORE_*` | default `VERIFY_IDENTITY` | same | same | same | INF-04 |
| `COGNITO_REGION`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID` | yes | yes | **no (needed for AUTHZ-01)** | yes | |
| `COGNITO_DOMAIN` | yes | - | - | - | userInfo endpoint |
| `TERMS_VERSION`, `PHONE_VERIFICATION_REQUIRED` | yes | - | - | - | |
| `ALLOW_DEV_RECRUITER_HEADER` | - | `"false"` | - | `"false"` | Never `true` in AWS |
| `ALLOW_DEV_STUDENT_HEADER` | - | - | `"false"` | - | **Must never be `true` in AWS** (AUTHZ-01) |
| `DOCUMENTS_BUCKET`, `UPLOAD_SIGNING_SECRET_ID` | - | - | - | yes | |
| `UPLOAD_URL_EXPIRY_SECONDS`, `UPLOAD_TOKEN_TTL_SECONDS`, `DOCUMENT_RESUME_*`, `DOCUMENT_COVER_LETTER_*` | - | - | - | optional (defaults 300 s, 1800 s, resume on 5 MB, cover letter off) | |
| `APP_TIMEZONE` | - | - | optional (default America/Chicago) | - | |

### H.3 Frontend (Vercel) configuration the backend expects (frontend not verified)

* API base URL = the deployed HTTP API URL (stack output).
* Cognito: user pool id, app client id, hosted domain, redirect URI exactly matching an app-client callback URL,
  scopes `openid email`, authorization code + PKCE.
* Send `Authorization: Bearer <access token>` (not the ID token) to every route; refresh before expiry.
* Handle three error envelopes (B.5) and route by `nextStep`.

---

## I. Prioritized Remediation Backlog

Type legend: **CD** confirmed defect, **CG** configuration gap, **MI** missing infrastructure, **TG** test coverage
gap, **DD** documentation discrepancy, **PR** potential risk requiring verification.

| ID | Priority | Finding | Evidence | Affected files | Recommended fix | Verification |
|---|---|---|---|---|---|---|
| AUTHZ-01 | **P0** | (CD) Student dashboard API has no authentication: no authorizer, identity from client header `X-Dev-Student-Id` when `ALLOW_DEV_STUDENT_HEADER=true` (no `AWS_SAM_LOCAL` gate), otherwise `403` for all; no `account_status` check. Exposes any student's profile and applications if the flag is enabled; feature unusable if not. | `template.yaml:162-195` (no `Auth` on the event), `:81`, `:189`; `StudentLambdaHandler.java:38-46,65-68,138-160`; `StudentRepository.java:19-30`; `docs/API.md:26-28,903` | `template.yaml`, `StudentLambda/pom.xml`, `StudentLambda/src/main/java/org/hmb/handler/StudentLambdaHandler.java`, `.../repository/StudentRepository.java`, new `common` student authenticator, docs, Postman | Add `Auth: {Authorizer: CognitoAccessToken, AuthorizationScopes: [openid]}` to the route; add `COGNITO_*` env + `nimbus-jose-jwt` to StudentFunction; resolve the student from the verified token via `AccountProfileRepository` (Google identity, `students` row, not deleted, `ACTIVE`, authorizer `sub` match) mirroring `RecruiterAuthenticator`; keep the dev header only behind `DevAuth` (flag **and** `AWS_SAM_LOCAL`) and only without an `Authorization` header; remove `X-Dev-Student-Id` from deployed CORS | JVM tests: no/invalid/expired/ID token -> 401; recruiter token, PENDING, SUSPENDED, deleted -> 403; header ignored when a token is present and outside `sam local`; MySQL IT: student A never sees B. Live: scenarios 5-11 (F.3) |
| INF-01 | **P0** | (MI) No AWS-reachable MySQL and no network path: database is local only; no RDS/Aurora, VPC config, subnets, security groups, NAT or endpoints. | AWS read-only inspection (D.1); `template.yaml` (no `VpcConfig`); local `env.json` points to localhost | `template.yaml`, new network/DB stack | Owner chooses hosting (recommended RDS MySQL 8.x, private subnets, Multi-AZ for prod, encryption at rest, TLS required); add `VpcConfig` to the four functions; NAT gateway for Cognito JWKS/userInfo; S3 gateway endpoint; optional Secrets Manager / Cognito IdP interface endpoints; SG rules (D.2) | From a deployed dev stack: `/auth/me` returns 200/`SIGNUP_FORM` (DB + Cognito reachable); CloudWatch shows no connection timeouts; `nc`-style reachability via Lambda test event |
| INF-02 | **P0** | (CG) Recruiter, Student and Document Lambdas can only read DB credentials from plaintext env vars; template sets `""` with no parameters, so a deployment cannot connect or would expose the password in the Lambda configuration. | `common/.../DatabaseConfig.java:52-67`; `template.yaml:122-135,174-182,279-287`; only `Registration/.../RegistrationDataSource.java:61-95` supports `DB_SECRET_ARN` | `common/.../config/DatabaseConfig.java`, `template.yaml` | Move Secrets Manager support into `common` (reuse the Registration logic), add `DB_SECRET_ARN` + scoped `secretsmanager:GetSecretValue` (and `kms:Decrypt` if a CMK is used) per function; parameterise host/port/name; remove hard-coded `DB_NAME` | Unit tests for secret parsing; deployed dev stack connects for all four functions; `aws lambda get-function-configuration` shows no password |
| REPO-01 | P1 | (CG) The audited authentication code, the whole Registration module source, schema DDL and new docs are uncommitted/untracked. A clean checkout or CI build would not contain them. | `git status` (53 entries; untracked `common/src/main/java/org/hmb/security`, `Registration/src`, ...) | Working tree | Review, then commit on a branch with a PR; build CI from the commit | CI build from a fresh clone passes the same 733 tests |
| INF-04 | P1 | (PR) `DB_SSL_MODE` default `VERIFY_IDENTITY` needs the RDS CA in the trust store; no CA bundle or truststore is packaged. Local Student config uses `DISABLED`. | `DatabaseConfig.java:83-104`; local `env.json` | `common/.../DatabaseConfig.java`, `template.yaml`, packaging | Package the RDS CA bundle (or a PKCS12 truststore) and set `DB_TRUSTSTORE_URL`; keep `VERIFY_IDENTITY`; set `require_secure_transport=ON` on RDS; align local SSL settings | Deployed connection succeeds with `VERIFY_IDENTITY`; `SHOW STATUS LIKE 'Ssl_cipher'` non-empty for Lambda sessions |
| IAM-01 | P1 | (CG) All functions use the MySQL `root` account locally; no least-privilege DB users exist. | local `env.json` (not reproduced); E.3 | DB, Secrets Manager | Create per-function MySQL users with the grants in E.3, `REQUIRE SSL`, one secret each | Run ITs under each user; attempt a forbidden statement (e.g. `UPDATE users` as `hmb_recruiter`) -> denied |
| CORS-01 | P1 | (CG) One `PortalOrigin` for API and S3 CORS; separate Student and Recruiter Vercel origins cannot both work. | `template.yaml:9-17,71-82,228` | `template.yaml` | Two origin parameters (or a list), both in `AllowOrigins`; S3 CORS only needs the recruiter origin; never `*` | Preflight from each portal succeeds; a third origin gets no ACAO header |
| COG-01 | P1 | (CG) App client callback/logout URLs are localhost only. | Cognito app client (read-only, D.1) | Cognito console | Owner adds exact HTTPS staging/production callback and sign-out URLs; consider a separate app client per environment | Hosted-UI sign-in from the Vercel domain completes |
| COG-02 | P1 | (CG) Pool allows native self sign-up, password and SMS OTP sign-in, phone usernames; app client allows `USER_PASSWORD_AUTH`; MFA off. Backend refuses native users, so not a bypass, but it allows SMS cost abuse and unnecessary attack surface. | Cognito user pool/app client (D.1); backend refusal: `CognitoIdentityService`, `AccountProfileRepository` provider check | Cognito | Restrict the app client to the Google IdP, remove `ALLOW_USER_PASSWORD_AUTH`, disable native self sign-up and SMS factors if unused, set SMS spend limits; consider threat protection | `aws cognito-idp describe-user-pool(-client)` shows the new settings; hosted UI shows Google only |
| TEST-01 | P1 | (TG) No real Google-issued Cognito access token has been tested against any endpoint; `CognitoLiveSmokeTest` real-token case skipped. | F.1, F.3 | Tests, Postman | Execute the 12-scenario plan with dedicated test accounts on `sam local` and the dev stack; record results (no tokens) | All expected results in F.3 observed |
| TEST-02 | P1 | (TG) Deployed API Gateway authorizer behaviour and `sam local` never exercised in this audit (Docker not running, nothing deployed). | F.1 | - | Run `sam local start-api` smoke tests; after deploying dev, run the F.3 matrix through API Gateway | Same |
| DEP-01 | P1 | (CD) Jackson 2.17.2 has 16 OSV advisories; at least one parser ReDoS (CVE-2026-89407) is plausibly reachable from untrusted bodies. | G.1; root `pom.xml:52-61` (`jackson-databind`/`jackson-annotations` 2.17.2; `jackson-core` follows transitively) | `pom.xml` | Import `jackson-bom` >= 2.18.11 | `mvn dependency:tree` shows one Jackson version; full test suite; OSV re-scan clean |
| DB-01 | P1 | (PR) Database name case: code/template use `hiremebot_db_V1`, local server stores `hiremebot_db_v1` (Windows). On Linux RDS names are case-sensitive; mismatch = `Unknown database`. | `template.yaml:387`; `hiremebot_db_V1.sql:1`; local `lower_case_table_names=1` | `template.yaml`, DDL, docs | Pick one exact (preferably lower-case) name; parameterise `DB_NAME`; create the RDS database with that name | Deployed connection succeeds |
| DB-02 | P1 (owner decision) | (CD vs owner rule) `UNIQUE(first_name, last_name)` requested by the owner is absent from DDL and the local DB; code already handles it. 0 duplicate groups locally. | E.2; `hiremebot_db_V1.sql:5-26`; `UniqueKeyCatalog.java:22,36` | DDL, each database | Owner confirms the rule (note accent/case-insensitive collation and same-name refusals); then run the E.2 migration per environment and update the DDL | Pre-check returns 0 rows; `SHOW INDEX FROM users` lists the index; `FullNameConstraint*` ITs pass against a copy |
| INF-03 | P1 | (PR) Connection scaling not designed: up to 2 connections per warm container x 4 functions, no reserved concurrency, no RDS Proxy. | `DatabaseConfig.java:69-77`; template (no `ReservedConcurrentExecutions`) | `template.yaml` | Reserved concurrency per function sized to the DB's `max_connections`, and/or RDS Proxy | Load test (PERF-01) shows no `Too many connections` |
| OPS-01 | P1 | (MI) No alarms; Recruiter/Student/Document log groups have no retention; no structured logs. | `template.yaml` (log groups only at `:104-107,458-462`) | `template.yaml` | Explicit log groups with retention for all functions; alarms on API 5xx/4xx rate, Lambda errors/throttles/duration p95, DB CPU/connections/storage; SNS notification | Alarms visible and test-fired |
| OPS-02 | P1 | (MI) No CI/CD, no staging environment, no stage parameter, no versions/aliases. | No pipeline files; `samconfig.toml` single env | New pipeline config, `template.yaml`, `samconfig.toml` | CI (build + test with MySQL service + `sam validate --lint`) and deploy to dev/staging via OIDC role; manual approval to prod; `AutoPublishAlias` | Pipeline run from a clean clone deploys staging |
| OPS-03 | P1 | (CG) Build reproducibility: `sam build` packages `hiremebot-common` from `~/.m2` (stale-jar risk); JDK 11 default on this machine; no enforcer/`release`. | D.5; root `pom.xml` | `pom.xml`, build scripts | Build with `mvn -pl common install` (or full reactor `install`) before `sam build`, or build artifacts with Maven and point `CodeUri` at them; add enforcer (Java 21, Maven >= 3.9, dependency convergence); use `maven.compiler.release` | CI build from empty `~/.m2` |
| OPS-04 | P1 | (MI) No backup/restore or DR plan. | D.9 | Infra/runbook | Automated RDS backups (7-35 days), PITR, deletion protection, snapshot before migrations; test restore; S3 versioning | Documented restore drill |
| RATE-01 | P1 | (MI) No API throttling or WAF; registration endpoints call Cognito per request. | `template.yaml` (no `DefaultRouteSettings`) | `template.yaml` | HTTP API stage throttling (burst/rate) per route; reserved concurrency; optional CloudFront + WAF rate rules | Load test returns `429` above limits |
| AUTHZ-03 | P2 | (PR, business rule) Recruiter APIs check the recruiter's status and the student's `is_deleted`, but not the student's `account_status`; a SUSPENDED/PENDING student's record can still be worked on. | `AssignmentRepository.java:17-29`; `RecruiterAccessRepository.java:31-40` | Recruiter and Document repositories | Owner decides; if required, add `su.account_status = 'ACTIVE'` | MySQL IT with a suspended student |
| SEC-01 | P2 | (PR) Revoked/signed-out Cognito tokens remain valid until expiry (60 min); student suspension not enforced (fixed by AUTHZ-01). | C.3 | Cognito, docs | No blacklist; shorten access-token lifetime to 15-30 min; incident runbook: suspend in MySQL + `AdminUserGlobalSignOut` | Suspended recruiter gets 403 on next call (already tested); token lifetime visible in Cognito |
| COG-03 | P2 | (CG) Refresh-token rotation off; 5-day refresh tokens. | D.1 | Cognito | Enable rotation (frontend must store the new refresh token) | Refresh returns a new refresh token |
| DEV-01 | P2 | (PR) Recruiter dev header and dev signing secret ship in production artifacts, gated by env vars an admin could set (`AWS_SAM_LOCAL` is not reserved). Not reachable through the JWT-protected HTTP API. | `DevAuth.java:23-31`; `RecruiterAuthenticator.java:96-126`; `SigningSecretProvider.java:40` | `common`, Document | Add a deploy-time guard (e.g. CloudFormation rule/`cfn-guard` that dev flags are `false`), or exclude dev code from production builds; alarm on the warning log line | Template policy check in CI |
| DEV-02 | P2 | (CG) Deployed CORS allows `X-Dev-Student-Id`. | `template.yaml:81` | `template.yaml` | Remove with AUTHZ-01 | Preflight with that header is refused |
| SEC-02 | P2 | (CD) Truststore password embedded in the JDBC URL. | `DatabaseConfig.java:98-101` | `common/.../DatabaseConfig.java` | Pass via Hikari data-source properties instead of the URL | Unit test asserts the URL has no password |
| SEC-03 | P2 | (CG) No explicit request-body limit in Recruiter/Document handlers (API Gateway allows 10 MB). | `RecruiterLambdaHandler.java:276`; `DocumentLambdaHandler.java:260` | Both handlers | Reject bodies > e.g. 64 KB with 413; set Jackson `StreamReadConstraints` | Handler tests |
| S3-01 | P2 (P1 before any download/preview feature) | (MI) No malware scanning; validation is extension/MIME/magic bytes only. | `FileFormat.java`; no scanning resources | `template.yaml` | Amazon GuardDuty Malware Protection for S3 (tag-based result) and block registration/download of non-clean objects | EICAR test file is flagged in staging |
| S3-02 | P2 | (CG) Bucket has no versioning/lifecycle (abandoned `pending` uploads accumulate); SSE-S3 not KMS; signing secret never rotates. | `template.yaml:202-264` | `template.yaml` | Lifecycle rule for `doc-state=pending` objects (e.g. 1 day); versioning (with noncurrent expiry); optional SSE-KMS; rotation runbook | `get-bucket-lifecycle-configuration` |
| IAM-02 | P2 | (CG) Deployments use an IAM user with long-lived access keys. | AWS CLI identity (read-only) | AWS account | Use IAM Identity Center for humans and GitHub OIDC role for CI; remove long-lived keys | `aws sts get-caller-identity` shows an assumed role |
| IAM-03 | P2 | (CG) Document function has `s3:ListBucket` on the whole bucket. | `template.yaml:309-334` | `template.yaml` | Restrict with `s3:prefix` condition `students/*` or remove if 403-vs-404 semantics are acceptable | Unit/staging upload tests |
| DB-03 | P2 | (PR) Global `UNIQUE(hashed_value)` blocks the same file for two students and reveals that a matching file exists. | `hiremebot_db_V1.sql:130`; `DocumentUploadService.java:435-440` | DDL, Document service | Owner decides: make it `UNIQUE(student_id, hashed_value)` | Migration on copy + document ITs |
| DB-04 | P2 | (PR) `ON UPDATE CASCADE` on `fk_application_assignment` rewrites historical `applications.recruiter_id` when a student is reassigned by `UPDATE`. | `hiremebot_db_V1.sql:75,111-115` | DDL, ops procedure | Document reassignment as delete+insert, or change the FK and keep history | IT: reassignment keeps old recruiter on old applications |
| DB-05 | P2 | (PR) `application_documents` does not enforce same-student/correct-type at DB level (app code does). | `hiremebot_db_V1.sql:135-149`; `ApplicationService.java:127-132` | DDL | Optional composite FK or trigger | IT |
| DB-06 | P2 | (CG) No migration tool/history; no explicit charset/collation; unused `otp_verifications`; `TIMESTAMP` 2038 limit. | `hiremebot_db_V1.sql:1,19-25,163-175` | DDL | Adopt Flyway (or numbered SQL scripts), `CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`, `DATETIME` for new columns | Fresh environment built from migrations |
| DB-07 | P2 | (PR) No `(student_id, applied_at)` index for student queries. | `StudentStatisticsRepository.java:42`; DDL `:82-116` | DDL | Add index after `EXPLAIN` on realistic data | `EXPLAIN` uses the index |
| DB-08 | P2 | (CG) Recruiter approval has no audit trail. | `Registration/db/recruiter-approval.sql` | DB/ops | Approval log table or documented change ticket per approval | Runbook review |
| DB-09 | P2 | (PR) A user can exist in both `students` and `recruiters` at DB level (only application logic prevents it). | DDL `:52-63` | DDL | Optional trigger/check; operator guidance | IT |
| ERR-01 | P2 | (DD) Three error envelopes (`code` vs `error`, `VALIDATION_FAILED` vs `VALIDATION_ERROR`, API Gateway `{"message"}`). Documented, so not a contract defect. | `ApiResponse.java:12-19`; `ErrorResponse.java:9`; `docs/API.md:17-19` | Docs/frontend | Keep as is for V1; frontend handles all three; plan a versioned unification later | Frontend error-handling tests |
| ERR-02 | P2 | (CG) Student Lambda has no request id; Recruiter/Document return none in the body. | `StudentLambdaHandler.java`; `RequestIds.java` | Lambdas | Add `RequestIds` + `X-Request-Id` header | Handler tests |
| PERF-01 | P2 | (TG) No load tests; memory/timeout/cold-start untested; SnapStart off. | D.8 | - | Load test in staging; evaluate SnapStart and memory sizing | Load-test report |
| PERF-02 | P2 | (PR) Recruiter students list unpaginated. | `AssignmentRepository.java:40-50` | Recruiter | Add pagination when assignment counts grow | API tests |
| DOC-01 | P2 | (DD) `.env.example:43-62` contains a stray JSON block with dev flags `true`; root README is SAM boilerplate; template description "Sample SAM Template"; `RecruiterRequest.java:14` still says the recruiter id comes from the dev header. | Listed files | `.env.example`, `README.md`, `template.yaml:3-6`, `RecruiterRequest.java:14` | Clean up | Review |
| DEP-02 | P2 | (CD, not applicable path) `protobuf-java` 3.25.1 CVE-2024-7254. | G.1 | `common/pom.xml` | Exclude from `mysql-connector-j` | ITs |
| DEP-03 | P2 | (CG) `nimbus-jose-jwt` optional in `common`; old `aws-lambda-java-events`. | G.2 | POMs | Document the requirement / make it non-optional; optional upgrade | Build |

---

## J. Suggested Remediation Order

| # | Work package | Items | Depends on | Complexity |
|---|---|---|---|---|
| 1 | **Baseline the code** | REPO-01, OPS-03 (build `common` from source, enforcer, `release`) | - | Low |
| 2 | **Student authentication and authorization** | AUTHZ-01, DEV-02, ERR-02 (student request id), tests for C.4 student scenarios; decide AUTHZ-03 | 1 | Medium |
| 3 | **Database credentials and hardening in code** | INF-02 (Secrets Manager in `common`), SEC-02, SEC-03, DB-01 (name parameter), DEP-01, DEP-02 | 1 | Medium |
| 4 | **AWS foundation (owner-approved)** | INF-01 (VPC, subnets, SGs, NAT, S3 endpoint, RDS), INF-04 (TLS/CA), IAM-01 (DB users + secrets), OPS-04 (backups), IAM-02 (deployer identity) | 3 | High |
| 5 | **Template and Cognito configuration** | CORS-01, COG-01, COG-02, COG-03, stage parameter (OPS-02 part), RATE-01, INF-03, OPS-01 (log retention, alarms), S3-02, IAM-03, DEV-01 guard | 2, 4 | Medium |
| 6 | **Database business rules (owner decisions)** | DB-02 (full-name index), DB-03, DB-04, DB-05, DB-06 (migration tooling), DB-07, DB-08, DB-09 | 4 | Medium |
| 7 | **Development deployment and real-token validation** | Deploy dev stack; TEST-02; TEST-01 (F.3 matrix) | 2-5 | Medium |
| 8 | **Staging and pipeline** | OPS-02 (CI/CD, staging, approvals, aliases), PERF-01 load test, PERF-02 if needed | 7 | High |
| 9 | **Production hardening** | S3-01 (malware scanning), SEC-01 (token lifetime, runbook), privacy/deletion procedure, cost controls, incident and rollback runbooks, ERR-01 roadmap, DOC-01, DEP-03 | 8 | Medium |

---

## K. Deployment Checklist

### Before AWS development deployment

- [ ] Audited code committed (REPO-01); build from a clean clone with JDK 21; `common` installed before `sam build`.
- [ ] AUTHZ-01 fixed, **or** explicitly accept that the student dashboard returns `403` in dev and confirm
      `ALLOW_DEV_STUDENT_HEADER` stays `"false"`.
- [ ] AWS-hosted MySQL created; DDL applied with the agreed database name (DB-01).
- [ ] VPC config, security groups, NAT (Cognito JWKS/userInfo), S3 gateway endpoint (INF-01).
- [ ] DB credentials in Secrets Manager for every function; no plaintext passwords in Lambda configuration (INF-02).
- [ ] TLS to MySQL verified (INF-04).
- [ ] `TermsVersion`, `DatabaseSecretArn`, Cognito parameters and `PortalOrigin` passed explicitly.
- [ ] `sam validate --lint` and `mvn clean verify` (scratch schema) green.

### Before staging deployment

- [ ] Everything above.
- [ ] Separate staging stack/parameters; CI/CD pipeline with OIDC role (OPS-02, IAM-02).
- [ ] Least-privilege DB users (IAM-01).
- [ ] Student and Recruiter origins configured (CORS-01); Cognito callback/logout URLs for staging (COG-01).
- [ ] Jackson upgraded (DEP-01).
- [ ] F.3 real-token matrix executed against the dev stack with test accounts (TEST-01, TEST-02).
- [ ] Log retention for all functions (OPS-01).
- [ ] Full-name rule decision made and migration applied if approved (DB-02).

### Before public production launch

- [ ] Everything above, executed and re-verified in staging.
- [ ] Cognito hardened: Google-only app client, no password/SMS flows, SMS spend limits (COG-02); token lifetimes and
      refresh rotation decided (SEC-01, COG-03).
- [ ] Alarms and on-call notification (OPS-01); throttling (RATE-01); reserved concurrency / RDS Proxy (INF-03).
- [ ] RDS Multi-AZ, automated backups, PITR, deletion protection, restore drill done (OPS-04).
- [ ] Load test results reviewed (PERF-01).
- [ ] Malware scanning in place if any download/preview feature ships; otherwise documented acceptance (S3-01).
- [ ] S3 lifecycle/versioning (S3-02).
- [ ] Runbooks: deployment, rollback (alias/previous version), incident (suspend + global sign-out), recruiter
      approval, privacy deletion.
- [ ] Deployment approval recorded by the owner.

---

## L. Manual Actions Required From Owner

| Area | Action |
|---|---|
| AWS account and region | Confirm the target account(s) and `us-east-2`; decide whether dev/staging/prod use separate accounts (recommended) or separate stacks. |
| Database hosting | Choose RDS MySQL / Aurora MySQL, instance size, Multi-AZ, backup retention; approve the VPC/NAT cost. |
| Database name | Confirm the exact database name (case) for all environments (DB-01). |
| Secrets Manager | Create (or approve creation of) one DB secret per function user; approve rotation policy; never share values in chat/tickets. |
| IAM | Approve least-privilege DB users (E.3) and Lambda policies; replace long-lived deployer keys with Identity Center / OIDC. |
| Vercel origins | Provide the exact Student and Recruiter portal HTTPS origins for staging and production. |
| Cognito | Add callback/sign-out URLs; decide on Google-only sign-in, disabling native sign-up/password/SMS, MFA/threat protection, token lifetimes, refresh-token rotation; consider separate app clients/pools per environment. |
| Business rules | Decide: full-name uniqueness (DB-02), document hash uniqueness scope (DB-03), whether suspended/pending students may still be served by recruiters (AUTHZ-03), reassignment history (DB-04). |
| Database migration approval | Approve the E.2 migration (after the duplicate pre-check) per environment. |
| Terms | Provide `TermsVersion`. |
| Real-account integration testing | Create dedicated Google test accounts with no real personal data and run (or authorize) the F.3 matrix; approve/suspend test recruiters in the non-production DB only. |
| Recruiter approvals | Name the operator(s) allowed to approve recruiters; agree how approvals are recorded (DB-08). |
| Deployment approval | Approve each environment deployment after the corresponding checklist in section K is complete. |

---

## M. Verified vs Unverified

| Source of evidence | Conclusions supported |
|---|---|
| **Source code inspection** | Complete API inventory and module map; JWT validation rules; recruiter/document authorization chain; student dashboard dev-header authentication and missing status check (AUTHZ-01); dev-auth gating (C.5); registration rules and transactions; S3 key/token/presign design; error envelopes; logging contents; DB pool settings; IAM statements in the template; missing VPC/secret support. |
| **Unit / JVM tests** (locally signed RS256 tokens, mocked AWS) | 733 tests run, 732 passed, 1 skipped, 0 failed: token rejection cases (alg, issuer, client, token_use, expiry, scope, malformed), token precedence over dev headers, PENDING/SUSPENDED/student-token refusals, request validation, outage handling. |
| **MySQL integration tests** (isolated scratch schemas, dropped afterwards) | Registration transactions and races, full-name index handling (in scratch schema), recruiter manual approval, assignment-scoped applications and documents, concurrency/duplicate handling, student dashboard queries. |
| **Live Cognito (read-only)** | JWKS fetch from the real pool and forged-token rejection (`CognitoLiveSmokeTest`); user pool and app client settings (D.1). |
| **SAM CLI** | `sam validate --lint` valid; `sam build` succeeded. |
| **AWS CLI inspection (read-only)** | No stacks, Lambdas, HTTP APIs, RDS or secrets in `us-east-2`; default VPC only; development bucket settings; Cognito settings; deployer is an IAM user. |
| **Local MySQL (read-only metadata/aggregates)** | Schema matches the DDL; full-name index absent; 0 duplicate name groups; collation, `lower_case_table_names`, `require_secure_transport`, `max_connections`; row counts unchanged by the audit. |
| **OSV.dev** | Jackson and protobuf advisories in G.1 (no NVD/Dependabot scan). |
| **Existing documentation / Postman** | Documented contracts match code (B.5); Postman collections contain no real tokens. |

**NOT VERIFIED (requires further work):**

* Any request with a **real Google-issued Cognito access token** (TEST-01).
* `sam local start-api` behaviour (Docker not running) and **deployed API Gateway authorizer** behaviour, including the
  `{"message":"Unauthorized"}` / `{"message":"Forbidden"}` responses and CORS preflights (TEST-02).
* Lambda-to-database connectivity, TLS with `VERIFY_IDENTITY` against RDS, Secrets Manager reads, NAT/endpoint
  routing (nothing deployed).
* Performance, cold starts, connection scaling, throttling (no load test).
* The frontend applications (not in this repository).
* Vulnerabilities not present in OSV.dev; the Nimbus changelog review.
* Production-like Cognito behaviour for revoked tokens (behaviour described from AWS documentation and code, not tested).

**Assumptions:** Staging and production will use AWS-hosted MySQL in `us-east-2`; the two portals will be served from
two different Vercel origins; V1 keeps `PHONE_VERIFICATION_REQUIRED=false`; recruiter approval remains a manual
database operation.

