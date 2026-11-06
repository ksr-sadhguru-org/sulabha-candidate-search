# AWS design notes - bulk upload

Recommendations from the architecture discussion (2026-10-29), for when the flow in
[architecture.html](architecture.html) is built on AWS. Nothing here is implemented yet. The AWS details come from
general knowledge of the AWS docs and have not been tested - check the current docs before building.

## Decided (2026-10-29)

- The Ingest Lambda passes the S3 link to `POST /upload-resume/{candidate_id}`; the **API** downloads the file, so the
  API needs read access to the bucket.
- The bulk path has **no "suggest the form" step** (see open point 1).
- Failures are recorded by the API in a Postgres table, `failed_resumes`; `GET /failed-resumes` on the Candidate
  Search API reads it. There is no error queue and no Failed-records Lambda.

## Security recommendations

### Exposing the Intake endpoint

- **API Gateway is optional.** A Lambda can expose its endpoint itself through a **Function URL**, a built-in HTTPS
  endpoint. For a single caller (Sulabha) skip API Gateway.
- API Gateway adds throttling and usage plans, API keys, WAF, request validation, a custom domain, ready-made
  authorizers (Cognito / JWT) and IP allow-listing. A Function URL has none of these: the controls are reserved
  concurrency and your own code. Add API Gateway if you want those built in - for example when Sulabha cannot sign
  requests (below) and you want throttling or WAF.
- API Gateway does not do basic auth either; it would take a Lambda authorizer.
- `/failed-resumes` is on the Candidate Search API, so it is protected by the API's own auth (below), not by this.

### Authenticating the caller

A Function URL has two auth modes, `AWS_IAM` and `NONE`. Neither is basic auth.

| | `AWS_IAM` (preferred) | `NONE` + basic auth / shared secret |
|---|---|---|
| How it works | AWS checks a SigV4 signature on the request before your code runs | The URL is public; your code checks the `Authorization` header itself |
| Unauthenticated calls | Rejected by AWS (403) - the Lambda never runs, no cost | Reach the Lambda, which returns 401 (still an invocation) |
| What Sulabha needs | Server-side calling code that can use AWS credentials and sign requests (SigV4) | Nothing special |
| Secret | IAM credentials - prefer a role, so there are no long-lived keys | A password in Secrets Manager, rotated |
| Weak spots | The credentials must stay server-side, never in browser JavaScript | Guessable or brute-forceable (no lockout, no WAF); junk calls are capped only by reserved concurrency |

Setting up `AWS_IAM`:

1. Create the Function URL with auth type `AWS_IAM` on the Intake Lambda.
2. Give Sulabha an IAM identity: a role if Sulabha runs on AWS (same or another account), otherwise an IAM user with
   access keys.
3. Allow that identity to call the URL: a policy with `lambda:InvokeFunctionUrl` on the function ARN. Another account
   also needs a resource policy on the Lambda. Check the current Lambda docs for any companion permission.
4. Sulabha's backend signs every request with SigV4 (service `lambda`, the function's region). The AWS SDKs and common
   libraries do this; Postman has an "AWS Signature" auth type for testing.

SigV4 in one line: the caller computes a signature over the request (method, URL, headers, body) with its AWS secret
key and sends it in the `Authorization` header; AWS recomputes it and compares.

Either mode needs the call to come from a server. A secret or AWS key in browser JavaScript is visible to anyone who
opens the dev tools.

### Hardening checklist (either mode)

- Reserved concurrency on both Lambdas (caps cost and abuse); on the Ingest Lambda, cap it to the LLM rate limit.
- Limit the records per request (for example 1,000-5,000) and the body size; reject anything larger.
- The API downloads from S3, so give its task role read-only access to the Isha bucket and prefix alone, and have it
  refuse any link outside that bucket - an endpoint that fetches whatever link it is sent can be misused. For
  pre-signed URLs, allow-list the host instead.
- Keep resume content out of error messages, `failed_resumes` rows and logs; never log the `Authorization` header.
- Encrypt the work queue (SSE).
- With basic auth: password in Secrets Manager, long and random, rotated, compared in constant time, 401 before the
  body is read.

### The Candidate Search API itself

- Basic auth is acceptable only over HTTPS.
- Roles: UI (search, `/failed-resumes`), ingest (`/upload-resume/{id}`), admin. `/upload-resume/{id}` accepts only the
  Ingest Lambda's credential.
- The destructive endpoints (`/clear_all_data`, `/drop_all_tables`, `/create_all_tables`, `DELETE /query_cache`):
  admin only, or disabled in production.
- CORS: restrict to Sulabha's origin (today it allows only `http://localhost:4200`).
- Longer term: Cognito / OIDC with JWTs.

## Open points - revisit when the flow is redone for the AWS migration

1. **Applicant form fields.** Today the form (name, email, phone, job location, languages, meditator, stays in ashram,
   Isha program, notice period, experience range ...) is filled by the "suggest" step and confirmed by the user. The
   search filters read these fields, not the resume text, and the same-person match (same email or phone = update that
   candidate) uses them too. With no suggest step the fields are empty, so location and similar filters show "not on
   file" and that match does not work. Options: Sulabha sends the details with each record; the API suggests them with
   the LLM (the step that was dropped); or accept "not on file" for bulk-loaded candidates.
2. **New endpoint.** `/upload-resume/{id}` (hyphen, id in the path, S3 link instead of text) runs the whole pipeline in
   one call. Today the id is in the body of `/upload_resume` (underscore) and the UI makes three calls.
3. **Duplicates and retries.** A duplicate resume is skipped, not recorded as a failure; the same id sent again
   updates that candidate, so a queue retry never creates a second record.
4. **Failure handling details.** A failure the API reports (bad file, LLM error) is final: the API records it and the
   message is consumed. A failure to reach the API at all cannot be recorded in Postgres (the Lambda has no database
   access); SQS retries it, and an SQS dead-letter queue would be the safety net. Also open: the `failed_resumes`
   columns, and whether a row is cleared when the same candidate id later saves successfully.
5. **Caller of the endpoints.** Does Sulabha call from a backend or straight from the browser? This decides whether
   `AWS_IAM` is possible.
