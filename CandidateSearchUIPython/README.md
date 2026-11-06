# Candidate Search UI - Python

A NiceGUI port of [CandidateSearchUI](../CandidateSearchUI) (Angular): the same three pages - Search, Upload Resume, Admin - with the
same features and the Angular app's CSS. The Angular UI stays untouched.

| | Angular UI | Python UI |
|---|---|---|
| Port | 4200 | 4201 |
| Talks to | the API at `localhost:8000`, from the browser | the API at `API_URL` (default `http://localhost:8001`, the Python API), from the server |
| Runs as | static files | a Python server process (needs a container of its own) |

## Run

- **Docker:** `docker compose up -d --build` - http://localhost:4201. `API_URL` points it at another API (the Java API is
  `http://host.docker.internal:8000`).
- **Local:** `python -m venv .venv`, `.venv\Scripts\pip install -r requirements.txt`, then
  `$env:API_URL="http://localhost:8001"; .venv\Scripts\python -m app.main`.

Start the API first (`../CandidateSearchAPIPython`, or the Java one).

## Test

- `.venv\Scripts\pip install -r requirements-dev.txt`, then `.venv\Scripts\python -m pytest` - helper and API-client unit tests, and the
  pages driven by NiceGUI's simulated user against a fake backend (no browser, no LLM).
- `tests/e2e_check.py` - the real thing in a **visible Chrome window** (don't click in it): search and Load more, open and save a record,
  upload two resumes, Save All, tab state, Admin. Needs the UI, an API with a working LLM key and the test data; see the file's header.

## How it maps to the Angular app

| Angular | Python (`app/`) |
|---|---|
| `app.html` shell, router, keep-alive route strategy | `main.py` - one page with three kept-alive tab panels; the nav switches tabs and updates the URL |
| `core/api.service.ts`, `models.ts` | `api.py` (httpx), `models.py` (constants, display helpers; the JSON stays plain dicts) |
| `core/search-state.service.ts`, `features/search` | `search_page.py` - the state lives in the page object, which lives as long as the browser tab |
| `features/upload` | `upload_page.py` - one panel per file, extraction in background tasks, Save All |
| `features/admin` | `admin_page.py` |
| `shared/expandable-panel`, `shared/candidate-record-editor` | `components.py` - `ExpandablePanel`, `RecordEditor` |
| the `*.css` files | `styles.css`, merged |

## Differences from the Angular UI

- Editable dropdowns (Gender, Experience, ...) are Quasar selects that accept typed values, not browser `datalist` inputs.
- Badge tooltips are Quasar tooltips; the file picker is Quasar's uploader.
- State is kept on the server per browser tab: reloading the page starts fresh (as the Angular app does), and a restart of the UI
  server drops every open tab's state.
- The Angular app shows a "Save failed" message that always blames the backend being down; this one shows the backend's own message when it sent one.
