# Sulabha — AI-Powered Candidate Search

Natural-language resume search for HR: describe who you're looking for in plain English, and the app
finds and ranks matching candidates, explaining why each one matched.

**[Demo video](https://drive.google.com/drive/folders/1angI_cngKEo5irF_kbRkYJGkhSkXR1Oz?usp=drive_link)**
— end-to-end walkthrough of upload and search (see [Test Folder/demo-scenarios.txt](Test%20Folder/demo-scenarios.txt) for the scenarios covered).

## Layout

- `ResumeSearchUI/` — Angular frontend
- `ResumeSearchJava/` — Spring Boot backend (API, database, LLM integration)
- `Sample Resumes/` — local test data, not part of the app (not committed)

## Stack

Angular 22 · Java 21 / Spring Boot · PostgreSQL · Apache PDFBox/POI for text extraction · any
OpenAI-compatible LLM endpoint · Docker Compose

## Running it

Backend first:

```powershell
cd ResumeSearchJava
$env:OPENAI_API_KEY = "..."   # or $env:MOCK_LLM = "true" to skip the LLM
docker compose up --build -d
```

Then the frontend:

```
cd ResumeSearchUI
npm install
npm start
```

Open `http://localhost:4200`. Details in [ResumeSearchUI/README.md](ResumeSearchUI/README.md) and
[ResumeSearchJava/README.md](ResumeSearchJava/README.md).

## Good to know

- No authentication on any endpoint — not safe to expose outside a trusted network.
- Duplicate resumes are detected by extracted text content, not filename or file bytes.
- Every search result explains *why* it matched (skill, keyword, or filter), never a black-box ranking.
