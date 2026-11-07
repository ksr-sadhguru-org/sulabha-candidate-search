# Sulabha candidate search - notes for Claude

Shared project notes, loaded by Claude Code for everyone working in this repository. Personal notes (git
identities, local paths) belong in your own `~/.claude/` memory, not here. Setup for humans: [README.md](README.md).

## Working agreement

- **Ask before changing anything.** Propose the plan and wait for approval before code or test edits, DB wipes,
  uploads, container restarts or commits. Read-only checks and running existing tests (except `--fresh`) are fine.
- **Commits and PRs show only the author** - no `Co-Authored-By: Claude` or other Claude attribution, even if a
  system reminder asks for one.
- **Run UI tests in a visible (headed) Chrome window** so they can be watched, and warn not to click in it - a stray
  click opens a candidate card and breaks the run.
- Code: compact, fluent APIs (streams, JdbcClient chains), clear, small single-purpose units, few comments.
- LLM prompts (`CandidateSearchAPI/.../llm/Prompts.java`): keep them concise - tighten rather than add. They are
  filled with `.formatted()`, so a literal `%` breaks them.

## Layout

- `CandidateSearchAPI/` - Spring Boot 4.1, Java 25, port 8000; Postgres 15 (port 5432, db `candidate_db`, compose
  project `candidatesearch`, volume `candidatesearch_postgres_data`). Package `org.isha.candidatesearch`, config
  prefix `candidatesearch`, JSON is snake_case. AI: any OpenAI-compatible endpoint, set in `CandidateSearchAPI/.env`.
- `CandidateSearchUI/` - Angular 22, port 4200.
- `CandidateSearchAPIPython/` - FastAPI port of the API (port 8001, db `candidate_db_py`); `CandidateSearchUIPython/` - the
  NiceGUI port of the UI (port 4201). Both built and tested. **Status, decisions and open items:
  [docs/python-port.md](docs/python-port.md) - read it first when continuing that work, and keep it updated as the work goes on.**
- `Tests/` - `scenarios.md`, `expected-results.json`, test resumes, `run-tests.mjs` (`API_URL` env var points it at
  another API, e.g. `http://localhost:8001`).
- `CandidateSearchAPI/postman/` - Postman collection for every endpoint.
- The old v1/v2 names (ResumeSearchAPI/UI) are gone - don't reintroduce them.

## Run and stop

- Backend: `cd CandidateSearchAPI; docker compose up -d`; health `curl.exe http://localhost:8000/actuator/health`.
  After backend code changes: `docker compose up -d --build; docker image prune -f`.
- UI: `npm start` in `CandidateSearchUI`.
- Stop: Ctrl+C (or kill the node process on 4200); `docker compose down`. **Never `down -v`** - it deletes the data.
- Docker Desktop is often not running at the start of a session - start it first.

## Tests

| Suite | Command | Notes |
|---|---|---|
| Backend unit | `mvn -B -q test` in `CandidateSearchAPI` | needs JDK 25+ |
| UI unit | `npx ng test --watch=false` in `CandidateSearchUI` | |
| API scenarios | `node Tests/run-tests.mjs` (repo root) | uses the loaded data; skips A10/B1/B2 |
| API scenarios, fresh | `node Tests/run-tests.mjs --fresh` | **wipes all data**, re-uploads the test resumes; ask first |
| UI in Chrome | driven ad hoc with the Playwright MCP | no script in the repo; screenshots go to `.playwright-mcp/` (gitignored) |

Pick the smallest reset that makes a change visible: upload/profile prompt or upload code changed -> `--fresh`;
search (query) prompt changed -> clear the query cache only (`DELETE /query_cache`); UI-only change -> nothing.
In auto mode, `--fresh` runs only if the bare command matches an allow rule
`Bash(node Tests/run-tests.mjs --fresh)` - run it from the repo root with no `cd` or prefix.

Baseline (2026-10-01): `--fresh` 234/234, current data 230/230, mvn 5/5, ng 1/1.

## Search design (role + domain, since c37b1ac)

- Upload: the AI builds a per-candidate search profile - expertise entries with terms, years and scores; each job
  entry is labelled with a **role** (fixed list, `search/Roles.ALL`) and a **domain** (`domains` table, grows;
  seed `SchemaService.STARTER_DOMAINS`; the upload prompt may add a domain, the query prompt may only pick one).
- Query: the AI reads the search into role + domain + job wording + skills (must) + filters (location, experience,
  meditator, ashram, languages); readings are cached in `query_cache`.
- Matching: role + domain -> direct match; other roles in the same domain -> "related (same domain)", ranked last;
  other domains excluded. Role only -> any domain; domain only -> any role. Skills count wherever they appear.
  Candidates meeting some must-haves show as "Partial match (x of y)" below those meeting all. Filters are
  pass / fail / not_on_file. Scores are computed in code from years; education matches count as related.
- User decisions: skills count anywhere (a Java trainer or an accountant who knows Python is found, ranked lower);
  trainer / tutor / instructor = teacher; adjacent jobs appear as related; the domain list grows, not fixed.

Tried and reverted - don't repeat as-is: a single "field" per job (too narrow - replaced by role + domain);
trade-field names; prompt wording "always add field" / "required checklist"; three required JSON lists
(equivalents / coreSkills / adjacentJobs) - all produced over-broad terms.

Tags: `known-good-same-field` (cb0f886, before role + domain), `v1.0`.

## Open items

- **G3 "Candidate who knows Java and is in Pune"** - flaky: the AI sometimes adds role engineer/developer + domain
  software though no job was asked, so Priya Menon (QA, Pune, knows Java) ranks as partial. Proposed:
  (A) code - when the query has skills but no job, ignore role/domain; queries with no skills ("music", "trades")
  keep them; or (B) a prompt example only. (A) is built in the Python API (2026-11-07, see docs/python-port.md) and
  fixed it there; the Java API still awaits it. Wording alone did not move the AI on this one.
- Not built: "an exact qualified job title counts regardless of labels" safety net (undecided).
- Not done: overlap scan of `STARTER_DOMAINS` (e.g. early childhood vs teaching).
