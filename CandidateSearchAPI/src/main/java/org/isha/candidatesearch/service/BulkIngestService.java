package org.isha.candidatesearch.service;

import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.BulkUploadResult;
import org.isha.candidatesearch.dto.UploadRequest;
import org.isha.candidatesearch.dto.UploadResponse;
import org.isha.candidatesearch.extraction.ResumeTextExtractor;
import org.isha.candidatesearch.llm.LlmClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Imports many resumes without a review step: extract, skip duplicates, AI-suggest the form, save. */
@Service
public class BulkIngestService {

    /** Parallel files at once - enough to speed a batch up, few enough to stay under LLM rate limits. */
    private static final int CONCURRENCY = 4;

    private final ResumeTextExtractor extractor;
    private final CandidateService candidates;
    private final LlmClient llm;

    public BulkIngestService(ResumeTextExtractor extractor, CandidateService candidates, LlmClient llm) {
        this.extractor = extractor;
        this.candidates = candidates;
        this.llm = llm;
    }

    public List<BulkUploadResult> ingest(List<MultipartFile> files) {
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.clamp(files.size(), 1, CONCURRENCY))) {
            return files.stream()
                    .map(f -> CompletableFuture.supplyAsync(() -> ingestOne(f), pool))
                    .toList().stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    private BulkUploadResult ingestOne(MultipartFile file) {
        String name = file.getOriginalFilename();
        try {
            String text = extractor.extract(file);
            if (text == null || text.isBlank()) {
                return new BulkUploadResult(name, null, false, ResumeTextExtractor.NO_TEXT_MESSAGE);
            }
            var duplicate = candidates.findDuplicate(text);
            if (duplicate.isPresent()) {
                return new BulkUploadResult(name, duplicate.get().candidateId(), false, "Already exists as " + duplicate.get().resumeName());
            }
            ApplicantDetails form = Objects.requireNonNullElse(llm.suggestApplicantDetails(text), CandidateService.EMPTY_FORM);
            UploadResponse saved = candidates.save(new UploadRequest(UUID.randomUUID().toString(), name, text, form));
            return new BulkUploadResult(name, saved.candidateId(), saved.status(), saved.message());
        } catch (Exception e) {
            return new BulkUploadResult(name, null, false, "Could not process this file: " + e.getMessage());
        }
    }
}
