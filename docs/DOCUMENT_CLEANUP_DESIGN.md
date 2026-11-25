# Design: manual cleanup of abandoned document uploads (`DocumentCleanupFunction`)

Status: **design only. Nothing in this document is implemented, deployed or granted.** The current stack has no
cleanup function, no `s3:DeleteObject` permission anywhere, no lifecycle rules and no versioning.

## 1. Problem

Every upload is a direct browser PUT to `students/{studentId}/{resumes|cover-letters}/{uuid}.{pdf|docx}`, signed with
`x-amz-tagging: doc-state=pending`. A successful `register` inserts a row in `object_store_entries` and retags the object
`doc-state=registered`. Objects that are uploaded but never registered stay in S3 forever, because V1 has no lifecycle
rule and no reconciler. Typical sources:

- the user closed the browser between the PUT and `register`;
- `register` failed (verification failure, database outage) and was never retried;
- two uploads of identical bytes raced and the loser's object has no row;
- a retry after an expired URL left the first object behind.

These objects cost storage and are personal data with no owner in MySQL. A future administrator-run tool should find
them and, after explicit approval, delete them.

## 2. Definitions

| Term | Meaning |
|---|---|
| **age** | `now - LastModified` of the S3 object (from `ListObjectsV2`) |
| **threshold** | `olderThanDays`, default **7**. A cleanup eligibility threshold, not an S3 expiration rule |
| **registered** | a row exists in `object_store_entries` with `object_key` equal to the S3 key. MySQL is the only authority |
| **registered-but-pending** | a row exists but the tag is still `pending` (the INSERT succeeded and the tagging step failed). **This is a valid, in-use document and is never a cleanup candidate** |
| **candidate** | an object that passes every rule in section 3 |

## 3. Eligibility rules (all must hold, evaluated in this order)

1. The key is under `students/` and matches the server's key shape
   `students/{digits}/(resumes|cover-letters)/{uuid}.(pdf|docx)`. Anything else is skipped and reported.
2. `age >= threshold`, and `threshold >= MIN_THRESHOLD_DAYS` (see section 6.1). The handler rejects a smaller value.
3. `GetObjectTagging` succeeded and the tag set contains exactly `doc-state=pending`. `registered`, missing, unknown or
   multiple values: not a candidate (reported separately).
4. MySQL confirms **no row** with this `object_key`. A query error, timeout or any doubt is "not a candidate"
   (fail closed). The tag is never trusted on its own: see "registered-but-pending" above.
5. The object is outside any possible upload or registration window. This is guaranteed by rule 2 together with the
   invariant in section 6.1, not by a separate check.
6. Every S3 and MySQL call for this object succeeded. Any failure moves the object to `failed`, never to `eligible`.

## 4. Invocation and authorization

- **No EventBridge schedule, no API Gateway route, no Function URL, no S3 trigger.** The function has no `Events` in the
  template.
- Invoked only by an administrator, from the Lambda console ("Test") or the CLI:

  ```bash
  aws lambda invoke --function-name <DocumentCleanupFunction> --region us-east-2 \
    --cli-binary-format raw-in-base64-out --payload '{"mode":"DRY_RUN","olderThanDays":7}' out.json
  ```

- `lambda:InvokeFunction` is granted only to an administrators' IAM role (no resource-based policy for anyone else).
- `ReservedConcurrentExecutions: 1`, so two cleanup runs can never overlap.
- `Timeout` up to 900 s. The run checks `context.getRemainingTimeInMillis()` and stops cleanly, returning a
  continuation marker.

## 5. Modes

### 5.1 `DRY_RUN` (read-only, the default)

Input:

```json
{ "mode": "DRY_RUN", "olderThanDays": 7, "startAfter": null, "maxKeys": 10000 }
```

Steps:

1. `ListObjectsV2` with `Prefix=students/`, paginated with `ContinuationToken` (never assume one page), starting after
   `startAfter` if given.
2. Keep keys with `age >= threshold`. `ListObjectsV2` returns `LastModified`, `ETag` and `Size`, so this costs nothing
   extra. **`ListObjectsV2` cannot filter by tag**, so the age filter comes first to keep tag calls low.
3. `GetObjectTagging` for each remaining key (bounded parallelism, for example 8).
4. For `pending` objects, look up the keys in MySQL in batches:
   `SELECT object_key FROM object_store_entries WHERE object_key IN (?, ?, ...)` (at most 500 placeholders, read-only
   user). Keys that come back are registered-but-pending.
5. Write a **manifest** of the candidates (section 5.3) and return the summary. **Nothing is deleted or retagged.**

Output (the order of the counters matches the order of the checks):

```json
{
  "mode": "DRY_RUN",
  "scanned": 150,
  "olderThanThreshold": 40,
  "pending": 12,
  "registeredButPending": 2,
  "registeredTag": 26,
  "otherTag": 2,
  "eligibleForCleanup": 10,
  "failed": 0,
  "deleted": 0,
  "complete": true,
  "nextStartAfter": null,
  "manifestId": "2026-11-04T18-20-00Z-3f9c",
  "manifestSha256": "9b1d...e0"
}
```

`registeredButPending` also lists the affected `objectId`s so an administrator can retag them if they want. The
cleanup function itself has **no `s3:PutObjectTagging`** and never repairs anything.

### 5.2 `DELETE` (destructive, only from an approved manifest)

Input:

```json
{
  "mode": "DELETE",
  "manifestId": "2026-11-04T18-20-00Z-3f9c",
  "manifestSha256": "9b1d...e0",
  "expectedCount": 10,
  "confirmation": "CONFIRM_DELETE"
}
```

Deletion is a **two-stage approval**. `DELETE` never deletes "whatever is eligible now". It processes only the keys in a
manifest that a human reviewed:

1. Refuse unless `confirmation` is exactly `CONFIRM_DELETE`, the manifest exists, its SHA-256 equals `manifestSha256`
   (no edits after review), `expectedCount` equals the manifest's size, and the manifest is younger than
   `MANIFEST_MAX_AGE_HOURS` (24). Otherwise a new dry run is required.
2. Refuse if the deployment has deletes disabled (`EnableDocumentCleanupDeletes=false`, section 8).
3. For each manifest entry `{key, etag, lastModified, size}`, **re-check every rule from section 3 from scratch** (fresh
   `HeadObject`/`GetObjectTagging`, age, tag, key shape, MySQL), and require that the current `ETag` and `LastModified`
   still equal the manifest's. Any difference → `skipped`.
4. Delete under the coordination protocol in section 6 (`DeleteObject` with `If-Match: <manifest ETag>`).
5. Record the result per entry, write a result file next to the manifest, and return counts:

```json
{ "mode": "DELETE", "manifestId": "...", "requested": 10, "deleted": 9, "skipped": 1, "failed": 0,
  "skippedReasons": { "nowRegistered": 0, "tagChanged": 1, "etagChanged": 0, "missing": 0, "lockBusy": 0 } }
```

A run that hits its time limit stops. Re-running `DELETE` with the same manifest is safe and idempotent: deleted keys are
`missing`, and everything is re-checked.

### 5.3 The manifest

- Stored at `s3://<documents bucket>/admin/cleanup-manifests/{manifestId}.json` (same private, encrypted, TLS-only
  bucket; outside `students/`, so no cleanup permission can ever delete it). Never in logs.
- Content: run metadata (threshold, time, function version, counts) and, per candidate, `key`, `etag`,
  `lastModified`, `size`. No document content and no presigned URLs.
- The administrator reviews the manifest (count, prefixes, dates) before running `DELETE`. Keys contain student ids,
  so the manifest is handled as internal personal data.

## 6. Race conditions and data-loss analysis

### 6.1 Registration vs cleanup (the main risk)

The concern: a recruiter registers object `K` while cleanup deletes `K`. "Check MySQL, then delete" is **not atomic**
with another Lambda's INSERT, so the re-check right before delete **does not by itself** prevent the race. This design
relies on a structural invariant instead, and adds a lock as defense in depth.

**Invariant: no row for key `K` can be inserted through the API after a bounded time from `K`'s creation.**

Why it holds in the current code (verified in `DocumentUploadService` and `UploadTokenService`):

- The **only** INSERT into `object_store_entries` is in `register`, after `UploadTokenService.verify` rejects any
  token at or past its expiry (`UPLOAD_TOKEN_EXPIRED`).
- The token binds the exact `objectKey`, and keys are random UUIDs generated server-side by `upload-url`. There is no
  way to obtain a new token for an existing key: every `upload-url` call issues a new key.
- The token and the presigned URL are created in the same `upload-url` request at time `T`, with
  `exp = T + UPLOAD_TOKEN_TTL_SECONDS` (default 1800, code-enforced maximum 86400). The PUT can only happen after `T`,
  so the object's `LastModified >= T` (allowing seconds of clock skew).
- Therefore every INSERT for `K` begins before `LastModified(K) + TTL`, and completes (or is abandoned) within the
  Lambda timeout (20 s) plus MySQL's `innodb_lock_wait_timeout` (default 50 s) after that.

So for any object older than `TTL_max + timeout + lock wait + skew` (about 1 day and 2 minutes at the configured
maximums), the API can no longer create a row for it. With the default 7-day threshold the margin is about 6 days.
Rules derived from this:

- `MIN_THRESHOLD_DAYS = 2` is enforced by the handler (smaller `olderThanDays` is rejected), and must be
  re-derived if `UPLOAD_TOKEN_TTL_SECONDS`'s allowed maximum is ever raised.
- The rule also covers "replay": replaying `register` for `K` needs a row (then `K` is not a candidate) or a valid token.

**What the invariant does not cover:**

| Gap | Mitigation |
|---|---|
| A row inserted by hand (SQL client, migration script) for an old key | Operational rule: no manual inserts into `object_store_entries` while a cleanup is approved or running. The `DELETE` re-check catches anything inserted before it, and the lock below catches cooperating writers |
| A leaked token-signing secret (anyone could mint tokens for any key and time) | Out of scope for cleanup; rotate the secret. The lock below still serializes such registrations with deletes |
| A wrong clock on the Lambda host | Negligible (AWS hosts are NTP-synced); the 6-day margin absorbs it |
| A future code change that lets a token outlive its key, or registers by key without a token | Any such change must re-validate this section. Note it in code review |

**Defense in depth: per-key MySQL named lock (recommended before enabling `DELETE`).** Without any schema change, both
writers take `GET_LOCK` on the same name, so "check, then act" becomes atomic between them:

- Lock name: `hmbdoc:` + the first 32 hex characters of `SHA-256(objectKey)` (MySQL's 64-character limit). Collisions
  only cause extra serialization, never wrong results.
- **Cleanup**, per key: `GET_LOCK(name, 0)` (busy → `skipped/lockBusy`), then re-check the row, then
  `DeleteObject If-Match`, then `RELEASE_LOCK(name)` in `finally`, all on one connection.
- **Register** (a small future change in `DocumentUploadService`): take `GET_LOCK(name, 5)` on one connection held
  from the `findKnown` lookup through the INSERT; release in `finally`. If the lock cannot be taken, answer
  `503 SERVICE_UNAVAILABLE` (the client retries `register`, which is already idempotent).
- Named locks are connection-scoped and need no privileges. They work on a single MySQL primary (the current setup).
  With RDS Proxy, sessions with named locks are pinned, which is acceptable at this volume.

This changes the register code, so it is part of the cleanup milestone and needs its own tests. Without it, safety
rests on the invariant above, which is strong for API traffic but does not cover the listed gaps.

### 6.2 Object replaced between check and delete

`DeleteObject` with `If-Match: <ETag>` fails with `412 Precondition Failed` if the object changed (verified in the S3
API reference: `If-Match` is supported for general purpose buckets). `x-amz-if-match-last-modified-time` and
`x-amz-if-match-size` are **directory-bucket only**, so `LastModified` and size are compared by the function itself
(from `HeadObject`) instead. Keys are random and URLs expire within minutes, so a replacement is practically
impossible; the precondition is cheap insurance. A `412` is `skipped/etagChanged`.

### 6.3 Other cases

| Case | Behaviour |
|---|---|
| Registered-but-pending (row exists, tag `pending`) | MySQL check finds the row: **never deleted**, reported with its `objectId` |
| Tag `registered`, no row (for example the student was hard-deleted and the row cascaded) | Not a candidate (tag is not `pending`). Reported as `registeredTag` without a row in a later version, if wanted. Out of scope for this tool |
| Untagged object, or another tag value | Not a candidate; reported as `otherTag` |
| Key outside the expected shape | Skipped and reported |
| MySQL unavailable | The run fails or marks every unchecked key `failed`. Never "no row" |
| `object_key` collation is case-insensitive | A lookup can only err towards "row exists", which is the safe direction. Keys are generated lowercase |
| Same bytes uploaded again later | Unaffected. `upload-url` checks only MySQL, so the new upload gets a new key; the old orphan is independent |
| Cleanup could create `DOCUMENT_UNAVAILABLE` (row without object) | Only through the race in 6.1, which the invariant and the lock close |

### 6.4 Irreversibility

The bucket has **no versioning**, so a `DeleteObject` is permanent. That is why deletion requires a reviewed manifest,
an explicit confirmation, a deploy-time switch, and full re-validation. If versioning is ever re-enabled or
suspended, a plain delete only adds a delete marker; the function would then need `s3:DeleteObjectVersion` and the
version id from the manifest to actually reclaim storage. Revisit this design in that case.

## 7. Logging and privacy

- Log counts and `objectId`s (for registered-but-pending). Do not log keys, ETags of individual objects, document
  content, presigned URLs, tokens or credentials. Per-key detail goes only to the manifest and result files.
- One summary line per run (`mode`, threshold, counters, `complete`, `manifestId`).
- Failures log the AWS error code and the counter, never the S3 response body.

## 8. Permissions (future; none of this is granted today)

`DocumentCleanupFunction` role:

| Statement | Action | Resource / condition |
|---|---|---|
| List candidates | `s3:ListBucket` | the bucket, with `Condition: StringLike s3:prefix = students/*` |
| Read tags and metadata | `s3:GetObjectTagging`, `s3:GetObject` (for `HeadObject`) | `arn:aws:s3:::<bucket>/students/*` |
| Manifests | `s3:PutObject`, `s3:GetObject` | `arn:aws:s3:::<bucket>/admin/cleanup-manifests/*` |
| Delete (only when enabled) | `s3:DeleteObject` | `arn:aws:s3:::<bucket>/students/*` |

- MySQL: a dedicated **read-only** user with `SELECT` on `object_store_entries` only (`GET_LOCK` needs no grant).
- `s3:DeleteObject` is added only when the template parameter `EnableDocumentCleanupDeletes` is `"true"` (default
  `"false"`), through a condition, so deletion is a deliberate deploy-time decision and a dry-run-only deployment
  cannot delete even if invoked with `DELETE`:

  ```yaml
  # sketch only, not in template.yaml
  Parameters:
    EnableDocumentCleanupDeletes: { Type: String, Default: "false", AllowedValues: ["true", "false"] }
  Conditions:
    CleanupDeletesEnabled: !Equals [!Ref EnableDocumentCleanupDeletes, "true"]
  # in DocumentCleanupFunction.Policies[0].Statement:
    - !If
      - CleanupDeletesEnabled
      - { Sid: DeleteApprovedOrphans, Effect: Allow, Action: s3:DeleteObject,
          Resource: !Sub "${DocumentsBucket.Arn}/students/*" }
      - !Ref AWS::NoValue
  ```

- `DocumentManagementFunction` (the upload API) keeps **no** `s3:DeleteObject`.

## 9. Scaling

One `GetObjectTagging` per object older than the threshold, per run. That is fine for V1 volumes, since runs are manual
and rare. For large buckets, replace steps 1 to 3 with an **S3 Inventory** report (daily or weekly CSV/Parquet that
includes `LastModified`, `ETag` and, optionally, object tags), so a run reads one report instead of calling the S3 API
per object.

## 10. Implementation plan (future milestone)

1. Module: reuse `DocumentManagementLambda` (new handler `org.hmb.handler.DocumentCleanupHandler`, new
   `DocumentCleanupService`), or a separate module if the package size matters. `S3DocumentStorageService.state(key)` and
   `ObjectState` already read the `doc-state` tag and can be reused. Add `list(prefix, startAfter)` and
   `deleteIfMatch(key, etag)`.
2. `DocumentRepository`: add `findExistingKeys(Connection, List<String>)` (batched `IN`, read-only).
3. Implement `DRY_RUN` and manifests first. Deploy with deletes disabled and run dry runs for a while.
4. Add the named lock to `register` (section 6.1) with tests, including a concurrency test against real MySQL.
5. Implement `DELETE`. Enable it only by redeploying with `EnableDocumentCleanupDeletes=true` after reviewing a dry-run
   manifest.
6. Tests: every rule in section 3 (including registered-but-pending never deleted, MySQL errors fail closed, threshold
   below `MIN_THRESHOLD_DAYS` rejected, wrong confirmation, tampered or stale manifest, ETag change → skip, lock busy →
   skip, pagination across pages, time-budget stop and continuation, logs contain no keys or URLs).

## 11. Explicitly out of scope

Automatic or scheduled cleanup, S3 lifecycle expiration, versioning, retagging/repair, deleting registered objects,
deleting objects whose student was removed, a public or API Gateway cleanup endpoint, and any schema change.
