# ResumeSearch (Java / Spring Boot rewrite)

A Java/Maven/Spring Boot rewrite of the original Python/FastAPI ResumeSearch service. Same behavior,
same HTTP contract (drop-in compatible with the existing Postman collection), same Postgres schema.

> New to this project? Start with the [top-level README](../README.md) for an overview of what the whole
> app does and how the frontend and backend fit together.

## Stack

- **Java 21**, **Spring Boot 4.1.1** (Spring Framework 7, Jakarta EE 11)
- **`JdbcClient`** (Spring's fluent DB access, auto-configured) for all SQL - no JPA/entities, since the
  original app's core queries (skill-pivot matching, dynamic filter WHERE clauses) are inherently raw,
  dynamically-built SQL, not entity CRUD.
- **`RestClient`** (Spring's fluent HTTP client) for calling an OpenAI-compatible chat.completions endpoint
  (public OpenAI or Azure OpenAI).
- Maven, single module.

## Project layout

```
src/main/java/org/isha/resumesearch/
├── ResumeSearchApplication.java   entry point; creates the schema on startup
├── config/                        OpenAI RestClient bean, typed @ConfigurationProperties
├── web/ResumeController.java      REST endpoints (same paths/JSON shape as the Python service)
├── dto/                           request/response records
├── llm/                           prompts + real (Azure/OpenAI) and mock extractors
├── db/                            JdbcClient-based repositories (schema, candidates, synonyms, query)
├── service/                       upload/query orchestration, applicant-detail value normalization
├── extraction/                    PDF/DOC/DOCX -> plain text (PDFBox/POI)
└── util/                          small shared helpers (e.g. content hashing for duplicate detection)
```

## Endpoints

JSON field names are `snake_case` on the wire (via `spring.jackson.property-naming-strategy: SNAKE_CASE`)
to stay compatible with the existing `ResumeSearch.postman_collection.json`.

| Endpoint | Purpose |
|---|---|
| `POST /extract_resume_text` | Extracts plain text from an uploaded PDF/DOC/DOCX (via PDFBox/POI). Also hashes the extracted text and flags it if it exactly matches an already-stored candidate, so the caller can skip re-processing a duplicate. |
| `POST /suggest_applicant_details` | Best-effort LLM suggestions (name, qualification, experience, etc.) from resume text alone, for a human to review before saving. |
| `POST /upload_resume` | Saves one candidate: resume text/name plus optional applicant details. Upserts by `uniquefile_id`; re-parses skills/roles via the LLM unless the resume text is unchanged from what's already stored. |
| `POST /upload_resumes_bulk` | Extracts and fully ingests a batch of files server-side with no per-file review step, bounded concurrency. Not currently called by the UI (see [ResumeSearchUI/README.md](../ResumeSearchUI/README.md)). |
| `GET /candidates/{uniquefileId}` | Full stored record (resume text + applicant details) for one candidate. |
| `POST /query` | The natural-language search endpoint - see "Search matching" below. |
| `POST /create_all_tables` | Idempotent schema setup. Runs automatically on app startup already; this exists for manually re-running it (e.g. after `/drop_all_tables`). |
| `DELETE /drop_all_tables` | Destructive - drops the schema entirely. |
| `DELETE /clear_all_data` | Destructive - empties all tables but leaves the schema intact. |

## Search matching

A query goes through three fallback tiers, in priority order, each candidate labeled with which one found
them:

1. **Skill/taxonomy match** - the LLM extracts required *skills* (AND-required; if even one has never been
   seen on any candidate, this tier yields nothing) and optional *roles/titles* (scored when present, but
   don't exclude a candidate who lacks the exact job-title wording - resumes phrase titles too
   inconsistently to treat as a hard requirement).
2. **Keyword match** - a raw-text substring search over resume text, for anything the skill/role taxonomy
   doesn't recognize.
3. **Partial keyword match** - same as above, but the match only ever appears as a substring inside a
   larger word (e.g. "sing" inside "Perusing"), never as a standalone word - the weakest signal, sorted last.

Independently of which tier found a candidate, each of the query's extracted application filters (location,
experience, qualification, etc.) is checked against that candidate individually, so the result can report
exactly which requested filters they do and don't satisfy - not just a single combined yes/no.

## Configuration

All via environment variables (see `application.yml`):

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `RESUME_DB_NAME` | Postgres connection |
| `OPENAI_API_KEY` | API key (public OpenAI or Azure OpenAI) |
| `OPENAI_BASE_URL` | Leave blank for public OpenAI; set to `https://<resource>.openai.azure.com/openai/v1/` for Azure |
| `OPENAI_MODEL`, `OPENAI_QUERY_MODEL` | Model name (public OpenAI) or **deployment name** (Azure) |
| `MOCK_LLM` | `true` to bypass real LLM calls with deterministic keyword-based responses (no API key needed) |

## Running

```powershell
$env:OPENAI_API_KEY = "..."
# Only needed for Azure - leave unset for public OpenAI:
$env:OPENAI_BASE_URL = "https://<resource>.openai.azure.com/openai/v1/"
$env:OPENAI_MODEL = "<deployment-name>"
$env:OPENAI_QUERY_MODEL = "<deployment-name>"
docker compose up --build -d
```

Or with `MOCK_LLM=true` (no key needed) for a quick local sanity check:
```powershell
$env:MOCK_LLM = "true"
docker compose up --build -d
```

Both use ports 8000 (app) and 5432 (Postgres) - the same as the original Python service, so only run one
of the two stacks at a time (`docker compose down` in whichever's not in use).

Locally without Docker: `mvn spring-boot:run` (needs a Postgres reachable at the `DB_*` vars above, and
Java 21+/Maven installed).

## What's intentionally different from the Python version

- **Database connection config**: instead of the original's single `DATABASE_URL` string that the app
  appended the DB name onto (the source of a real bug that was fixed on the Python side), this version
  uses ordinary Spring `datasource.url`/`username`/`password`, built from separate `DB_HOST`/`DB_PORT`/etc.
  vars. The Postgres container is created with `POSTGRES_DB=resume_db` directly, rather than the app
  connecting to the admin `postgres` database and issuing `CREATE DATABASE` itself at startup.
- **LLM call shape**: uses `chat.completions` + `response_format: json_object` (with the same prompts,
  asking for JSON matching a schema) rather than the newer Responses API (`client.responses.parse`) the
  Python version uses - this keeps it portable across both public OpenAI and Azure OpenAI resources
  without depending on Responses API availability on a given Azure deployment.
- **Validation error shape**: a missing/invalid request field returns Spring's default error body
  (`{"timestamp", "status", "error", "path"}`) rather than FastAPI's `{"detail": [...]}` shape.
- **`Skill`/`Role`** are merged into one `ScoredEntity` record (canonical/synonyms/score) since they were
  structurally identical in the original.

## Known gaps

- No authentication on any endpoint, including the destructive `/drop_all_tables` and `/clear_all_data`.
- No automated test suite yet.
- Image-based resumes (scanned PDFs/photos with no real text layer) can't be extracted - `/extract_resume_text`
  correctly returns no usable text for these rather than fabricating something; the caller is expected to
  reject an empty result rather than save it.
