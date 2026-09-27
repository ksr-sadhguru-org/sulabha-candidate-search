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
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds who has one need by its words (a term plus its equivalents), in order of strength:
 * EXACT - a profile term is the query term or ends with it ("music teacher" for "teacher");
 * RELATED - the same, via one of the LLM's equivalents ("tutor") or only via an education entry;
 * TEXT - only the resume text contains it (full-text search).
 */
@Component
class NeedMatcher {

    static final int EXACT = 3, RELATED = 2, TEXT = 1, SAME_DOMAIN = 0;

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

    /** A skill ("java") counts wherever it appears - any job, a skill entry, or the resume text. */
    Map<String, Result> matchSkill(Need need) {
        return match(need, false, h -> true, true);
    }

    /** The job as worded ("music instructor"): only job entries count, only in the query's domain when there is one;
     *  resume text only when the query names neither a role nor a domain ("astronaut"). */
    Map<String, Result> matchJob(Need need, String domain, boolean textFallback) {
        return match(need, false, h -> "profession".equalsIgnoreCase(h.kind()) && (domain == null || domain.equals(h.domain())),
                textFallback);
    }

    /** Ranking extras ("senior"): profile terms containing the need anywhere. */
    Map<String, Result> matchNice(Need need) {
        return match(need, true, h -> true, true);
    }

    private Map<String, Result> match(Need need, boolean anywhere, Predicate<Hit> allowed, boolean textFallback) {
        String main = Terms.normalize(need.term());
        List<String> phrases = Stream.concat(Stream.of(main),
                        Optional.ofNullable(need.alternatives()).orElse(List.of()).stream().map(Terms::normalize))
                .filter(p -> !p.isEmpty()).distinct().toList();

        Map<String, Result> results = new HashMap<>();
        (anywhere ? expertise.findContaining(phrases) : expertise.findEndingWith(phrases)).stream()
                .filter(allowed)
                .collect(Collectors.groupingBy(Hit::candidateId))
                .forEach((id, hits) -> {
                    // A degree ("B.Tech Electrical Engineering") is not the profession itself - it counts as related.
                    List<Hit> exact = hits.stream()
                            .filter(h -> !"education".equalsIgnoreCase(h.kind()))
                            .filter(h -> anywhere ? containsWords(h.term(), main) : Terms.matches(h.term(), main))
                            .toList();
                    results.put(id, exact.isEmpty() ? new Result(RELATED, hits, null) : new Result(EXACT, exact, null));
                });
        if (textFallback) {
            phrases.stream()
                    .filter(p -> p.matches("[a-z0-9 ]+")) // full-text search can't represent "c++", "c#", ".net"
                    .forEach(p -> textSearch.findContaining(p).forEach(id -> results.putIfAbsent(id, new Result(TEXT, List.of(), p))));
        }
        return results;
    }

    private static boolean containsWords(String term, String phrase) {
        return (" " + term + " ").contains(" " + phrase + " ");
    }
}
