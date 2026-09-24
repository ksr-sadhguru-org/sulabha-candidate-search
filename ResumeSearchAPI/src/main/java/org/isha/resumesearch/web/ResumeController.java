package org.isha.resumesearch.web;

import org.isha.resumesearch.db.SchemaService;
import org.isha.resumesearch.dto.ApplicantDetails;
import org.isha.resumesearch.dto.BulkUploadResponse;
import org.isha.resumesearch.dto.BulkUploadResult;
import org.isha.resumesearch.dto.CandidateDetailResponse;
import org.isha.resumesearch.dto.ExtractedTextResponse;
import org.isha.resumesearch.dto.QueryRequest;
import org.isha.resumesearch.dto.QueryResult;
import org.isha.resumesearch.dto.ResumeUploadRequest;
import org.isha.resumesearch.dto.ResumeUploadResponse;
import org.isha.resumesearch.dto.SuggestApplicantDetailsRequest;
import org.isha.resumesearch.extraction.ResumeTextExtractor;
import org.isha.resumesearch.llm.LlmExtractor;
import org.isha.resumesearch.llm.LlmUnavailableException;
import org.isha.resumesearch.service.CandidateService;
import org.isha.resumesearch.service.QueryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    // Bounds how many files in a bulk upload get processed (and hence how many concurrent LLM calls
    // fire) at once - high enough to meaningfully parallelize, low enough to avoid Azure rate limits.
    private static final int BULK_UPLOAD_CONCURRENCY = 4;

    private final CandidateService candidateService;
    private final QueryService queryService;
    private final SchemaService schemaService;
    private final ResumeTextExtractor resumeTextExtractor;
    private final LlmExtractor llmExtractor;

    public ResumeController(CandidateService candidateService, QueryService queryService, SchemaService schemaService,
                             ResumeTextExtractor resumeTextExtractor, LlmExtractor llmExtractor) {
        this.candidateService = candidateService;
        this.queryService = queryService;
        this.schemaService = schemaService;
        this.resumeTextExtractor = resumeTextExtractor;
        this.llmExtractor = llmExtractor;
    }

    @PostMapping("/upload_resume")
    public ResumeUploadResponse uploadResume(@Valid @RequestBody ResumeUploadRequest request) {
        return candidateService.uploadResume(request);
    }

    /** Full stored record for one candidate - resume text plus applicant details, for the "view full
     *  record" panel in search results. Read-only for now; editing is a planned follow-up. */
    @GetMapping("/candidates/{uniquefileId}")
    public CandidateDetailResponse getCandidate(@PathVariable String uniquefileId) {
        return candidateService.getCandidateDetail(uniquefileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No candidate found for " + uniquefileId));
    }

    /** Extracts plain text from an uploaded PDF/DOC/DOCX file, for the caller to review/edit before calling /upload_resume. */
    @PostMapping("/extract_resume_text")
    public ExtractedTextResponse extractResumeText(@RequestParam("file") MultipartFile file) {
        try {
            String text = resumeTextExtractor.extract(file);
            ExtractedTextResponse.DuplicateMatch duplicate = candidateService.findDuplicateByResumeText(text).orElse(null);
            return new ExtractedTextResponse(file.getOriginalFilename(), text, duplicate);
        } catch (Exception e) {
            log.error("Error extracting text from {}: {}", file.getOriginalFilename(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not extract text: " + e.getMessage());
        }
    }

    /** Best-effort applicant-detail suggestions from resume text alone (nationality, languages, etc.) -
     *  the caller must let a human review/correct these before submitting, since several fields
     *  (e.g. is_meditator, stay_in_ashram) can never be inferred from a resume and always come back null. */
    @PostMapping("/suggest_applicant_details")
    public ApplicantDetails suggestApplicantDetails(@Valid @RequestBody SuggestApplicantDetailsRequest request) {
        ApplicantDetails suggestion = llmExtractor.suggestApplicantDetails(request.resumeText());
        if (suggestion == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not generate suggestions");
        }
        return suggestion;
    }

    /** Extracts and fully ingests each uploaded file (no per-file review step) - for importing many resumes
     *  at once. Files are processed with bounded concurrency, since each involves its own LLM call and
     *  doing them one-at-a-time would make a large batch take as long as the sum of every file's latency. */
    @PostMapping("/upload_resumes_bulk")
    public BulkUploadResponse uploadResumesBulk(@RequestParam("files") List<MultipartFile> files) {
        int poolSize = Math.clamp(files.size(), 1, BULK_UPLOAD_CONCURRENCY);
        try (ExecutorService executor = Executors.newFixedThreadPool(poolSize)) {
            List<CompletableFuture<BulkUploadResult>> futures = files.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> ingestOne(file), executor))
                    .toList();
            return new BulkUploadResponse(futures.stream().map(CompletableFuture::join).toList());
        }
    }

    private BulkUploadResult ingestOne(MultipartFile file) {
        String resumeName = file.getOriginalFilename();
        try {
            String text = resumeTextExtractor.extract(file);
            String uniquefileId = "BULK-" + UUID.randomUUID();
            ResumeUploadResponse response = candidateService.uploadResume(new ResumeUploadRequest(uniquefileId, resumeName, text, null));
            return new BulkUploadResult(resumeName, uniquefileId, response.status(), response.message());
        } catch (Exception e) {
            log.error("Error processing bulk upload for {}: {}", resumeName, e.getMessage(), e);
            return new BulkUploadResult(resumeName, null, false, "Error extracting text: " + e.getMessage());
        }
    }

    @PostMapping("/query")
    public QueryResult query(@Valid @RequestBody QueryRequest request) {
        try {
            return queryService.query(request.query());
        } catch (LlmUnavailableException e) {
            throw e; // handled below - keeps its specific message instead of the generic one
        } catch (Exception e) {
            log.error("Error processing query: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error processing query");
        }
    }

    /** 502: the backend is up but the LLM it depends on isn't - the message is shown as-is in the UI. */
    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleLlmUnavailable(LlmUnavailableException e) {
        log.error("Error processing query: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
    }

    @DeleteMapping("/drop_all_tables")
    public ResponseEntity<Map<String, Object>> dropAllTables() {
        log.warn("Received request to drop all tables");
        List<String> dropped = schemaService.dropAllTables();
        return ResponseEntity.ok(Map.of(
                "message", "Successfully dropped all tables",
                "dropped_tables", dropped,
                "warning", "All data has been permanently deleted"
        ));
    }

    @PostMapping("/create_all_tables")
    public ResponseEntity<Map<String, Object>> createAllTables() {
        log.info("Received request to create all tables");
        schemaService.ensureTablesExist();
        return ResponseEntity.ok(Map.of(
                "message", "Successfully created all tables",
                "tables", List.of("candidates_table", "candidate_search", "entity_synonyms", "candidate_application_data")
        ));
    }

    /** Empties all data but leaves the schema intact at all times - unlike /drop_all_tables, there's no
     *  window where the tables don't exist, so nothing needs recreating afterward. */
    @DeleteMapping("/clear_all_data")
    public ResponseEntity<Map<String, Object>> clearAllData() {
        log.warn("Received request to clear all data (schema preserved)");
        List<String> cleared = schemaService.clearAllData();
        return ResponseEntity.ok(Map.of(
                "message", "Successfully cleared all data",
                "cleared_tables", cleared,
                "warning", "All data has been permanently deleted (schema preserved)"
        ));
    }
}
