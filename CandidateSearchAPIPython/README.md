# Candidate Search API - Python

A Python (FastAPI) port of [CandidateSearchAPI](../CandidateSearchAPI): same endpoints, same JSON, same Postgres schema,
same prompts. The Java API stays untouched while this one is checked against it.

| | Java API | Python API |
|---|---|---|
| Port (default) | 8000 | 8001 (Docker) - or whatever you pass to uvicorn |
| Database | `candidate_db` | `candidate_db_py` (same Postgres container) |
| Stack | Spring Boot 4.1, Java 25 | FastAPI, psycopg 3, Pydantic, httpx, pdfminer.six, python-docx, olefile |

## Run

Postgres is the container of the Java compose project (`cd ../CandidateSearchAPI; docker compose up -d`). Create the
database once: `docker exec candidatesearch-db-1 psql -U postgres -c "CREATE DATABASE candidate_db_py"`. The API creates
its tables on start.

Copy `.env.example` to `.env` (same settings as the Java API's; the real `.env` is gitignored) and add `DB_NAME=candidate_db_py`.

- **Docker:** `docker compose up -d --build` - the API on http://localhost:8001. Health: `curl.exe http://localhost:8001/actuator/health`.
- **Local:** `python -m venv .venv`, `.venv\Scripts\pip install -r requirements.txt`, then
  `.venv\Scripts\python -m uvicorn app.main:app --port 8001 --reload`.

## Test

- Unit: `.venv\Scripts\python -m pytest` (needs `pip install pytest`).
- Scenarios, from the repo root: `$env:API_URL="http://localhost:8001"; node Tests/run-tests.mjs` (`--fresh` wipes
  `candidate_db_py` only, and needs a working LLM key). Java baseline: `--fresh` 234/234, current data 230/230.

## Layout

`app/` mirrors the Java packages: `config`, `models` (the DTOs), `db/` (repositories, schema), `extraction`, `llm/`
(client, mock, prompts), `search/` (terms, domains, roles, locations, skill competencies), `service/`, `web/` (routes, errors).

## Differences from the Java API

- A bad request (blank query, missing field, invalid JSON, no file) answers **400** with `{"message": ...}`; Java answers 500.
- Text extraction uses pdfminer.six and python-docx instead of PDFBox and POI. On the 35 test resumes the text is
  byte-identical to Java's, so duplicate hashes agree. Legacy binary `.doc` is read by a small parser over `olefile`
  (the test resumes are PDF and DOCX only, so that path has not been run on a real `.doc` yet).
- `via_term` on a matched entry can name a different one of two equal-score terms than Java does - both come back in
  whatever order Postgres returns them.
