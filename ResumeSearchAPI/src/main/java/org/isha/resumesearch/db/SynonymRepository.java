package org.isha.resumesearch.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Normalizes skills/roles against the entity_synonyms table - equivalent of normalize_entities_batch(_query) in db_operations.py. */
@Repository
public class SynonymRepository {

    private static final Logger log = LoggerFactory.getLogger(SynonymRepository.class);

    // Crude English suffix-stripping (not real stemming) used only as a last-resort query-time fallback -
    // e.g. "plumber" and the registered canonical "plumbing" both strip to "plumb", so a query using one
    // word form (agent noun, gerund, plural) still finds a canonical registered under a related form.
    private static final int MIN_ROOT_LENGTH = 3;
    private static final List<String> ROOT_SUFFIXES = List.of(
            "ational", "ation", "ings", "ing", "ers", "er", "ors", "or", "ed", "es", "s"
    );

    private final JdbcClient jdbcClient;

    public SynonymRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).strip();
    }

    /**
     * Normalizes a batch of skills/roles, creating new canonicals when a synonym isn't already known.
     * Returns a map of every (lowercased) synonym in this batch -> its canonical name.
     */
    public Map<String, String> normalizeBatch(List<NamedEntity> entities, String entityType) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        List<Set<String>> perEntitySynonyms = entities.stream().map(this::expandedSynonyms).toList();
        Set<String> allSynonyms = perEntitySynonyms.stream().flatMap(Set::stream).collect(Collectors.toSet());

        Map<String, String> synonymToCanonical = new HashMap<>(lookup(allSynonyms, entityType));

        List<String[]> toInsert = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            Set<String> synonyms = perEntitySynonyms.get(i);
            String existingCanonical = synonyms.stream().map(synonymToCanonical::get).filter(Objects::nonNull).findFirst().orElse(null);
            String canonical = existingCanonical != null ? existingCanonical : normalize(entities.get(i).canonical());
            for (String syn : synonyms) {
                if (!synonymToCanonical.containsKey(syn)) {
                    synonymToCanonical.put(syn, canonical);
                    toInsert.add(new String[]{entityType, canonical, syn});
                }
            }
        }
        for (String[] row : toInsert) {
            jdbcClient.sql("""
                            INSERT INTO entity_synonyms (entity_type, canonical, synonym)
                            VALUES (:entityType, :canonical, :synonym)
                            ON CONFLICT (entity_type, canonical, synonym) DO NOTHING
                            """)
                    .param("entityType", row[0]).param("canonical", row[1]).param("synonym", row[2])
                    .update();
        }
        log.info("Batch normalized {} {}(s) with {} new synonyms", entities.size(), entityType, toInsert.size());
        return synonymToCanonical;
    }

    /**
     * Query-time lookup only: no entity_type filter and no new canonicals created (matches normalize_entities_batch_query).
     * Falls back to substring matching for terms with no exact match (e.g. searching "office" should still
     * find the stored skill "microsoft office"/synonym "ms office") - but only when that fallback is
     * unambiguous, i.e. the term's substring only appears in synonyms of a single canonical. A generic term
     * that partially matches several unrelated skills is left unmatched rather than resolved arbitrarily.
     * Anything still unmatched after that gets one more try against canonical names by suffix-stripped
     * root (see lookupByCanonicalRoot) - e.g. "plumber" finds the registered canonical "plumbing".
     */
    public Map<String, String> lookupBatch(List<NamedEntity> entities) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        Set<String> allSynonyms = entities.stream().flatMap(e -> expandedSynonyms(e).stream()).collect(Collectors.toSet());
        Map<String, String> exact = lookup(allSynonyms, null);

        Set<String> unmatched = allSynonyms.stream().filter(s -> !exact.containsKey(s)).collect(Collectors.toSet());
        if (unmatched.isEmpty()) {
            return exact;
        }

        Map<String, String> combined = new HashMap<>(exact);
        Map<String, String> fuzzy = lookupFuzzyUnambiguous(unmatched);
        combined.putAll(fuzzy);

        Set<String> stillUnmatched = unmatched.stream().filter(s -> !fuzzy.containsKey(s)).collect(Collectors.toSet());
        if (!stillUnmatched.isEmpty()) {
            combined.putAll(lookupByCanonicalRoot(stillUnmatched));
        }
        return combined;
    }

    private Map<String, String> lookupFuzzyUnambiguous(Set<String> terms) {
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();
        int i = 0;
        for (String term : terms) {
            String paramName = "term" + (i++);
            conditions.add("LOWER(synonym) LIKE LOWER(:" + paramName + ")");
            params.put(paramName, "%" + term + "%");
        }
        List<String[]> rows = jdbcClient.sql("SELECT synonym, canonical FROM entity_synonyms WHERE " + String.join(" OR ", conditions))
                .params(params)
                .query((rs, rowNum) -> new String[]{rs.getString("synonym"), rs.getString("canonical")})
                .list();

        Map<String, String> result = new HashMap<>();
        for (String term : terms) {
            Set<String> canonicalsContainingTerm = rows.stream()
                    .filter(row -> row[0].contains(term))
                    .map(row -> row[1])
                    .collect(Collectors.toSet());
            if (canonicalsContainingTerm.size() == 1) {
                result.put(term, canonicalsContainingTerm.iterator().next());
            }
        }
        return result;
    }

    /** Last-resort fallback: matches a query term against a registered CANONICAL name (deliberately not
     *  the often free-text/descriptive synonym rows, e.g. "project manager (plumbing & fire fighting)" -
     *  matching against those would pull in unrelated canonicals just because they happen to mention the
     *  word) via a shared suffix-stripped root, unambiguous only. */
    private Map<String, String> lookupByCanonicalRoot(Set<String> terms) {
        List<String> canonicals = jdbcClient.sql("SELECT DISTINCT canonical FROM entity_synonyms")
                .query(String.class)
                .list();
        Map<String, List<String>> canonicalsByRoot = new HashMap<>();
        for (String canonical : canonicals) {
            canonicalsByRoot.computeIfAbsent(wordRoot(canonical), k -> new ArrayList<>()).add(canonical);
        }

        Map<String, String> result = new HashMap<>();
        for (String term : terms) {
            List<String> matches = canonicalsByRoot.get(wordRoot(term));
            if (matches != null && matches.size() == 1) {
                result.put(term, matches.get(0));
            }
        }
        return result;
    }

    private static String wordRoot(String word) {
        String w = word.toLowerCase(Locale.ROOT).strip();
        for (String suffix : ROOT_SUFFIXES) {
            if (w.length() - suffix.length() >= MIN_ROOT_LENGTH && w.endsWith(suffix)) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    private Map<String, String> lookup(Set<String> synonyms, String entityTypeOrNull) {
        String sql = entityTypeOrNull == null
                ? "SELECT canonical, synonym FROM entity_synonyms WHERE synonym IN (:synonyms)"
                : "SELECT canonical, synonym FROM entity_synonyms WHERE entity_type = :entityType AND synonym IN (:synonyms)";
        var spec = jdbcClient.sql(sql).param("synonyms", synonyms);
        if (entityTypeOrNull != null) {
            spec = spec.param("entityType", entityTypeOrNull);
        }
        Map<String, String> result = new HashMap<>();
        spec.query((rs, rowNum) -> Map.entry(rs.getString("synonym"), rs.getString("canonical")))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private Set<String> expandedSynonyms(NamedEntity entity) {
        Set<String> set = entity.synonyms().stream().map(this::normalize).collect(Collectors.toCollection(LinkedHashSet::new));
        set.add(normalize(entity.canonical()));
        return set;
    }
}
