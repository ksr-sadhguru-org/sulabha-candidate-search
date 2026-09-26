package org.isha.candidatesearch.db;

import org.isha.candidatesearch.dto.CandidateDetail.ExpertiseView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** A candidate's search profile: expertise rows and the normalized terms that find them. */
@Repository
public class ExpertiseRepository {

    /** One expertise row to store. terms must already be normalized. */
    public record NewExpertise(String name, String kind, String field, String source, int score, Double years, Set<String> terms) {
    }

    /** One stored term that matched a query phrase, with the expertise row it belongs to. */
    public record Hit(String candidateId, int expertiseId, String name, String kind, String field, String source, int score,
                      Double years, String term) {
    }

    private static final String HIT_SELECT = """
            SELECT t.candidate_id, e.id AS expertise_id, e.name, e.kind, e.field, e.source, e.score, e.years::float8 AS years, t.term
            FROM expertise_terms t JOIN expertise e ON e.id = t.expertise_id
            """;

    private final JdbcClient jdbc;

    public ExpertiseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(String candidateId, List<NewExpertise> expertise) {
        jdbc.sql("DELETE FROM expertise WHERE candidate_id = :id").param("id", candidateId).update();
        expertise.forEach(e -> {
            int id = jdbc.sql("""
                            INSERT INTO expertise (candidate_id, name, kind, field, source, score, years)
                            VALUES (:candidateId, :name, :kind, :field, :source, :score, :years) RETURNING id""")
                    .param("candidateId", candidateId).param("name", e.name()).param("kind", e.kind()).param("field", e.field())
                    .param("source", e.source()).param("score", e.score()).param("years", e.years())
                    .query(Integer.class).single();
            e.terms().forEach(term -> jdbc.sql("""
                            INSERT INTO expertise_terms (expertise_id, candidate_id, term) VALUES (:id, :candidateId, :term)
                            ON CONFLICT DO NOTHING""")
                    .param("id", id).param("candidateId", candidateId).param("term", term)
                    .update());
        });
    }

    public List<ExpertiseView> findByCandidate(String candidateId) {
        return jdbc.sql("""
                        SELECT e.name, e.kind, e.field, e.source, e.score, e.years::float8 AS years, STRING_AGG(t.term, '|' ORDER BY t.term) AS terms
                        FROM expertise e LEFT JOIN expertise_terms t ON t.expertise_id = e.id
                        WHERE e.candidate_id = :id GROUP BY e.id ORDER BY e.source DESC, e.score DESC""")
                .param("id", candidateId)
                .query((rs, n) -> new ExpertiseView(rs.getString("name"), rs.getString("kind"), rs.getString("field"), rs.getString("source"),
                        rs.getInt("score"), rs.getObject("years", Double.class),
                        rs.getString("terms") == null ? List.of() : Arrays.asList(rs.getString("terms").split("\\|"))))
                .list();
    }

    /** Terms equal to a phrase or ending with it as whole words ("music teacher" for "teacher"). */
    public List<Hit> findEndingWith(Collection<String> phrases) {
        return phrases.isEmpty() ? List.of() : jdbc.sql(HIT_SELECT + "WHERE t.term IN (:phrases) OR t.term LIKE ANY (ARRAY[:suffixes])")
                .param("phrases", phrases)
                .param("suffixes", phrases.stream().map(p -> "% " + p).toList())
                .query(Hit.class).list();
    }

    /** Terms containing a phrase as whole words ("senior java developer" for "senior") - for ranking extras. */
    public List<Hit> findContaining(Collection<String> phrases) {
        return phrases.isEmpty() ? List.of() : jdbc.sql(HIT_SELECT + "WHERE ' ' || t.term || ' ' LIKE ANY (ARRAY[:patterns])")
                .param("patterns", phrases.stream().map(p -> "% " + p + " %").toList())
                .query(Hit.class).list();
    }
}
