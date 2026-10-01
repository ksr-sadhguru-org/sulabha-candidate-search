# Sulabha — AI-Powered Candidate Search

Natural-language resume search for HR: describe who you're looking for in plain English, and the app
finds and ranks matching candidates, explaining why each one matched.

**[Demo video (release v1.0)](https://github.com/ksr-sadhguru-org/sulabha-candidate-search/releases/tag/v1.0)**
— captioned test run: bulk upload, duplicates and updated resumes, the key searches, paging and page state
(download the MP4 under "Assets").

## Layout

- `CandidateSearchUI/` — Angular frontend
- `CandidateSearchAPI/` — Spring Boot backend (API, database, LLM integration)
- `Tests/` — test scenarios ([scenarios.md](Tests/scenarios.md)), their machine-checkable expected results
  (`expected-results.json`), the synthetic test resumes (`Candidate Resumes for Tests/`) and `run-tests.mjs`,
  which checks the running API against them (see step 6 of Getting started)

## Stack

Angular 22 · Java 25 / Spring Boot · PostgreSQL · Apache PDFBox/POI for text extraction · any
OpenAI-compatible LLM endpoint · Docker Compose

## Getting started

Step by step on Windows. Commands are for PowerShell; run them from the repository folder unless a step says otherwise.

### 1. Install the tools (once)

| Tool                       | Why                                  | Get it                                                                                                             |
| -------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| **Docker Desktop**         | Runs the backend and its database    | https://www.docker.com/products/docker-desktop - start it after installing and wait until it says _Engine running_ |
| **Node.js 22 or 24 (LTS)** | Runs the web UI and the test scripts | https://nodejs.org                                                                                                 |
| Git                        | To get the code                      | https://git-scm.com                                                                                                |

Java and Maven are **not** needed to run the app - Docker builds the backend. You only need Java 25 + Maven to run the
backend's unit tests (`mvn test`).

### 2. Get an AI key

The app uses an OpenAI-compatible AI service to read resumes and searches. Use one of:

- **OpenAI** - create a key at https://platform.openai.com/api-keys (it starts with `sk-`).
- **Azure OpenAI** - in the Azure portal open your OpenAI resource: copy a **key** (_Keys and Endpoint_), the resource
  **endpoint**, and the **deployment name** of your model (_Deployments_).

No key yet? You can still try the app with `MOCK_LLM=true` (step 3) - searches then use simple keyword matching.

### 3. Create the settings file

```powershell
Copy-Item CandidateSearchAPI/.env.example CandidateSearchAPI/.env
notepad CandidateSearchAPI/.env
```

Fill in the values - the file explains each one:

| Setting                              | OpenAI                       | Azure OpenAI                                          |
| ------------------------------------ | ---------------------------- | ----------------------------------------------------- |
| `OPENAI_API_KEY`                     | your `sk-...` key            | your Azure key                                        |
| `OPENAI_BASE_URL`                    | leave empty                  | `https://<resource-name>.openai.azure.com/openai/v1/` |
| `OPENAI_MODEL`, `OPENAI_QUERY_MODEL` | a model name, e.g. `gpt-4.1` | your **deployment name**                              |
| `MOCK_LLM`                           | `false`                      | `false`                                               |

`.env` is gitignored: your key stays on your machine and is never committed.

### 4. Start the backend

```powershell
cd CandidateSearchAPI
docker compose up -d --build
```

The first start downloads and builds everything (a few minutes). Check it is up - this should show `"status":"UP"`:

```powershell
curl.exe http://localhost:8000/actuator/health
```

Later starts are quick: `docker compose up -d`. After changing backend code, rebuild with
`docker compose up -d --build; docker image prune -f` (the prune removes the old, now-unused image).

### 5. Start the web UI

In a **second** PowerShell window:

```powershell
cd CandidateSearchUI
npm install        # first time only
npm start
```

Open http://localhost:4200. Leave this window open while you use the app (Ctrl+C stops the UI).

### 6. Try it with the sample resumes

- **In the app:** _Upload Resume_ -> pick files from `Tests/Candidate Resumes for Tests/` -> review -> _Save All_. Then
  _Search_, or click one of the example searches.
- **Automatically:** load every sample resume and check all test scenarios in one go (this **clears all data first**):

  ```powershell
  node Tests/run-tests.mjs --fresh
  ```

  Afterwards, `node Tests/run-tests.mjs` re-checks against the data already loaded. The scenarios are described in
  [Tests/scenarios.md](Tests/scenarios.md).

### 7. Stop, restart, reset

| To...                                     | Do                                                               |
| ----------------------------------------- | ---------------------------------------------------------------- |
| Stop the UI                               | Ctrl+C in its window                                             |
| Stop the backend (data is kept)           | `cd CandidateSearchAPI; docker compose down`                     |
| Start again                               | `docker compose up -d` (backend), `npm start` (UI)               |
| Delete all candidates                     | _Admin_ page -> type the confirmation phrase -> _Clear All Data_ |
| Remove everything, including the database | `docker compose down -v` (deletes the data permanently)          |

### Common problems

| You see                                                   | Fix                                                                                                                                                             |
| --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| _"The AI service did not answer"_ / _"Search failed ..."_ | Check `CandidateSearchAPI/.env`: key, base URL and (on Azure) deployment names. Then restart: `docker compose up -d`. The cause is in `docker compose logs app` |
| `docker` is not recognized / cannot connect               | Start Docker Desktop and wait for _Engine running_                                                                                                              |
| _Port 8000, 5432 or 4200 is already in use_               | Another program uses it - stop it, or an old copy of this app (`docker ps`, then `docker compose down`)                                                         |
| UI says the backend isn't running                         | Check step 4's health URL; see `docker compose logs app`                                                                                                        |
| A resume shows _"No text could be read"_                  | It is a scanned image - only PDF/Word files with real text are supported                                                                                        |

More detail: [CandidateSearchUI/README.md](CandidateSearchUI/README.md) and [CandidateSearchAPI/README.md](CandidateSearchAPI/README.md).

## Good to know

- No authentication on any endpoint — not safe to expose outside a trusted network.
- Duplicate resumes are detected by extracted text content, not filename or file bytes.
- An updated resume with the same email or phone as an existing candidate updates that candidate.
- Every search result explains _why_ it matched (profile, related term, resume text, filters), never a black-box ranking.
