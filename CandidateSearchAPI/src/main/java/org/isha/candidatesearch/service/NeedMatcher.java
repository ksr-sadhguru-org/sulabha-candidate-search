package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.ExpertiseRepository;
import org.isha.candidatesearch.db.ExpertiseRepository.Hit;
import org.isha.candidatesearch.db.TextSearchRepository;
import org.isha.candidatesearch.dto.ParsedQuery.Need;
import org.isha.candidatesearch.search.Terms;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds who has one need (a query term plus its equivalents), in order of strength:
 * EXACT - a profile term is the query term or ends with it ("music teacher" for "teacher");
 * RELATED - the same, via one of the LLM's equivalents ("tutor") or only via an education entry;
 * TEXT - only the resume text contains it (full-text search).
 */
@Component
class NeedMatcher {

    static final int EXACT = 3, RELATED = 2, TEXT = 1, SAME_FIELD = 0;

    /** One-word job titles that mean different things in different fields ("developer": software or land). In a
     *  query with a field they count only through a job in that field. Specific skills ("python") are never here. */
    static final Set<String> GENERIC_JOB_WORDS = Set.of(
            "developer", "engineer", "programmer", "teacher", "tutor", "trainer", "instructor", "consultant", "manager",
            "analyst", "specialist", "technician", "officer", "executive", "assistant", "designer", "architect",
            "operator", "supervisor", "coordinator", "lead", "expert", "professional", "worker");

    /** How one candidate meets one need. hits are empty for a TEXT match, textTerm is null otherwise. */
    record Result(int quality, List<Hit> hits, String textTerm) {

        int bestScore() {
            return hits.stream().mapToInt(Hit::score).max().orElse(0);
        }

        /** Years for this need - the reviewed form value when there is one, else the profile's. */
        Double years() {
            return Stream.of("form", "resume")
                    .map(source -> hits.stream().filter(h -> source.equals(h.source())).map(Hit::years)
                            .filter(Objects::nonNull).max(Double::compare))
                    .flatMap(Optional::stream).findFirst().orElse(null);
        }
    }

    private final ExpertiseRepository expertise;
    private final TextSearchRepository textSearch;

    NeedMatcher(ExpertiseRepository expertise, TextSearchRepository textSearch) {
        this.expertise = expertise;
        this.textSearch = textSearch;
    }

    /** Must-haves: profile terms equal to or ending with the need. A generic job word ("developer") in a query with a
     *  field counts only through a job in that field - so a land developer never meets "developer" in a software search. */
    Map<String, Result> matchMust(Need need, String queryField) {
        return match(need, false, queryField, need.isGeneric());
    }

    /** Ranking extras ("senior"): profile terms containing the need anywhere. */
    Map<String, Result> matchNice(Need need) {
        return match(need, true, null, false);
    }

    /**
     * @param queryField the query's field, or null when the query names no specific skill
     * @param wholeNeedGeneric the need itself is a generic job word - then every phrase of it is field-bound;
     *        otherwise only its one-word generic alternatives are. A field-bound phrase counts only through a job
     *        entry in queryField, and is not searched in resume text (which has no field).
     */
    private Map<String, Result> match(Need need, boolean anywhere, String queryField, boolean wholeNeedGeneric) {
        String main = Terms.normalize(need.term());
        List<String> phrases = Stream.concat(Stream.of(main),
                        Optional.ofNullable(need.alternatives()).orElse(List.of()).stream().map(Terms::normalize))
                .filter(p -> !p.isEmpty()).distinct().toList();

        java.util.function.Predicate<String> fieldBound = p -> queryField != null && (wholeNeedGeneric || GENERIC_JOB_WORDS.contains(p));
        java.util.function.BiPredicate<String, String> hitsPhrase = (term, p) -> anywhere ? containsWords(term, p) : Terms.matches(term, p);
        Map<String, Result> results = new HashMap<>();
        (anywhere ? expertise.findContaining(phrases) : expertise.findEndingWith(phrases)).stream()
                // Kept when some phrase it matches is free, or it is a job in the query's field.
                .filter(h -> inField(h, queryField) || phrases.stream().anyMatch(p -> hitsPhrase.test(h.term(), p) && !fieldBound.test(p)))
                .collect(java.util.stream.Collectors.groupingBy(Hit::candidateId))
                .forEach((id, hits) -> {
                    // A degree ("B.Tech Electrical Engineering") is not the profession itself - it counts as related.
                    List<Hit> exact = hits.stream()
                            .filter(h -> !"education".equalsIgnoreCase(h.kind()))
                            .filter(h -> anywhere ? containsWords(h.term(), main) : Terms.matches(h.term(), main))
                            .toList();
                    results.put(id, exact.isEmpty() ? new Result(RELATED, hits, null) : new Result(EXACT, exact, null));
                });
        phrases.stream()
                .filter(p -> !fieldBound.test(p))
                .filter(p -> p.matches("[a-z0-9 ]+")) // full-text search can't represent "c++", "c#", ".net"
                .forEach(p -> textSearch.findContaining(p).forEach(id -> results.putIfAbsent(id, new Result(TEXT, List.of(), p))));
        return results;
    }

    private static boolean inField(Hit h, String field) {
        return field != null && "profession".equalsIgnoreCase(h.kind()) && field.equals(h.field());
    }

    private static boolean containsWords(String term, String phrase) {
        return (" " + term + " ").contains(" " + phrase + " ");
    }
}
