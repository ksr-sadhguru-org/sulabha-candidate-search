package org.isha.candidatesearch.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** The growing list of fields that job entries are labelled with. */
@Repository
public class FieldRepository {

    /** A field and how many candidates have a job entry in it. */
    public record FieldCount(String name, int candidates) {
    }

    private final JdbcClient jdbc;

    public FieldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> names() {
        return jdbc.sql("SELECT name FROM fields ORDER BY name").query(String.class).list();
    }

    /** Adds a new (already normalized) field; an existing one is left as is. */
    public void add(String name) {
        jdbc.sql("INSERT INTO fields (name) VALUES (:name) ON CONFLICT DO NOTHING").param("name", name).update();
    }

    public List<FieldCount> counts() {
        return jdbc.sql("""
                        SELECT f.name, COUNT(DISTINCT e.candidate_id) AS candidates
                        FROM fields f LEFT JOIN expertise e ON e.field = f.name
                        GROUP BY f.name ORDER BY candidates DESC, f.name""")
                .query(FieldCount.class).list();
    }
}
