# Python port - status and handoff

Written so work can continue on any machine: read this, then the READMEs of
[CandidateSearchAPIPython](../CandidateSearchAPIPython/README.md) and [CandidateSearchUIPython](../CandidateSearchUIPython/README.md).
Last updated: 2026-11-06. No secrets or local paths belong here.

## Goal

Port the stack to Python, in this order, keeping the Java and Angular versions untouched until the ports are verified:

1. `CandidateSearchAPI` (Spring Boot / Java) -> **`CandidateSearchAPIPython`** (FastAPI).
2. `CandidateSearchUI` (Angular) -> **`CandidateSearchUIPython`** (NiceGUI).

## Decisions

- API: FastAPI, psycopg 3 with a connection pool, Pydantic, httpx for the LLM call, pdfminer.six (PDF), python-docx (DOCX),
  olefile (legacy `.doc`). Same endpoints, JSON (snake_case), Postgres schema and prompts as the Java API.
- UI: NiceGUI, chosen over Streamlit because the Angular app relies on things Streamlit handles badly: result cards that load
  their detail when opened, one upload panel per file extracting in the background with a live status, page state kept when
  you navigate away, and badge tooltips. NiceGUI keeps state on the server per browser tab and calls the API with httpx.
  A Python UI is not static files: it needs a running server (an extra container), unlike the Angular build.
- Ports: Java API 8000, Python API 8001, Angular UI 4200, Python UI 4201.
- The Python API uses its own database `candidate_db_py` in the same Postgres container as the Java API's `candidate_db`,
  so `--fresh` against it never touches the Java data.
- `Tests/run-tests.mjs` reads the API address from the `API_URL` environment variable (default `http://localhost:8000`).

## Status

Both ports are built and verified except for one thing: **the live LLM** (see Open items).

**API port: done** (not committed when this was written - check `git status`).

| Check | Result |
|---|---|
| Unit tests (`pytest`) | 13 pass |
| `Tests/run-tests.mjs` on the loaded data, Java baseline 230/230 | 230/230, also from the Docker image |
| `Tests/run-tests.mjs --fresh`, Java baseline 234/234 | 234/234, with the LLM answers replayed from the Java data (live LLM unavailable) |
| Prompts | identical to the Java text blocks |
| Extracted resume text, 35 test resumes | byte-identical to Java's, so duplicate hashes agree |
| Stored rows after an upload (profile JSON, 347 expertise rows, domains) | identical to Java's |
| Query responses, 66 cached queries | 61 identical; 5 differ only in which equal-score term is `via_term` (Postgres row order) |
| Bulk upload, errors, CORS | checked by hand |

**UI port: done** (not committed when this was written).

| Check | Result |
|---|---|
| Unit and page tests (`pytest`, NiceGUI simulated user, fake backend) | 18 pass |
| `tests/e2e_check.py` in a visible Chrome: search, Load more, open and save a record, upload two resumes, Save All, tab state, Admin | 15/15, no console errors (API had the replayed LLM answers) |
| Docker image | builds, healthy, reaches the API container through the host |

The three pages, the shared expandable panel and record editor mirror the Angular structure (mapping table in the UI README). Known
differences: editable dropdowns are Quasar selects, tooltips and the file picker are Quasar's, state lives on the server per tab.

## How to run and test

- API: the Java compose project provides Postgres (`cd CandidateSearchAPI; docker compose up -d`); create the database once
  (`docker exec candidatesearch-db-1 psql -U postgres -c "CREATE DATABASE candidate_db_py"`); then
  `cd CandidateSearchAPIPython; docker compose up -d --build` serves it on http://localhost:8001.
  Scenarios: `API_URL=http://localhost:8001 node Tests/run-tests.mjs` from the repo root.
- UI: `cd CandidateSearchUIPython; docker compose up -d --build` serves it on http://localhost:4201 (`API_URL` picks the API).
- UI tests: `pip install -r requirements-dev.txt`, `python -m pytest`; the visible-Chrome check is `tests/e2e_check.py`.

## Open items

- **LLM key.** On 2026-11-06 the Azure OpenAI key in `CandidateSearchAPI/.env` answered `401 PermissionDenied` to the Java
  API as well as the Python one (same key, same endpoint), so the live LLM was never exercised from Python. Renew the key
  (or check network access to the Azure resource), then run `API_URL=http://localhost:8001 node Tests/run-tests.mjs --fresh`
  (wipes only `candidate_db_py`) and `tests/e2e_check.py` to confirm the real LLM path.
- Legacy binary `.doc` extraction (a small parser over `olefile`) has not run on a real `.doc` - the test resumes are PDF and DOCX.
- Behaviour differences from the Java API (bad requests answer 400, not 500; `via_term` order) are listed in the API README.
- Not done yet: mention the new folders in `README.md`, add the Python API to the Postman collection notes, decide whether to
  retire the Java API and Angular UI once the Python ones have run on the live LLM (the Python API's compose file would then
  need its own `db` service).
- Related, separate topic: [aws-design-notes.md](aws-design-notes.md) and the architecture diagrams in [architecture.html](architecture.html).
