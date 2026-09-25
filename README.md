# Sulabha — AI-Powered Candidate Search

Natural-language resume search for HR: describe who you're looking for in plain English, and the app
finds and ranks matching candidates, explaining why each one matched.

**[Demo video](https://drive.google.com/drive/folders/1angI_cngKEo5irF_kbRkYJGkhSkXR1Oz?usp=drive_link)**
— end-to-end walkthrough of upload and search (recorded on an earlier version of the app).

## Layout

- `CandidateSearchUI/` — Angular frontend
- `CandidateSearchAPI/` — Spring Boot backend (API, database, LLM integration)
- `Tests/` — test scenarios ([scenarios.md](Tests/scenarios.md)), their machine-checkable expected results
  (`expected-results.json`), the synthetic test resumes (`Candidate Resumes for Tests/`) and `run-tests.mjs`,
  which checks the running API against them (`node Tests/run-tests.mjs` - clears the database first)

## Stack

Angular 22 · Java 21 / Spring Boot · PostgreSQL · Apache PDFBox/POI for text extraction · any
OpenAI-compatible LLM endpoint · Docker Compose

## Running it

Backend first. Put the LLM settings in `CandidateSearchAPI/.env` (gitignored, read automatically by
Docker Compose) — `OPENAI_API_KEY=...`, or `MOCK_LLM=true` to skip the LLM. Then:

```powershell
cd CandidateSearchAPI
docker compose up -d                                  # normal start - reuses the existing image
docker compose up -d --build; docker image prune -f   # only after changing backend code
```

Each `--build` leaves the previous image behind as an untagged `<none>` image; the `prune` removes it.

Then the frontend:

```
cd CandidateSearchUI
npm install
npm start
```

Open `http://localhost:4200`. Details in [CandidateSearchUI/README.md](CandidateSearchUI/README.md) and
[CandidateSearchAPI/README.md](CandidateSearchAPI/README.md).

## Good to know

- No authentication on any endpoint — not safe to expose outside a trusted network.
- Duplicate resumes are detected by extracted text content, not filename or file bytes.
- An updated resume with the same email or phone as an existing candidate updates that candidate.
- Every search result explains *why* it matched (profile, related term, resume text, filters), never a black-box ranking.
