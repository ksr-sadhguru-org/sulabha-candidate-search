# CandidateSearchAPI

Spring Boot backend for Sulabha: API on **8000**, Postgres on **5432**, database `candidate_db`.

## Run

Copy `.env.example` to `.env` (gitignored) and fill in the AI settings - the file explains OpenAI and Azure OpenAI.
Step-by-step setup for new users: [Getting started](../README.md#getting-started).

```powershell
docker compose up -d                                  # start
docker compose up -d --build; docker image prune -f   # after code changes
mvn -q -B test                                        # unit tests
```

## How it works

**Upload** - one LLM call builds a *search profile*: one entry per area of expertise with its relevant
years and every term that should find it ("senior java developer", "java developer", "java", "spring boot").
The form's skill competencies (`Java (2 years), ...`, reviewed by HR) are stored as entries too. Scores are
computed in code from years (same years, same score), minus a little for skills unused in 5+ years.
A resume with the same email or phone (and a compatible first name) as an existing candidate updates that
candidate instead of creating a new one.

**Search** - one LLM call (cached per query) turns the query into:
- `must` - profession and skills a candidate needs, each with equivalents and optional years ("8+ years");
- `nice` - extras that only help ranking ("senior");
- `filters` - location (City, State, Country), languages, meditator, stay in ashram, ...

A must-have matches a profile term that equals it or ends with it ("teacher" finds "music teacher"); failing
that, the resume text (Postgres full-text search). Filters are shown per candidate as pass / fail / not on file.

**Ranking** - how the must-haves were found (profile > equivalent or degree > resume text), then filters
passed, then extras met, then score, then relevant years.

Unclear, vague and no-match queries return a `message` instead of results.

## Tables

| Table | Holds |
|---|---|
| `candidates` | resume text, fingerprint, form fields, location parts, full-text index |
| `expertise` | one row per area of expertise (from the resume or the form) |
| `expertise_terms` | normalized terms that find each expertise row |
| `query_cache` | each query's parsed form |

## Endpoints

`POST /extract_resume_text` · `POST /suggest_applicant_details` · `POST /upload_resume` ·
`POST /upload_resumes_bulk` · `GET /candidates` · `GET /candidates/{id}` · `POST /query` ·
`DELETE /clear_all_data` · `DELETE /drop_all_tables` · `POST /create_all_tables`
