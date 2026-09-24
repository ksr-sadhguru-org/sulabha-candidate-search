# Sulabha — AI-Powered Candidate Search

Sulabha replaces exact-match, query-based candidate search with **natural-language search**: an HR user
types a plain-English description of who they're looking for (e.g. *"developer with 5+ years Java
experience, based in Mumbai"*) instead of constructing a precise query the old way, and the app uses an
LLM to understand both that request and the resumes on file, then explains exactly why each candidate was
matched.

This document is an orientation for someone who has never seen this codebase before. For implementation
detail, see the two sub-project READMEs it links to below.

## What the app actually does

**Uploading a resume**: HR picks one or more PDF/DOC/DOCX files. The backend extracts the raw text, an LLM
suggests applicant details (name, qualification, experience, etc.), and HR reviews/edits before saving.
Resumes are also fingerprinted by content, so re-uploading the exact same file a second time is detected
and skipped rather than silently creating a duplicate candidate record.

**Searching for a candidate**: HR types a free-text description. An LLM breaks that down into:
- **Required skills** (e.g. "Java") — a candidate must have every one of these to get a strong match
- **Optional roles/titles** (e.g. "Developer") — scored when present, but don't exclude a candidate who
  lacks the exact job-title wording, since resumes phrase titles too inconsistently to treat as a hard
  requirement
- **Structured filters** (location, experience, qualification, salary, etc.) — checked independently
  per field, so a result can be shown as fully matching, partially matching (with a breakdown of exactly
  which filters it does and doesn't satisfy), or not matching at all

If nothing in the skill/role taxonomy matches, a raw-text keyword fallback still catches a literal mention
of the search terms anywhere in a resume — visibly labeled as a weaker "keyword match" (or "partial
keyword match" when the word only appears as a substring inside another word), so results are never a
black box: every candidate's badge shows *why* they showed up.

## Project layout

```
sulabha-resume-search/
├── ResumeSearchUI/      Angular frontend - what HR actually uses
├── ResumeSearchJava/    Spring Boot backend - API, database, LLM integration
├── ResumeSearch/        legacy Python prototype - superseded, not part of the running app
└── Sample Resumes/      local test data, not part of the app itself
```

`ResumeSearchUI` and `ResumeSearchJava` are two independent git repositories that run together - the
frontend is just a browser client of the backend's HTTP API.

## Technical stack

| Layer | Technology |
|---|---|
| Frontend | Angular 22 (standalone components, signals), TypeScript |
| Backend | Java 21, Spring Boot 4.1 (`JdbcClient` for raw SQL - no ORM/JPA) |
| Database | PostgreSQL |
| Resume text extraction | Apache PDFBox (PDF), Apache POI (DOC/DOCX) |
| AI / LLM | Any OpenAI-compatible `chat.completions` endpoint - public OpenAI or Azure OpenAI |
| Backend deployment | Docker / Docker Compose (backend + Postgres) |

## Prerequisites

- **Docker Desktop** - runs the backend and its Postgres database together. (Alternative: Java 21+, Maven,
  and your own Postgres instance, if you'd rather not use Docker - see the backend README.)
- **Node.js + npm** - to run the frontend.
- **An OpenAI or Azure OpenAI API key** - for real search/extraction quality. Without one, the backend can
  run in a `MOCK_LLM=true` mode instead: a deterministic, keyword-based stand-in with no API key and no
  cost, good enough to click through the app locally but not representative of real search quality.

## Running it

The backend must be started first - the frontend is just a client of it.

### 1. Backend

```powershell
cd ResumeSearchJava
$env:OPENAI_API_KEY = "..."   # or set MOCK_LLM=true instead - see below
docker compose up --build -d
```

This starts the API on `http://localhost:8000` and a Postgres container alongside it. The database schema
is created automatically on startup - no manual setup step needed. Full configuration options (Azure
OpenAI setup, running without Docker, all environment variables) are in
**[ResumeSearchJava/README.md](ResumeSearchJava/README.md)**.

To try the app without an API key:
```powershell
cd ResumeSearchJava
$env:MOCK_LLM = "true"
docker compose up --build -d
```

### 2. Frontend

```
cd ResumeSearchUI
npm install
npm start
```

Opens on `http://localhost:4200`. Details in **[ResumeSearchUI/README.md](ResumeSearchUI/README.md)**.

### 3. Open the app

Visit `http://localhost:4200` in a browser. It redirects to the Search page.

## Using the app

- **Search** (`/search`, the default page) - describe who you're looking for in plain language and press
  Search (or `Ctrl+Enter`). Each result shows which skills/roles matched with a score, whether it matched
  via the raw-text keyword fallback instead, and a breakdown of which of your requested filters (location,
  experience, etc.) that candidate does and doesn't satisfy.
- **Upload Resume** (`/upload`) - pick one or more resume files. Each gets its own panel that auto-extracts
  text and suggests applicant details; review/edit, then save individually or use "Save All" to save every
  ready panel at once (it waits for any files still being processed rather than skipping them).
- **Admin** (`/admin`) - schema management and destructive data-clearing, gated behind typing a
  confirmation phrase. For local testing/demo use only.

## Good to know

- **No authentication** on any endpoint (including the destructive admin actions) - this app is not safe
  to expose outside a trusted network as-is.
- **Scanned/image-only resumes aren't supported** - if a PDF has no actual text layer (e.g. a photographed
  or scanned document), nothing can be extracted from it, and saving it is correctly rejected rather than
  silently storing an empty record.
- **A resume whose extracted text exactly matches one already on file is detected as a duplicate** and
  skipped automatically, rather than creating a second candidate record and re-paying for LLM processing.
  This is based on the extracted text content, not the raw file bytes, so a re-exported or re-saved copy
  of the same resume (different bytes, same wording) is still caught.
- The search matching logic is deliberately transparent rather than a black-box ranking: every result
  badge explains specifically which requested skill, keyword, or filter caused that candidate to appear,
  and to what degree.

## Where to go next

- **[ResumeSearchUI/README.md](ResumeSearchUI/README.md)** - frontend pages, stack details, running/build commands
- **[ResumeSearchJava/README.md](ResumeSearchJava/README.md)** - backend endpoints, configuration, project layout, database schema
