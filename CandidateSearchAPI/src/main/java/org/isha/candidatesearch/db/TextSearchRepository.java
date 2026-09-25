package org.isha.candidatesearch.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

/** Full-text fallback over resume text (Postgres english stemming: "teachers" finds "teacher"). */
@Repository
public class TextSearchRepository {

    private final JdbcClient jdbc;

    public TextSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Candidates whose resume contains this phrase, words in order. */
    public Set<String> findContaining(String phrase) {
        return new HashSet<>(jdbc.sql("SELECT id FROM candidates WHERE search_tsv @@ phraseto_tsquery('english', :phrase)")
                .param("phrase", phrase)
                .query(String.class).list());
    }
}
