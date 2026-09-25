package org.isha.candidatesearch.db;

import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.CandidateSummary;
import org.isha.candidatesearch.dto.ExtractedTextResponse.DuplicateMatch;
import org.isha.candidatesearch.dto.ParsedQuery.Location;
import org.isha.candidatesearch.llm.LlmJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The candidates table: resume, fingerprint, form fields and the stored LLM profile. */
@Repository
public class CandidateRepository {

    private static final List<String> FORM_COLUMNS = List.of(
            "name", "email_from", "phone", "dob", "gender", "marital_status", "address", "qualification", "experience",
            "skill_competencies", "other_interests", "reason_for_change", "notice_period", "salary_expected",
            "stay_in_ashram", "any_kind_job", "duration_with_isha", "done_isha_program", "linkedin_profile", "job_id",
            "nationality", "job_location", "languages", "applicant_programs", "is_meditator");

    private static final List<String> OWN_COLUMNS = List.of(
            "resume_name", "resume_text", "resume_hash", "profile_json", "total_years",
            "email_key", "phone_key", "location_city", "location_state", "location_country");

    private static final List<String> ALL_COLUMNS = Stream.concat(OWN_COLUMNS.stream(), FORM_COLUMNS.stream()).toList();
    private static final Pattern SNAKE = Pattern.compile("_([a-z])");

    private static final String UPSERT = "INSERT INTO candidates (id, %s) VALUES (:id, %s) ON CONFLICT (id) DO UPDATE SET %s, updated_date = NOW()"
            .formatted(String.join(", ", ALL_COLUMNS),
                    ALL_COLUMNS.stream().map(c -> ":" + camel(c)).collect(Collectors.joining(", ")),
                    ALL_COLUMNS.stream().map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", ")));

    /** What search needs per candidate: filter fields and display fields. */
    public record Row(String id, String name, String experience, Double totalYears, String qualification,
                      String jobLocation, String city, String state, String country, String languages,
                      String nationality, String gender, String maritalStatus, String noticePeriod, Double salaryExpected,
                      String stayInAshram, String anyKindJob, String durationWithIsha, String doneIshaProgram,
                      String isMeditator) {
    }

    public record Stored(String resumeName, String resumeText, String profileJson, Double totalYears) {
    }

    /** Everything written for one candidate besides the form. */
    public record Record(String id, String resumeName, String resumeText, String resumeHash, String profileJson,
                         Double totalYears, String emailKey, String phoneKey, Location location) {
    }

    private final JdbcClient jdbc;

    public CandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DuplicateMatch> findByHash(String hash) {
        return jdbc.sql("SELECT id, resume_name FROM candidates WHERE resume_hash = :hash LIMIT 1")
                .param("hash", hash)
                .query((rs, n) -> new DuplicateMatch(rs.getString("id"), rs.getString("resume_name")))
                .optional();
    }

    public Optional<Stored> findStored(String id) {
        return jdbc.sql("SELECT resume_name, resume_text, profile_json, total_years::float8 AS total_years FROM candidates WHERE id = :id")
                .param("id", id)
                .query(Stored.class)
                .optional();
    }

    /** id -> name of candidates sharing this email or phone, most recently updated first. */
    public Map<String, String> findByContact(String emailKey, String phoneKey) {
        return jdbc.sql("""
                        SELECT id, COALESCE(name, '') AS name FROM candidates
                        WHERE email_key = :email OR phone_key = :phone ORDER BY updated_date DESC""")
                .param("email", emailKey).param("phone", phoneKey)
                .query((rs, n) -> Map.entry(rs.getString("id"), rs.getString("name")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public void upsert(Record r, ApplicantDetails form) {
        Map<String, Object> params = new HashMap<>(toParams(form));
        params.put("id", r.id());
        params.put("resumeName", r.resumeName());
        params.put("resumeText", r.resumeText());
        params.put("resumeHash", r.resumeHash());
        params.put("profileJson", r.profileJson());
        params.put("totalYears", r.totalYears());
        params.put("emailKey", r.emailKey());
        params.put("phoneKey", r.phoneKey());
        params.put("locationCity", r.location().city());
        params.put("locationState", r.location().state());
        params.put("locationCountry", r.location().country());
        jdbc.sql(UPSERT).params(params).update();
    }

    public Optional<ApplicantDetails> findForm(String id) {
        return jdbc.sql("SELECT " + String.join(", ", FORM_COLUMNS) + " FROM candidates WHERE id = :id")
                .param("id", id)
                .query(ApplicantDetails.class)
                .optional();
    }

    public List<CandidateSummary> listAll() {
        return jdbc.sql("SELECT id AS candidate_id, name, resume_name, job_location FROM candidates ORDER BY name, id")
                .query(CandidateSummary.class)
                .list();
    }

    /** Search rows for these ids, or for every candidate when ids is null. */
    public Map<String, Row> findRows(Collection<String> ids) {
        if (ids != null && ids.isEmpty()) {
            return Map.of();
        }
        var sql = jdbc.sql("""
                SELECT id, name, experience, total_years::float8 AS total_years, qualification, job_location, location_city AS city,
                       location_state AS state, location_country AS country, languages, nationality, gender, marital_status,
                       notice_period, salary_expected::float8 AS salary_expected, stay_in_ashram, any_kind_job,
                       duration_with_isha, done_isha_program, is_meditator
                FROM candidates""" + (ids == null ? "" : " WHERE id IN (:ids)"));
        return (ids == null ? sql : sql.param("ids", ids))
                .query(Row.class).list().stream()
                .collect(Collectors.toMap(Row::id, Function.identity()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toParams(ApplicantDetails form) {
        return LlmJson.MAPPER.convertValue(form, Map.class);
    }

    private static String camel(String snake) {
        return SNAKE.matcher(snake).replaceAll(m -> m.group(1).toUpperCase());
    }
}
