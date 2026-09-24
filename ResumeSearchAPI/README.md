# ResumeSearch (Java / Spring Boot)

Spring Boot backend for Sulabha — resume upload/extraction, LLM-based applicant-detail suggestion, and
natural-language candidate search.

> New to this project? Start with the [top-level README](../README.md).

## Stack

Java 21, Spring Boot 4.1 (`JdbcClient`, no JPA/entities), `RestClient` against any OpenAI-compatible
`chat.completions` endpoint, Maven.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `POST /extract_resume_text` | Extract text from a PDF/DOC/DOCX; flags an exact-content duplicate |
| `POST /suggest_applicant_details` | LLM-suggested applicant fields from resume text, for review |
| `POST /upload_resume` | Save one candidate (upserts by `uniquefile_id`) |
| `POST /upload_resumes_bulk` | Ingest a batch server-side, no per-file review |
| `GET /candidates/{id}` | Full stored record for one candidate |
| `POST /query` | Natural-language search |
| `POST /create_all_tables` | Idempotent schema setup (also runs on startup) |
| `DELETE /drop_all_tables` / `DELETE /clear_all_data` | Destructive — schema reset / data wipe |

## Search matching

Each query resolves in three tiers — skill/role match, then keyword match, then partial keyword match
(weakest) — and every result also reports which requested filters (location, experience, etc.) it does
and doesn't satisfy, independent of which tier found it.

## Configuration

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `RESUME_DB_NAME` | Postgres connection |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`, `OPENAI_QUERY_MODEL` | LLM config — blank base URL means public OpenAI, otherwise Azure |
| `MOCK_LLM` | `true` to bypass real LLM calls (deterministic, no API key needed) |

## Running

Set the variables above in a `.env` file in this folder (gitignored; Compose reads it automatically), then:

```powershell
docker compose up -d                                  # normal start - reuses the existing image
docker compose up -d --build; docker image prune -f   # after code changes; prune drops the old <none> image
```

Runs on port 8000 (Postgres on 5432). Without Docker: `mvn spring-boot:run` against your own Postgres.

## Known gaps

- No authentication, no automated test suite
- Scanned/image-only resumes can't be extracted (no OCR)
