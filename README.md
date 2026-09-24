# Sulabha — AI-Powered Candidate Search

Natural-language resume search for HR: describe who you're looking for in plain English, and the app
finds and ranks matching candidates, explaining why each one matched.

**[Demo video](https://drive.google.com/drive/folders/1angI_cngKEo5irF_kbRkYJGkhSkXR1Oz?usp=drive_link)**
— end-to-end walkthrough of upload and search (see [Tests/demo-scenarios.txt](Tests/demo-scenarios.txt) for the scenarios covered).

## Layout

- `ResumeSearchUI/` — Angular frontend
- `ResumeSearchAPI/` — Spring Boot backend (API, database, LLM integration)
- `Tests/` — demo scenarios and the test resumes they use (`Candidate Resumes for Tests/`)

## Stack

Angular 22 · Java 21 / Spring Boot · PostgreSQL · Apache PDFBox/POI for text extraction · any
OpenAI-compatible LLM endpoint · Docker Compose

## Running it

Backend first. Put the LLM settings in `ResumeSearchAPI/.env` (gitignored, read automatically by
Docker Compose) — `OPENAI_API_KEY=...`, or `MOCK_LLM=true` to skip the LLM. Then:

```powershell
cd ResumeSearchAPI
docker compose up -d                                  # normal start - reuses the existing image
docker compose up -d --build; docker image prune -f   # only after changing backend code
```

Each `--build` leaves the previous image behind as an untagged `<none>` image; the `prune` removes it.

Then the frontend:

```
cd ResumeSearchUI
npm install
npm start
```

Open `http://localhost:4200`. Details in [ResumeSearchUI/README.md](ResumeSearchUI/README.md) and
[ResumeSearchAPI/README.md](ResumeSearchAPI/README.md).

## Good to know

- No authentication on any endpoint — not safe to expose outside a trusted network.
- Duplicate resumes are detected by extracted text content, not filename or file bytes.
- Every search result explains *why* it matched (skill, keyword, or filter), never a black-box ranking.
