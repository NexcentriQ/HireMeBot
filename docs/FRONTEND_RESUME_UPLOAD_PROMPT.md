# Prompt for the Recruiter Portal repo: Resume Upload

Paste everything below into your frontend repository's assistant. It is self-contained: it does not assume access to
the backend code. Backend contract version: HireMeBot `/api/v1/documents/*`.

---

## Your task

You are a senior React engineer working in the existing **HireMeBot Recruiter Portal** (React + Vite). Implement the
**resume upload** feature end to end in this repository, reusing its existing API client, authentication provider,
notification/toast component, form components and styling. Do not introduce a new HTTP library, state library or UI
kit. If something this prompt names does not exist in the repo, say so and choose the smallest sensible equivalent.

A recruiter opens an assigned student's profile, chooses a resume (PDF or DOCX), and the file is uploaded **directly
from the browser to a private S3 bucket** through a presigned URL. The backend then registers it and returns an
`objectId`. The `objectId` is later used by the existing "create application" call as `documents.resumeObjectId`.
**Uploading a resume does not create an application.**

Scope: resume upload only. Cover letters are defined by the backend but **disabled**; build no cover-letter UI. Do not
build download, preview, list or delete features.

## The flow (implement exactly this)

```
choose file
  -> validate (advisory)           extension, MIME, size, not empty
  -> SHA-256 of the file's bytes   Web Crypto, hex, lowercase
  -> POST /api/v1/documents/upload-url
        ALREADY_EXISTS   -> show "This resume has already been uploaded."; keep objectId; STOP (no upload)
        UPLOAD_REQUIRED  -> continue
  -> PUT file to uploadUrl directly on S3, with exactly requiredHeaders   (show real progress)
        only if S3 returned 200 ->
  -> POST /api/v1/documents/register
        success (201 or 200) -> show success, hand objectId to the caller
```

Show success **only** when `register` succeeds. A successful S3 `PUT` alone is not success.

## API contract

Base URL comes from the repo's existing API configuration (locally `http://127.0.0.1:3000`). Reuse the existing API
client. Send JSON with `Content-Type: application/json`.

### Authentication

Production authentication (a validated Cognito/JWT) is **not built yet**. Until it is, the backend identifies the
recruiter from a development-only header and accepts it **only when the backend runs locally under `sam local`**; a
deployed backend answers `403` to every document call. So today this feature can only be exercised against a local
backend.

- Attach authentication in **one place only**, through the repo's existing API client / auth provider. For local
  development that is the header `X-Dev-Recruiter-Id: <recruiter user_id>`; later it becomes
  `Authorization: Bearer <JWT>`. Do not scatter either through the upload code.
- **Never send the application's JWT, any `Authorization` header or any cookie to S3.** The S3 request carries only the
  headers the backend returned.
- The recruiter id is **never** part of a request body.

### 1. `POST /api/v1/documents/upload-url`

Request:

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

- `studentId`: the selected student (the recruiter must be assigned to them).
- `documentType`: always the string `"RESUME"`.
- `fileName`: only its extension matters (`pdf` or `docx`).
- `contentType`: exactly `application/pdf` for `.pdf`, and
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document` for `.docx`. Derive it from the extension;
  do not trust `file.type` (browsers leave it empty or wrong for some files).
- `fileSize`: `file.size` in bytes, 1 to 5,242,880 (5 MB).
- `checksum`: SHA-256 of the **file's bytes** as 64 lowercase hex characters. Not a hash of the name, path or text.

Success `200`, one of:

```json
{ "success": true, "status": "ALREADY_EXISTS", "uploadRequired": false, "objectId": 1000000001 }
```

```json
{
  "success": true,
  "status": "UPLOAD_REQUIRED",
  "uploadRequired": true,
  "objectKey": "students/1000000005/resumes/123e4567-e89b-12d3-a456-426614174000.pdf",
  "uploadUrl": "https://<bucket>.s3.<region>.amazonaws.com/...?X-Amz-Signature=...",
  "uploadToken": "<opaque string>",
  "expiresIn": 300,
  "requiredHeaders": {
    "Content-Type": "application/pdf",
    "x-amz-checksum-sha256": "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
    "x-amz-tagging": "doc-state=pending"
  }
}
```

`objectKey`, `uploadUrl` and `uploadToken` are chosen and signed by the server. `uploadUrl` and `uploadToken` are
**credentials**.

### 2. The direct S3 upload

`PUT` the raw file (the `File`/`Blob` itself as the body) to `uploadUrl`, sending **exactly** the headers in
`requiredHeaders`: every one, with the exact value. They are part of the signature; a missing or different value makes
S3 reject the request with `403 SignatureDoesNotMatch`. Header names are case-insensitive. Do **not** add other headers,
and **do not set `Content-Length`** (the browser sets it from the body, and the signature requires it to equal
`fileSize`). `x-amz-checksum-sha256` is already the base64 form S3 needs, so never recompute or re-encode it: echo it.
Use `withCredentials = false`. Proceed to register only on an S3 response of `200`.

Use **`XMLHttpRequest`**, not `fetch`, for this request: `xhr.upload.onprogress` gives real byte-level progress and
`fetch` does not. Wrap it in a small promise-returning function that also supports aborting.

### 3. `POST /api/v1/documents/register`

Request (every value exactly as issued or sent earlier):

```json
{
  "studentId": 1000000005,
  "documentType": "RESUME",
  "objectKey": "<objectKey from upload-url>",
  "checksum": "<the same hex checksum>",
  "uploadToken": "<uploadToken from upload-url>"
}
```

Success: `201` (registered now) or `200` (already registered, for example a retry). The body is the same either way:

```json
{ "success": true, "status": "SUCCESS", "objectId": 1000000002, "studentId": 1000000005,
  "documentType": "RESUME", "message": "Resume uploaded successfully" }
```

`register` is **idempotent and safe to retry**: repeating it returns the same `objectId` and creates nothing new.

### Errors

Every error is JSON: `{ "success": false, "error": "<CODE>", "message": "...", "details": ["..."] }` (`details` only for
validation). Branch on `error`, never on `message`. Do not rely on `401`: there is no JWT yet.

| `error` | HTTP | Meaning | What the UI does |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | a field is invalid; `details` lists them | show them; this is a bug in the client if validation passed |
| `DOCUMENT_TYPE_NOT_ENABLED` | 400 | the type is disabled | generic failure (should never happen for `RESUME`) |
| `BAD_REQUEST` | 400 | missing dev header, malformed body | generic failure |
| `FORBIDDEN` | 403 | not a recruiter, or not assigned to this student, or auth not configured | "You can't upload documents for this student." |
| `DUPLICATE_DOCUMENT` | 409 | these exact bytes are already registered to someone else or as another type | "This document is already registered." Show only this generic text; the backend deliberately reveals nothing else |
| `DOCUMENT_UNAVAILABLE` | 409 | registered for this student but the stored file is missing | "This resume is registered but its file is unavailable. Contact support." |
| `UPLOAD_TOKEN_EXPIRED` | 400 | the 30-minute token expired | start over from `upload-url` (a fresh URL) |
| `UPLOAD_TOKEN_INVALID` | 403 | token or key does not match the request | start over from `upload-url`; if it repeats, generic failure |
| `UPLOAD_NOT_FOUND` | 400 | no object at that key (the PUT failed or never happened) | start over from `upload-url` |
| `UPLOAD_VERIFICATION_FAILED` | 400 | S3's object differs from what was authorized | start over; "The upload did not match the selected file." |
| `INVALID_FILE_FORMAT` | 400 | the file does not look like a valid PDF/DOCX | "This file doesn't look like a valid PDF or Word document." Do not retry the same file |
| `SERVICE_UNAVAILABLE` | 503 | temporary | **retry** (see retry rules) |
| `INTERNAL_ERROR` | 500 | unexpected | generic failure, allow a manual retry |

## State, retries and what to keep

Model the upload as an explicit state machine (for example a `useReducer`, or a small custom hook such as
`useResumeUpload`), with states such as: `idle`, `validating`, `hashing`, `requestingUrl`, `uploading` (with
`progress`), `registering`, `succeeded`, `alreadyExists`, `failed` (with a typed reason and whether it is retryable).
The UI renders from the state; it must never show success outside `succeeded` / `alreadyExists`.

Keep **in memory only** (component state or a ref) the values needed to resume: `file`, `checksum`, `objectKey`,
`uploadToken`, `uploadUrl`, `requiredHeaders`, and the time the URL was issued. **Never** put the URL or token in
`localStorage`, `sessionStorage`, cookies, URLs, analytics or logs, and clear them after success or when the dialog
closes.

Retry rules:

- **`upload-url` fails with `503`/network error:** retry with short backoff (for example 3 tries), then show a retry button.
- **S3 `PUT` network error or `5xx`:** retry the same `PUT` (same URL, same headers) up to 2 times with backoff, only while
  the URL is still valid (`expiresIn` seconds since issued). Start the upload immediately after receiving the URL.
- **S3 `PUT` returns `403`, or the URL has expired:** discard the URL and call `upload-url` again for a fresh one, then
  upload again. (A different `objectKey` and token will be issued; that is expected.)
- **`register` fails with `503` or a network error / timeout:** do **not** upload again and do **not** call `upload-url`
  again. Keep `objectKey` and `uploadToken` and retry the identical `register` request (backoff, then a "Retry" button).
  The file is already in S3 and `register` is idempotent.
- **`UPLOAD_TOKEN_EXPIRED`, `UPLOAD_TOKEN_INVALID`, `UPLOAD_NOT_FOUND`, `UPLOAD_VERIFICATION_FAILED`:** start over from
  `upload-url`.
- **Never** call `register` if the S3 `PUT` did not return `200`.
- Disable the Upload button while a run is in progress, and guard against double submission.
- Provide a Cancel that aborts the in-flight XHR/fetch and resets the state.

## Client-side validation (advisory only)

The backend re-validates everything; this is only for fast feedback. Reject before any network call:

- no file, or an empty file (`size === 0`);
- extension not `.pdf` or `.docx` (case-insensitive; look at the last extension, so `resume.pdf.exe` is rejected);
- size over 5 MB (5,242,880 bytes);
- optionally, sniff the first bytes (`%PDF-` for PDF, `PK` for DOCX) to warn early. Treat this as a hint, never as proof.

`<input type="file" accept=".pdf,.docx">` helps the picker but is not validation.

## SHA-256 in the browser

```javascript
export async function sha256Hex(file) {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
```

`crypto.subtle` exists only in secure contexts (HTTPS or `localhost`); say so in the code comment and fail with a clear
message otherwise. The input is the complete binary file, not its name or text. A 5 MB file is fine to hold in memory.
Compute the hash once per selected file and reuse it for both calls.

## UX details

- Show what is happening: hashing, requesting URL, uploading with a **real progress bar**, registering.
- `ALREADY_EXISTS`: show "This resume has already been uploaded." and expose the existing `objectId` to the caller the
  same way a fresh upload would.
- Success: "Resume uploaded successfully", and give `objectId` to the parent so it can be used as
  `documents.resumeObjectId` in the existing create-application request. Do not create the application here.
- Errors: use the repo's notification component. Messages must be plain language, never raw JSON, never a stack trace,
  and never include the URL, token or any server detail.
- **Do not claim the file is scanned, safe or virus-free anywhere in the UI.** The backend performs only basic format
  and integrity checks and **no malware scanning**.
- Accessibility: file input labelled, progress exposed with `role="progressbar"` and `aria-valuenow`, errors announced
  (`aria-live`), buttons keyboard-operable.

## Do

- Reuse the existing API client, auth provider, toast and styles; keep the endpoint paths and base URL in the existing
  API configuration.
- Send exactly `requiredHeaders` on the S3 `PUT`; use `XMLHttpRequest` for real upload progress.
- Keep `objectKey` + `uploadToken` until `register` succeeds, and retry `register` without re-uploading.
- Make the token, URL and checksum-header values opaque: pass them through, never edit, log or persist them.
- Treat all client validation as advisory.

## Do not

- Do not send the JWT / `Authorization` / cookies to S3, and never put AWS credentials or bucket names in source.
- Do not stream the file through the API; the only calls to the backend are the two small JSON requests.
- Do not build an S3 key, choose a path, or send any `recruiterId`; the server decides both.
- Do not hash the file name, path or text content; hash the file's bytes.
- Do not set `Content-Length`; do not re-encode the checksum header.
- Do not call `register` after a failed `PUT`; do not show success before `register` returns success.
- Do not retry `INVALID_FILE_FORMAT` or `DUPLICATE_DOCUMENT` (they will fail the same way).
- Do not add cover-letter UI, a document list, download/preview or delete.
- Do not add dependencies unless the repo has no way to do something essential (it should not need any).

## Tests to write (use the repo's existing framework, for example Vitest + React Testing Library)

Unit tests (mock the network; no real S3):

1. `sha256Hex`: the empty file gives `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; a file whose
   bytes are `abc` gives `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`; the result is 64 lowercase
   hex characters; two files with the same bytes but different names hash equally.
2. Validation: rejects empty file, `.exe`, `resume.pdf.exe`, no extension, over 5 MB; accepts `.pdf`, `.PDF`, `.docx`;
   accepts exactly 5,242,880 bytes.
3. Content-type derivation from the extension (not from `file.type`).
4. Flow: `ALREADY_EXISTS` shows the message, returns `objectId`, makes **no** S3 call and no `register` call.
5. Flow: `UPLOAD_REQUIRED` -> S3 PUT sends exactly `requiredHeaders` (assert every header and that no `Authorization`
   or `Content-Length` is set) -> `register` is called with the issued `objectKey`, `checksum` and `uploadToken`.
6. S3 `PUT` fails (403, 500, network error): `register` is **not** called; the right retry/restart path is taken; an
   expired URL triggers a fresh `upload-url` call.
7. `register` fails with 503 or a network error: no second S3 upload and no second `upload-url`; the identical register
   request is retried; success shows only after it succeeds; repeating returns the same `objectId`.
8. `UPLOAD_TOKEN_EXPIRED` restarts from `upload-url`.
9. `DUPLICATE_DOCUMENT` shows only the generic message.
10. Progress: `xhr.upload.onprogress` events update the progress state (fake the XHR).
11. Cancel aborts the XHR and resets state; double clicks do not start two runs.
12. The URL, token and checksum are never written to storage or logged (spy on `console` and `Storage`).

Component test: select a valid file -> sees progress -> sees "Resume uploaded successfully" and the parent receives the
`objectId`.

## Acceptance criteria

- A recruiter can pick a PDF or DOCX for an assigned student, and after the flow above receives an `objectId`.
- Identical files for the same student show the "already uploaded" message and upload nothing.
- A file registered to another student (or as another type) shows only "This document is already registered."
- Upload progress is real byte-level progress.
- A failed S3 upload never calls `register`; a failed `register` can be retried without uploading again.
- No credentials, tokens or URLs are logged or stored; no JWT is sent to S3.
- No cover-letter, download or delete UI exists. All new tests pass and the existing ones are unaffected.

When you finish, summarize the files you created or changed, the tests you ran with their real results, and anything you
could not verify (for example a live S3 upload requires a running backend and a real bucket).
