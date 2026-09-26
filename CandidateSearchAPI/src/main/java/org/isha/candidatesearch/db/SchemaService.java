package org.isha.candidatesearch.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Schema:
 * - candidates: resume text, fingerprint, form fields (location also split into city/state/country) and a
 *   full-text index over the resume text for the text fallback.
 * - expertise: one row per area of expertise, from the resume (LLM profile) or the form (skill competencies).
 * - expertise_terms: every normalized phrase that finds an expertise row. Nothing is shared between candidates.
 * - query_cache: each query's parsed form, so the same query always searches the same way.
 * - fields: the growing list of fields (software & it, music, ...) job entries are labelled with. It is vocabulary,
 *   not candidate data, so Clear All Data keeps it; it starts from STARTER_FIELDS.
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    /** Children first, so DROP works without CASCADE ordering surprises. */
    public static final List<String> TABLES = List.of("expertise_terms", "expertise", "candidates", "query_cache");

    /** Seed for the fields table; the AI adds a new field only when none of the existing ones fits. */
    public static final List<String> STARTER_FIELDS = List.of(
            "software & it", "electrical", "mechanical", "civil & construction", "music", "performing arts",
            "teaching & education", "languages & translation", "accounting & finance", "healthcare", "hospitality & food",
            "trades", "design & media", "management", "yoga & wellness", "transport & logistics");

    private final JdbcClient jdbcClient;

    public SchemaService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void ensureTablesExist() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS candidates (
                    id VARCHAR(255) PRIMARY KEY,
                    resume_name VARCHAR(255), resume_text TEXT NOT NULL, resume_hash VARCHAR(64), profile_json TEXT,
                    total_years REAL,
                    name VARCHAR(255), email_from VARCHAR(255), phone VARCHAR(50), email_key VARCHAR(255), phone_key VARCHAR(20),
                    dob VARCHAR(50), gender VARCHAR(50), marital_status VARCHAR(50), address TEXT,
                    qualification VARCHAR(255), experience VARCHAR(100), skill_competencies TEXT, other_interests TEXT,
                    reason_for_change TEXT, notice_period VARCHAR(100), salary_expected DECIMAL(12, 2),
                    stay_in_ashram VARCHAR(100), any_kind_job VARCHAR(100), duration_with_isha VARCHAR(100),
                    done_isha_program VARCHAR(50), linkedin_profile TEXT, job_id VARCHAR(255), nationality VARCHAR(100),
                    job_location VARCHAR(255), location_city VARCHAR(100), location_state VARCHAR(100), location_country VARCHAR(100),
                    languages VARCHAR(255), applicant_programs TEXT, is_meditator VARCHAR(50),
                    search_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', resume_text)) STORED,
                    created_date TIMESTAMP DEFAULT NOW(), updated_date TIMESTAMP DEFAULT NOW()
                )""").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_candidates_hash ON candidates(resume_hash)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_candidates_email ON candidates(email_key)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_candidates_phone ON candidates(phone_key)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_candidates_tsv ON candidates USING GIN(search_tsv)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS expertise (
                    id SERIAL PRIMARY KEY,
                    candidate_id VARCHAR(255) NOT NULL REFERENCES candidates(id) ON DELETE CASCADE,
                    name VARCHAR(255) NOT NULL, kind VARCHAR(20), source VARCHAR(10) NOT NULL, score INT NOT NULL, years REAL
                )""").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_expertise_candidate ON expertise(candidate_id)").update();
        jdbcClient.sql("ALTER TABLE expertise ADD COLUMN IF NOT EXISTS field VARCHAR(100)").update();

        jdbcClient.sql("CREATE TABLE IF NOT EXISTS fields (name VARCHAR(100) PRIMARY KEY, created_date TIMESTAMP DEFAULT NOW())").update();
        STARTER_FIELDS.forEach(name -> jdbcClient.sql("INSERT INTO fields (name) VALUES (:name) ON CONFLICT DO NOTHING")
                .param("name", name).update());

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS expertise_terms (
                    expertise_id INT NOT NULL REFERENCES expertise(id) ON DELETE CASCADE,
                    candidate_id VARCHAR(255) NOT NULL,
                    term VARCHAR(255) NOT NULL,
                    PRIMARY KEY (expertise_id, term)
                )""").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_terms_term ON expertise_terms(term)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS query_cache (
                    query_key TEXT PRIMARY KEY, parsed_json TEXT NOT NULL, created_date TIMESTAMP DEFAULT NOW()
                )""").update();

        log.info("Schema ready: {}", TABLES);
    }

    public List<String> dropAllTables() {
        TABLES.forEach(t -> jdbcClient.sql("DROP TABLE IF EXISTS " + t + " CASCADE").update());
        jdbcClient.sql("DROP TABLE IF EXISTS fields").update();
        log.warn("Dropped tables: {}", TABLES);
        return TABLES;
    }

    /** Empties every table, keeping the schema. The query cache goes too, so prompt changes take effect. */
    public List<String> clearAllData() {
        jdbcClient.sql("TRUNCATE TABLE " + String.join(", ", TABLES)).update();
        log.warn("Cleared all data: {}", TABLES);
        return TABLES;
    }
}
