package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.CandidateRepository;
import org.isha.candidatesearch.db.ExpertiseRepository;
import org.isha.candidatesearch.db.FieldRepository;
import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.CandidateDetail;
import org.isha.candidatesearch.dto.CandidateSummary;
import org.isha.candidatesearch.dto.ExtractedTextResponse.DuplicateMatch;
import org.isha.candidatesearch.dto.Profile;
import org.isha.candidatesearch.dto.UploadRequest;
import org.isha.candidatesearch.dto.UploadResponse;
import org.isha.candidatesearch.llm.LlmClient;
import org.isha.candidatesearch.llm.LlmJson;
import org.isha.candidatesearch.search.Locations;
import org.isha.candidatesearch.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Saving a candidate: pick the record to write (new, same id, or the same person by email/phone), build the
 *  search profile with the LLM, then store candidate + profile in one transaction. */
@Service
public class CandidateService {

    private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

    static final ApplicantDetails EMPTY_FORM = new ApplicantDetails(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    private record Target(String id, boolean updatedExisting, String existingName) {
    }

    private final CandidateRepository candidates;
    private final ExpertiseRepository expertise;
    private final FieldRepository fields;
    private final LlmClient llm;
    private final TransactionTemplate tx;

    public CandidateService(CandidateRepository candidates, ExpertiseRepository expertise, FieldRepository fields,
                            LlmClient llm, TransactionTemplate tx) {
        this.candidates = candidates;
        this.expertise = expertise;
        this.fields = fields;
        this.llm = llm;
        this.tx = tx;
    }

    public UploadResponse save(UploadRequest request) {
        try {
            ApplicantDetails form = ApplicantNormalization.normalize(Optional.ofNullable(request.applicantDetails()).orElse(EMPTY_FORM));
            Target target = resolveTarget(request.candidateId(), form);
            Profile profile = reusableProfile(target.id(), request.resumeText())
                    .orElseGet(() -> llm.buildProfile(request.resumeText(), form.skillCompetencies(), fields.names()));
            if (profile == null) {
                return new UploadResponse(false, "The AI service could not read this resume - nothing was saved.", request.candidateId(), false);
            }
            var record = new CandidateRepository.Record(target.id(), request.resumeName(), request.resumeText(),
                    Hashing.sha256Hex(request.resumeText()), LlmJson.MAPPER.writeValueAsString(profile), profile.totalYears(),
                    emailKey(form.emailFrom()), phoneKey(form.phone()), Locations.parse(form.jobLocation()));
            List<ExpertiseRepository.NewExpertise> rows = ProfileMapper.toExpertise(profile, form.skillCompetencies());
            tx.executeWithoutResult(status -> {
                // A field the AI named that isn't on the list yet joins it, so later resumes and queries can use it.
                rows.stream().map(ExpertiseRepository.NewExpertise::field).filter(Objects::nonNull).distinct().forEach(fields::add);
                candidates.upsert(record, form);
                expertise.replace(target.id(), rows);
            });
            String message = target.updatedExisting()
                    ? "Updated the existing candidate " + target.existingName() + " (same email or phone)"
                    : "Saved";
            return new UploadResponse(true, message, target.id(), target.updatedExisting());
        } catch (Exception e) {
            log.error("Saving candidate {} failed: {}", request.candidateId(), e.getMessage(), e);
            return new UploadResponse(false, "Saving failed: " + e.getMessage(), request.candidateId(), false);
        }
    }

    public Optional<DuplicateMatch> findDuplicate(String resumeText) {
        return resumeText == null || resumeText.isBlank() ? Optional.empty() : candidates.findByHash(Hashing.sha256Hex(resumeText));
    }

    public Optional<CandidateDetail> detail(String id) {
        return candidates.findStored(id).map(s -> new CandidateDetail(id, s.resumeName(), s.resumeText(), s.totalYears(),
                candidates.findForm(id).orElse(EMPTY_FORM), expertise.findByCandidate(id)));
    }

    public boolean delete(String id) {
        return candidates.delete(id);
    }

    public List<CandidateSummary> list() {
        return candidates.listAll();
    }

    /** The same id updates that record; otherwise a matching email or phone - with a compatible name - means the
     *  same person sent an updated resume, so their existing record is updated instead of creating a second one. */
    private Target resolveTarget(String requestedId, ApplicantDetails form) {
        if (candidates.findStored(requestedId).isPresent()) {
            return new Target(requestedId, false, null);
        }
        String email = emailKey(form.emailFrom());
        String phone = phoneKey(form.phone());
        if (email == null && phone == null) {
            return new Target(requestedId, false, null);
        }
        return candidates.findByContact(email, phone).entrySet().stream()
                .filter(e -> sameFirstName(e.getValue(), form.name()))
                .findFirst()
                .map(e -> new Target(e.getKey(), true, e.getValue()))
                .orElse(new Target(requestedId, false, null));
    }

    /** Resume text unchanged since the last save (e.g. only form fields edited): reuse its profile, no LLM call. */
    private Optional<Profile> reusableProfile(String id, String resumeText) {
        return candidates.findStored(id)
                .filter(s -> s.resumeText().equals(resumeText) && s.profileJson() != null)
                .map(s -> {
                    try {
                        return LlmJson.MAPPER.readValue(s.profileJson(), Profile.class);
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    private static boolean sameFirstName(String stored, String incoming) {
        if (stored == null || stored.isBlank() || incoming == null || incoming.isBlank()) {
            return true;
        }
        return firstWord(stored).equals(firstWord(incoming));
    }

    private static String firstWord(String name) {
        return name.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
    }

    static String emailKey(String email) {
        return email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Last 10 digits, so "+91 98220 41567" and "9822041567" agree. */
    static String phoneKey(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() < 7 ? null : digits.substring(Math.max(0, digits.length() - 10));
    }
}
