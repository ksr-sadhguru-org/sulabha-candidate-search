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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
            ResumeParseResponse previous = resumeTextUnchanged ? readPreviousParse(existing.get().parsedResumeJson()) : null;
            if (previous != null) {
                // Same resume text as last time (e.g. only applicant_details was edited) - reuse the
                // previously extracted skills/roles instead of paying for another LLM call.
                parsed = previous;
                parsedJson = existing.get().parsedResumeJson();
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

    /** Stores each LLM entry once (its own row, display_name = itself) plus one row per name it includes
     *  (display_name = the entry), all with the entry's single score - so e.g. a search for "java" finds the
     *  candidate via "Senior Java Developer"'s includes, and the result shows that one entry, once. */
    private void processSkills(String uniquefileId, ResumeParseResponse parsed, List<String> manualSkillNames) {
        // Highest score first, so a name included by two entries is attributed to the stronger one.
        List<ScoredEntity> items = parsed.items().stream()
                .sorted(Comparator.comparingInt(ScoredEntity::score).reversed()).toList();
        // Main entries keep exactly the name the LLM gave them (it's what's displayed); what they include, and
        // manually declared skills, are normalized as usual so "J2EE" and "Java EE" still end up the same.
        List<NamedEntity> skillEntries = new ArrayList<>();
        List<NamedEntity> roleEntries = new ArrayList<>();
        List<NamedEntity> includedNames = new ArrayList<>();
        for (ScoredEntity item : items) {
            (isRole(item) ? roleEntries : skillEntries).add(new NamedEntity(item.canonical(), item.synonyms()));
            includes(item).forEach(name -> includedNames.add(new NamedEntity(name, List.of())));
        }
        manualSkillNames.forEach(name -> includedNames.add(new NamedEntity(name, List.of())));

        Map<String, String> entryMapping = new HashMap<>(synonymRepository.registerExact(skillEntries, "skill"));
        entryMapping.putAll(synonymRepository.registerExact(roleEntries, "role"));
        Map<String, String> skillsMapping = synonymRepository.normalizeBatch(includedNames, "skill");

        List<Object[]> entities = new ArrayList<>();
        Set<String> stored = new HashSet<>();
        for (ScoredEntity item : items) {
            String main = entryMapping.get(synonymRepository.normalize(item.canonical()));
            if (main == null || !stored.add(main)) {
                continue;
            }
            entities.add(new Object[]{isRole(item) ? "role" : "skill", main, item.score(), item.years(), main});
            for (String name : includes(item)) {
                String included = skillsMapping.get(synonymRepository.normalize(name));
                if (included != null && stored.add(included)) {
                    entities.add(new Object[]{"skill", included, item.score(), item.years(), main});
                }
            }
        }
        // A manually declared skill the LLM already covered would only add a second, flat-scored duplicate.
        for (String name : manualSkillNames) {
            String skill = skillsMapping.get(synonymRepository.normalize(name));
            if (skill != null && stored.add(skill)) {
                entities.add(new Object[]{"skill", skill, MANUAL_SKILL_SCORE, null, skill});
            }
        }
        candidateRepository.addCandidateSearchBatch(uniquefileId, entities);
        log.info("Successfully processed {} entries ({} rows incl. what they include) for resume: {}",
                items.size(), entities.size(), uniquefileId);
    }

    /** The stored LLM result for this resume, or null if it predates the current format (no "items") and so
     *  has to be re-extracted. */
    private ResumeParseResponse readPreviousParse(String parsedJson) {
        try {
            ResumeParseResponse previous = objectMapper.readValue(parsedJson, ResumeParseResponse.class);
            return previous.items() == null ? null : previous;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isRole(ScoredEntity item) {
        return "role".equalsIgnoreCase(item.type());
    }

    private static List<String> includes(ScoredEntity item) {
        return item.includes() == null ? List.of() : item.includes();
    }
}
