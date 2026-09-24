package org.isha.resumesearch.db;

import org.isha.resumesearch.dto.ApplicantDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Storage for raw/parsed resumes, extracted skills/roles, and applicant application data. */
@Repository
public class CandidateRepository {

    private static final Logger log = LoggerFactory.getLogger(CandidateRepository.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public CandidateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean existsUniquefileId(String uniquefileId) {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM candidates_table WHERE uniquefile_id = :id")
                .param("id", uniquefileId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public record StoredResume(String resumeName, String resumeText, String parsedResumeJson) {
    }

    public record DuplicateMatch(String uniquefileId, String resumeName) {
    }

    public Optional<StoredResume> findResume(String uniquefileId) {
        return jdbcClient.sql("SELECT resume_name, resume_text, parsed_resume FROM candidates_table WHERE uniquefile_id = :id")
                .param("id", uniquefileId)
                .query((rs, rowNum) -> new StoredResume(rs.getString("resume_name"), rs.getString("resume_text"), rs.getString("parsed_resume")))
                .optional();
    }

    /** Finds an existing candidate whose resume content hash matches - used to detect a resume being
     *  uploaded again (byte-for-byte identical extracted text) before paying for LLM processing again. */
    public Optional<DuplicateMatch> findByResumeHash(String resumeHash) {
        return jdbcClient.sql("SELECT uniquefile_id, resume_name FROM candidates_table WHERE resume_hash = :hash LIMIT 1")
                .param("hash", resumeHash)
                .query((rs, rowNum) -> new DuplicateMatch(rs.getString("uniquefile_id"), rs.getString("resume_name")))
                .optional();
    }

    /** SimplePropertyRowMapper maps columns to ApplicantDetails' record components automatically
     *  (camelCase <-> snake_case), so no manual per-field mapping is needed here. */
    public Optional<ApplicantDetails> findApplicantDetails(String uniquefileId) {
        return jdbcClient.sql("""
                        SELECT name, email_from, dob, gender, marital_status, address, qualification, experience,
                               skill_competencies, other_interests, reason_for_change, notice_period, salary_expected,
                               stay_in_ashram, any_kind_job, duration_with_isha, done_isha_program, linkedin_profile,
                               job_id, nationality, job_location, languages, applicant_programs, is_meditator
                        FROM candidate_application_data WHERE uniquefile_id = :id
                        """)
                .param("id", uniquefileId)
                .query(ApplicantDetails.class)
                .optional();
    }

    public void deleteUniquefileId(String uniquefileId) {
        jdbcClient.sql("DELETE FROM candidate_search WHERE uniquefile_id = :id").param("id", uniquefileId).update();
        jdbcClient.sql("DELETE FROM candidate_application_data WHERE uniquefile_id = :id").param("id", uniquefileId).update();
        jdbcClient.sql("DELETE FROM candidates_table WHERE uniquefile_id = :id").param("id", uniquefileId).update();
        log.info("Deleted all entries for uniquefile_id: {}", uniquefileId);
    }

    public void addResume(String uniquefileId, String resumeName, String resumeText, String parsedResumeJson, String resumeHash) {
        jdbcClient.sql("""
                        INSERT INTO candidates_table (uniquefile_id, resume_name, resume_text, parsed_resume, resume_hash, created_date, updated_date)
                        VALUES (:id, :name, :text, :parsed, :hash, NOW(), NOW())
                        """)
                .param("id", uniquefileId).param("name", resumeName).param("text", resumeText)
                .param("parsed", parsedResumeJson).param("hash", resumeHash)
                .update();
    }

    /** entities: list of [entityType, canonical, score]. */
    public void addCandidateSearchBatch(String uniquefileId, List<Object[]> entities) {
        for (Object[] e : entities) {
            jdbcClient.sql("""
                            INSERT INTO candidate_search (uniquefile_id, entity_type, canonical, score, created_date, updated_date)
                            VALUES (:id, :entityType, :canonical, :score, NOW(), NOW())
                            """)
                    .param("id", uniquefileId).param("entityType", e[0]).param("canonical", e[1]).param("score", e[2])
                    .update();
        }
        log.info("Stored {} entities for uniquefile_id: {}", entities.size(), uniquefileId);
    }

    public void addApplicantDetails(String uniquefileId, ApplicantDetails d) {
        jdbcClient.sql("""
                        INSERT INTO candidate_application_data (
                            uniquefile_id, name, email_from, dob, gender, marital_status,
                            address, qualification, experience, skill_competencies,
                            other_interests, reason_for_change, notice_period, salary_expected,
                            stay_in_ashram, any_kind_job, duration_with_isha, done_isha_program,
                            linkedin_profile, job_id, nationality, job_location, languages,
                            applicant_programs, is_meditator, created_date, updated_date
                        ) VALUES (
                            :id, :name, :emailFrom, :dob, :gender, :maritalStatus,
                            :address, :qualification, :experience, :skillCompetencies,
                            :otherInterests, :reasonForChange, :noticePeriod, :salaryExpected,
                            :stayInAshram, :anyKindJob, :durationWithIsha, :doneIshaProgram,
                            :linkedinProfile, :jobId, :nationality, :jobLocation, :languages,
                            :applicantPrograms, :isMeditator, NOW(), NOW()
                        )
                        """)
                // ApplicantDetails' record component names match these named parameters 1:1, so convert it
                // to a param map rather than listing all 23 fields by hand.
                .params(applicantParams(uniquefileId, d))
                .update();
        log.info("Stored applicant details for uniquefile_id: {}", uniquefileId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applicantParams(String uniquefileId, ApplicantDetails d) {
        Map<String, Object> params = new HashMap<>(jsonMapper.convertValue(d, Map.class));
        params.put("id", uniquefileId);
        return params;
    }
}
