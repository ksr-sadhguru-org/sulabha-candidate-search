# ResumeSearch UI

Angular 22 client for the [ResumeSearchJava](../ResumeSearchJava) backend — upload resumes and search
candidates with a natural-language query.

> New to this project? Start with the [top-level README](../README.md).

## Stack

Angular 22 (standalone components, signals, zoneless), plain `HttpClient`.

## Pages

- `/search` — natural-language query; each result shows its skill/keyword match and a per-field
  breakdown of which requested filters (location, experience, etc.) it does and doesn't satisfy
- `/upload` — pick one or more PDF/DOC/DOCX files; each gets a review panel (extracted text +
  AI-suggested applicant details) before saving, individually or via "Save All"
- `/admin` — schema setup and destructive data-clearing, gated behind a confirmation phrase (local/demo use only)

## Running

Backend must be running first, on `http://localhost:8000`.

```
npm install
npm start
```

Opens on `http://localhost:4200`. To use a different port, also add it to the backend's CORS config
(`WebCorsConfig.java`).

## Known gaps

- No OCR — only PDF/DOC/DOCX with an actual text layer are supported
- No authentication
