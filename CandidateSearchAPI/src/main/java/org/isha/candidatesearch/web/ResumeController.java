package org.isha.candidatesearch.web;

import jakarta.validation.Valid;
import org.isha.candidatesearch.db.QueryCacheRepository;
import org.isha.candidatesearch.db.SchemaService;
import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.BulkUploadResult;
import org.isha.candidatesearch.dto.CandidateDetail;
import org.isha.candidatesearch.dto.CandidateSummary;
import org.isha.candidatesearch.dto.ExtractedTextResponse;
import org.isha.candidatesearch.dto.QueryRequest;
import org.isha.candidatesearch.dto.SearchResponse;
import org.isha.candidatesearch.dto.SuggestRequest;
import org.isha.candidatesearch.dto.UploadRequest;
import org.isha.candidatesearch.dto.UploadResponse;
import org.isha.candidatesearch.extraction.ResumeTextExtractor;
import org.isha.candidatesearch.llm.LlmClient;
import org.isha.candidatesearch.service.BulkIngestService;
import org.isha.candidatesearch.service.CandidateService;
import org.isha.candidatesearch.service.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
public class ResumeController {

    private final CandidateService candidates;
    private final BulkIngestService bulk;
    private final SearchService search;
    private final SchemaService schema;
    private final QueryCacheRepository queryCache;
    private final ResumeTextExtractor extractor;
    private final LlmClient llm;

    public ResumeController(CandidateService candidates, BulkIngestService bulk, SearchService search, SchemaService schema,
                            QueryCacheRepository queryCache, ResumeTextExtractor extractor, LlmClient llm) {
        this.candidates = candidates;
        this.bulk = bulk;
        this.search = search;
        this.schema = schema;
        this.queryCache = queryCache;
        this.extractor = extractor;
        this.llm = llm;
    }

    /** Text of a PDF/DOC/DOCX for review, flagged when the same resume text is already on file. */
    @PostMapping("/extract_resume_text")
    public ExtractedTextResponse extract(@RequestParam("file") MultipartFile file) throws IOException {
        String text = extractor.extract(file);
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ResumeTextExtractor.NO_TEXT_MESSAGE);
        }
        return new ExtractedTextResponse(file.getOriginalFilename(), text, candidates.findDuplicate(text).orElse(null));
    }

    @PostMapping("/suggest_applicant_details")
    public ApplicantDetails suggest(@Valid @RequestBody SuggestRequest request) {
        return Optional.ofNullable(llm.suggestApplicantDetails(request.resumeText()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI service could not suggest form values"));
    }

    @PostMapping("/upload_resume")
    public UploadResponse upload(@Valid @RequestBody UploadRequest request) {
        return candidates.save(request);
    }

    @PostMapping("/upload_resumes_bulk")
    public Map<String, List<BulkUploadResult>> uploadBulk(@RequestParam("files") List<MultipartFile> files) {
        return Map.of("results", bulk.ingest(files));
    }

    @GetMapping("/candidates")
    public List<CandidateSummary> list() {
        return candidates.list();
    }

    @GetMapping("/candidates/{id}")
    public CandidateDetail detail(@PathVariable String id) {
        return candidates.detail(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No candidate " + id));
    }

    @DeleteMapping("/candidates/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        if (!candidates.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No candidate " + id);
        }
        return Map.of("message", "Deleted candidate " + id);
    }

    @PostMapping("/query")
    public SearchResponse query(@Valid @RequestBody QueryRequest request) {
        return search.search(request.query());
    }

    /** Forgets saved query readings only - use after a query prompt change; candidates are kept. */
    @DeleteMapping("/query_cache")
    public Map<String, Object> clearQueryCache() {
        return Map.of("message", "Cleared the query cache", "cleared_queries", queryCache.clear());
    }

    @DeleteMapping("/clear_all_data")
    public Map<String, Object> clearAllData() {
        return Map.of("message", "Cleared all data", "cleared_tables", schema.clearAllData());
    }

    @DeleteMapping("/drop_all_tables")
    public Map<String, Object> dropAllTables() {
        return Map.of("message", "Dropped all tables", "dropped_tables", schema.dropAllTables());
    }

    @PostMapping("/create_all_tables")
    public Map<String, Object> createAllTables() {
        schema.ensureTablesExist();
        return Map.of("message", "Created all tables", "tables", SchemaService.TABLES);
    }
}
