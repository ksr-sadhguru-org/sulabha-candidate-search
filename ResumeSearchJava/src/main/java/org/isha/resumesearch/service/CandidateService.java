package org.isha.resumesearch.service;

import tools.jackson.databind.ObjectMapper;
import org.isha.resumesearch.db.CandidateRepository;
import org.isha.resumesearch.db.NamedEntity;
import org.isha.resumesearch.db.SynonymRepository;
import org.isha.resumesearch.dto.ApplicantDetails;
import org.isha.resumesearch.dto.CandidateDetailResponse;
import org.isha.resumesearch.dto.ExtractedTextResponse;
import org.isha.resumesearch.dto.ResumeParseResponse;
import org.isha.resumesearch.dto.ResumeUploadRequest;
import org.isha.resumesearch.dto.ResumeUploadResponse;
import org.isha.resumesearch.dto.ScoredEntity;
import org.isha.resumesearch.llm.LlmExtractor;
import org.isha.resumesearch.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Orchestrates resume ingestion: LLM extraction, synonym normalization, and storage. */
@Service
public class CandidateService {

    private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

    /** Flat score for skills declared manually via applicant_details.skill_competencies, which
     *  (unlike LLM-extracted skills) have no education/experience basis for a graduated score. */
    private static final int MANUAL_SKILL_SCORE = 75;

    private final CandidateRepository candidateRepository;
    private final SynonymRepository synonymRepository;
    private final LlmExtractor llmExtractor;
    private final ObjectMapper objectMapper;

    public CandidateService(CandidateRepository candidateRepository, SynonymRepository synonymRepository,
                             LlmExtractor llmExtractor, ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.synonymRepository = synonymRepository;
        this.llmExtractor = llmExtractor;
        this.objectMapper = objectMapper;
    }

    public ResumeUploadResponse uploadResume(ResumeUploadRequest request) {
        try {
            Optional<CandidateRepository.StoredResume> existing = candidateRepository.findResume(request.uniquefileId());
            boolean resumeTextUnchanged = existing.isPresent() && existing.get().resumeText().equals(request.resumeText());

            ResumeParseResponse parsed;
            String parsedJson;
            if (resumeTextUnchanged) {
                // Same resume text as last time (e.g. only applicant_details was edited) - reuse the
                // previously extracted skills/roles instead of paying for another LLM call.
                parsedJson = existing.get().parsedResumeJson();
                parsed = objectMapper.readValue(parsedJson, ResumeParseResponse.class);
                log.info("resume_text unchanged for {} - skipping LLM re-extraction", request.uniquefileId());
            } else {
                parsed = llmExtractor.extractFromResume(request.resumeText());
                if (parsed == null) {
                    throw new IllegalStateException("LLM extraction returned no result");
                }
                parsedJson = objectMapper.writeValueAsString(parsed);
            }

            if (existing.isPresent()) {
                candidateRepository.deleteUniquefileId(request.uniquefileId());
            }

            String resumeHash = Hashing.sha256Hex(request.resumeText());
            candidateRepository.addResume(request.uniquefileId(), request.resumeName(), request.resumeText(), parsedJson, resumeHash);
            processSkills(request.uniquefileId(), parsed, manualSkillNames(request.applicantDetails()));

            if (request.applicantDetails() != null) {
                candidateRepository.addApplicantDetails(request.uniquefileId(), ApplicantNormalization.normalize(request.applicantDetails()));
            }
            return new ResumeUploadResponse("Resume processed", true, request.uniquefileId());
        } catch (Exception e) {
            log.error("Error processing resume: {}", e.getMessage(), e);
            return new ResumeUploadResponse("Error processing resume", false, request.uniquefileId());
        }
    }

    /** Checks whether a just-extracted resume's text exactly matches an already-stored candidate,
     *  so the caller can skip re-running LLM extraction/suggestion on a resume it's already seen. */
    public Optional<ExtractedTextResponse.DuplicateMatch> findDuplicateByResumeText(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return Optional.empty();
        }
        return candidateRepository.findByResumeHash(Hashing.sha256Hex(resumeText))
                .map(m -> new ExtractedTextResponse.DuplicateMatch(m.uniquefileId(), m.resumeName()));
    }

    public Optional<CandidateDetailResponse> getCandidateDetail(String uniquefileId) {
        return candidateRepository.findResume(uniquefileId)
                .map(resume -> new CandidateDetailResponse(
                        uniquefileId, resume.resumeName(), resume.resumeText(),
                        candidateRepository.findApplicantDetails(uniquefileId).orElse(null)));
    }

    /** Splits a comma-separated skill_competencies string into individual skill names, e.g. "React, Journalism" -> ["React", "Journalism"]. */
    private List<String> manualSkillNames(ApplicantDetails details) {
        if (details == null || details.skillCompetencies() == null) {
            return List.of();
        }
        return Arrays.stream(details.skillCompetencies().split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void processSkills(String uniquefileId, ResumeParseResponse parsed, List<String> manualSkillNames) {
        List<ScoredEntity> allSkills = Stream.concat(parsed.explicitSkills().stream(), parsed.impliedSkills().stream()).toList();
        List<NamedEntity> skillEntities = new ArrayList<>(allSkills.stream().map(s -> new NamedEntity(s.canonical(), s.synonyms())).toList());
        manualSkillNames.forEach(name -> skillEntities.add(new NamedEntity(name, List.of())));
        List<NamedEntity> roleEntities = parsed.roles().stream().map(r -> new NamedEntity(r.canonical(), r.synonyms())).toList();

        Map<String, String> skillsMapping = synonymRepository.normalizeBatch(skillEntities, "skill");
        Map<String, String> rolesMapping = synonymRepository.normalizeBatch(roleEntities, "role");

        List<Object[]> entities = new ArrayList<>();
        for (ScoredEntity s : allSkills) {
            String key = synonymRepository.normalize(s.canonical());
            if (skillsMapping.containsKey(key)) {
                entities.add(new Object[]{"skill", skillsMapping.get(key), s.score()});
            }
        }
        for (String name : manualSkillNames) {
            String key = synonymRepository.normalize(name);
            if (skillsMapping.containsKey(key)) {
                entities.add(new Object[]{"skill", skillsMapping.get(key), MANUAL_SKILL_SCORE});
            }
        }
        for (ScoredEntity r : parsed.roles()) {
            String key = synonymRepository.normalize(r.canonical());
            if (rolesMapping.containsKey(key)) {
                entities.add(new Object[]{"role", rolesMapping.get(key), r.score()});
            }
        }
        candidateRepository.addCandidateSearchBatch(uniquefileId, entities);
        log.info("Successfully processed {} skills and roles ({} manually declared) for resume: {}",
                entities.size(), manualSkillNames.size(), uniquefileId);
    }
}
