# ResumeSearch UI

Angular 22 client for the ResumeSearch backend ([ResumeSearchJava](../ResumeSearchJava)) - upload resumes (PDF/DOC/DOCX,
single or bulk) and search candidates with a natural-language query.

> New to this project? Start with the [top-level README](../README.md) for an overview of what the whole
> app does and how the frontend and backend fit together.

## Stack

Angular 22 (standalone components, signals, zoneless change detection - all defaults now, no extra config
needed), lazy-loaded routes, plain `HttpClient` (via async/await, not `httpResource` - the upload/search actions
here are one-shot button clicks, not continuously-reactive data fetches, so a resource wasn't the right fit).

## Pages

- **Search** (`/search`, default route) - natural-language query -> `POST /query`. Each result card shows
  its matched skill/role scores, a "Matches all filters" / "Partially matches filters (X/Y)" / "Doesn't
  match filters" badge with a per-field breakdown of exactly which requested filters it does and doesn't
  satisfy, and (when relevant) a keyword-match or partial-keyword-match badge for anything found only via
  the raw-text fallback rather than the skill/role taxonomy.
- **Upload Resume** (`/upload`) - pick one or more PDF/DOC/DOCX files; each gets its own collapsible panel
  that calls `POST /extract_resume_text` (also flags an exact-content duplicate of an existing candidate)
  then `POST /suggest_applicant_details`, for review/edit before `POST /upload_resume`. "Save All" saves
  every ready panel at once, waiting for any still-extracting files rather than skipping them, and skips
  panels already saved and unchanged on a repeat click.
- **Admin** (`/admin`) - "Create All Tables" (idempotent schema setup - not normally needed, since the
  backend already does this on startup) plus destructive "Clear All Data" / "Drop All Tables" actions,
  gated behind typing a confirmation phrase. Local testing/demo use only.

The backend also exposes `POST /upload_resumes_bulk` (extracts and fully ingests each file server-side with
no per-file review) but nothing in this UI calls it yet - bulk upload here goes through the same per-file
review flow as a single upload, just with more panels open at once.

## Running

Backend must be running first (see [ResumeSearchJava/README.md](../ResumeSearchJava/README.md)), on
`http://localhost:8000`.

```
npm install
npm start          # ng serve, defaults to http://localhost:4200
```

If port 4200 is already used by something else, run on another port and add it to the backend's CORS config
(`WebCorsConfig.java` - currently allows `4200` and `4300`):
```
npx ng serve --port 4300
```

## Known gaps

- Image-based resumes (scanned `.jpg`/scanned-PDF etc. with no actual text layer) aren't supported - only
  PDF/DOC/DOCX with extractable text. OCR is a possible future addition.
- No authentication - matches the backend's current state.
