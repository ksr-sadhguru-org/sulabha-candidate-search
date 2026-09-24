package org.isha.resumesearch.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/** Idempotent schema management - equivalent of the original db_setup.py. */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private static final List<String> TABLE_NAMES = List.of(
            "candidate_search", "candidate_application_data", "candidates_table", "entity_synonyms"
    );

    private final JdbcClient jdbcClient;

    public SchemaService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void ensureTablesExist() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS candidates_table (
                    id SERIAL PRIMARY KEY, uniquefile_id VARCHAR(255), resume_name VARCHAR(255),
                    resume_text TEXT, parsed_resume TEXT, resume_hash VARCHAR(64),
                    created_date TIMESTAMP, updated_date TIMESTAMP
                )""").update();
        // Added after the initial release - ALTER for existing databases that already have the table
        // without this column; a fresh CREATE TABLE above already includes it, so this is then a no-op.
        jdbcClient.sql("ALTER TABLE candidates_table ADD COLUMN IF NOT EXISTS resume_hash VARCHAR(64)").update();
        jdbcClient.sql("CREATE INDEX IF NOT EXISTS idx_candidates_table_resume_hash ON candidates_table(resume_hash)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS candidate_search (
                    id SERIAL PRIMARY KEY, uniquefile_id VARCHAR(255), entity_type VARCHAR(50),
                    canonical VARCHAR(255), score REAL, years REAL, display_name VARCHAR(255), created_date TIMESTAMP, updated_date TIMESTAMP
                )""").update();
        // Added after the table first shipped - upgrades databases created before they existed.
        jdbcClient.sql("ALTER TABLE candidate_search ADD COLUMN IF NOT EXISTS years REAL").update();
        jdbcClient.sql("ALTER TABLE candidate_search ADD COLUMN IF NOT EXISTS display_name VARCHAR(255)").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS entity_synonyms (
                    id SERIAL PRIMARY KEY, entity_type VARCHAR(50) NOT NULL, canonical VARCHAR(255) NOT NULL,
                    synonym VARCHAR(255) NOT NULL, created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(entity_type, canonical, synonym)
                )""").update();

        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS candidate_application_data (
                    id SERIAL PRIMARY KEY, uniquefile_id VARCHAR(255) NOT NULL, name VARCHAR(255),
                    email_from VARCHAR(255), dob VARCHAR(50), gender VARCHAR(50), marital_status VARCHAR(50),
                    address TEXT, qualification VARCHAR(255), experience VARCHAR(100), skill_competencies TEXT,
                    other_interests TEXT, reason_for_change TEXT, notice_period VARCHAR(100),
                    salary_expected DECIMAL(10, 2), stay_in_ashram VARCHAR(50), any_kind_job VARCHAR(50),
                    duration_with_isha VARCHAR(100), done_isha_program VARCHAR(50), linkedin_profile TEXT,
                    job_id VARCHAR(255), nationality VARCHAR(100), job_location VARCHAR(255), languages VARCHAR(255),
                    applicant_programs TEXT, is_meditator VARCHAR(50),
                    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(uniquefile_id)
                )""").update();

        log.info("Schema ready: {}", TABLE_NAMES);
    }

    public List<String> dropAllTables() {
        for (String table : TABLE_NAMES) {
            jdbcClient.sql("DROP TABLE IF EXISTS " + table + " CASCADE").update();
        }
        log.warn("Dropped tables: {}", TABLE_NAMES);
        return TABLE_NAMES;
    }

    /** Empties all rows but leaves the schema (columns, constraints, indexes) untouched at all times -
     *  unlike dropAllTables(), there's no moment where the tables don't exist. */
    public List<String> clearAllData() {
        for (String table : TABLE_NAMES) {
            jdbcClient.sql("TRUNCATE TABLE " + table).update();
        }
        log.warn("Cleared all data (schema preserved): {}", TABLE_NAMES);
        return TABLE_NAMES;
    }
}
