package org.isha.candidatesearch.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** The growing list of domains (the subject side of a job: music, software & it, plumbing, ...). */
@Repository
public class DomainRepository {

    /** A domain and how many candidates have a job in it. */
    public record DomainCount(String name, int candidates) {
    }

    private final JdbcClient jdbc;

    public DomainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> names() {
        return jdbc.sql("SELECT name FROM domains ORDER BY name").query(String.class).list();
    }

    /** Adds a new (already normalized) domain; an existing one is left as is. */
    public void add(String name) {
        jdbc.sql("INSERT INTO domains (name) VALUES (:name) ON CONFLICT DO NOTHING").param("name", name).update();
    }

    public List<DomainCount> counts() {
        return jdbc.sql("""
                        SELECT d.name, COUNT(DISTINCT e.candidate_id) AS candidates
                        FROM domains d LEFT JOIN expertise e ON e.domain = d.name
                        GROUP BY d.name ORDER BY candidates DESC, d.name""")
                .query(DomainCount.class).list();
    }
}
