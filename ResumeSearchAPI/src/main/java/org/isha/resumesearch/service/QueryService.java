package org.isha.resumesearch.service;

import org.isha.resumesearch.db.NamedEntity;
import org.isha.resumesearch.db.QueryRepository;
import org.isha.resumesearch.db.SynonymRepository;
import org.isha.resumesearch.dto.ApplicationFilterResponse;
import org.isha.resumesearch.dto.QueryParseEntity;
import org.isha.resumesearch.dto.QueryParseResponse;
import org.isha.resumesearch.dto.QueryPersonaMatch;
import org.isha.resumesearch.dto.QueryResult;
import org.isha.resumesearch.llm.LlmExtractor;
import org.isha.resumesearch.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Orchestrates the "dual query": skill/role matching against candidate_search, a raw-text keyword
 *  fallback for anything the skill taxonomy misses, plus soft filtering against candidate_application_data. */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final int MIN_KEYWORD_LENGTH = 3;
    // Common sentence-glue/filler words to drop from a recruiter's free-text query before the raw-text
    // keyword fallback runs, so e.g. "also"/"can"/"who" don't turn into noisy matches against every resume
    // that happens to contain them. Conservative about anything that could plausibly be a real skill/tool
    // name - e.g. "solid" is deliberately NOT here despite being a common filler word, since "SOLID
    // principles" is a genuine thing a query might ask for.
    private static final Set<String> STOPWORDS = Set.of(
            // Articles / determiners / quantifiers
            "a", "an", "the", "some", "any", "that", "this", "these", "those", "such", "each", "every", "all", "no",
            // Pronouns
            "who", "whom", "whoever", "someone", "somebody", "anyone", "anybody", "everyone", "everybody",
            "he", "she", "they", "them", "their", "him", "her", "his", "hers", "it", "its",
            "we", "us", "our", "you", "your", "i", "me", "my",
            // Conjunctions
            "and", "or", "but", "nor", "also", "as", "because", "since", "so", "yet", "both", "either", "neither", "whether",
            // Prepositions
            "in", "on", "at", "for", "of", "to", "with", "without", "by", "from", "about", "into", "onto",
            "upon", "over", "under", "between", "among", "through", "during", "after", "before",
            // Auxiliary / modal verbs
            "is", "are", "was", "were", "be", "been", "being", "has", "have", "had", "having",
            "do", "does", "did", "will", "would", "shall", "should", "can", "could", "may", "might", "must",
            // Intensifiers / generic qualifiers
            "very", "really", "quite", "just", "only", "too", "well", "strong", "good", "great",
            "excellent", "proven", "ideal", "ideally",
            // Request/preference verbs
            "looking", "look", "need", "needs", "needed", "needing", "want", "wants", "wanted", "wanting",
            "seeking", "seek", "require", "requires", "required", "requiring", "prefer", "prefers",
            "preferred", "preferably", "able", "ability", "capable",
            // Generic HR nouns
            "candidate", "candidates", "person", "people", "experience", "years", "year", "based",
            "role", "position", "job", "work", "working", "background", "knowledge", "skill", "skills",
            "profile", "resume", "cv",
            // Misc
            "etc", "eg", "ie", "vs", "like"
    );

    private final SynonymRepository synonymRepository;
    private final QueryRepository queryRepository;
    private final LlmExtractor llmExtractor;

    public QueryService(SynonymRepository synonymRepository, QueryRepository queryRepository, LlmExtractor llmExtractor) {
        this.synonymRepository = synonymRepository;
        this.queryRepository = queryRepository;
        this.llmExtractor = llmExtractor;
    }

    public QueryResult query(String queryText) {
        // The skill-parse and application-filter LLM calls are both derived from the same query text
        // and don't depend on each other's output - run them concurrently rather than back-to-back.
        CompletableFuture<QueryParseResponse> parsedQueryFuture = CompletableFuture.supplyAsync(() -> llmExtractor.extractFromQuery(queryText));
        CompletableFuture<ApplicationFilterResponse> filtersFuture = CompletableFuture.supplyAsync(() -> llmExtractor.extractApplicationFilters(queryText));

        QueryParseResponse parsedQuery = parsedQueryFuture.join();
        ApplicationFilterResponse filters = filtersFuture.join();
        // Both calls hit the same endpoint with the same credentials, so a null from either almost always
        // means the LLM itself is unreachable/misconfigured - AzureLlmExtractor has already logged the cause.
        if (parsedQuery == null || filters == null) {
            throw new LlmUnavailableException("Search failed: the LLM call failed - check the backend's OPENAI_* settings (API key, base URL, model) and its logs");
        }
        List<List<String>> requiredSkills = resolveRequired(parsedQuery.skills());
        List<String> optionalRoles = resolveOptional(parsedQuery.roles());
        // A requested skill nobody has (e.g. "C++") means no one can be a skill match - don't fall back to
        // searching on the roles alone, which would list e.g. every developer as if they had C++.
        boolean requiredSkillUnknown = !parsedQuery.skills().isEmpty() && requiredSkills.isEmpty();
        QueryResult skillResults = requiredSkillUnknown
                ? new QueryResult(List.of())
                : queryRepository.getQueryResult(requiredSkills, optionalRoles);

        List<QueryPersonaMatch> combinedMatches = new ArrayList<>(skillResults.result());
        Set<String> alreadyMatchedIds = new HashSet<>(skillResults.result().stream().map(QueryPersonaMatch::uniquefileId).toList());
        List<String> queryKeywords = extractKeywords(queryText);
        List<String> relatedKeywords = relatedKeywords(parsedQuery, queryKeywords);
        Map<String, QueryRepository.KeywordMatch> keywordMatches = queryRepository.keywordMatch(
                Stream.concat(queryKeywords.stream(), relatedKeywords.stream()).toList());
        for (Map.Entry<String, QueryRepository.KeywordMatch> entry : keywordMatches.entrySet()) {
            if (alreadyMatchedIds.add(entry.getKey())) {
                List<String> hits = entry.getValue().keywords();
                combinedMatches.add(QueryPersonaMatch.keywordOnly(entry.getKey(),
                        hits.stream().filter(queryKeywords::contains).toList(),
                        hits.stream().filter(relatedKeywords::contains).toList(),
                        entry.getValue().partial()));
            }
        }

        QueryResult joinedResult = queryRepository.leftJoinWithApplicationData(new QueryResult(combinedMatches), filters);
        // Three bands, strongest match first: skill/taxonomy match, then full keyword match, then partial
        // keyword match (e.g. "sing" only ever found inside "Perusing"). Within the keyword bands, a hit on
        // a skill/role word outranks one on a mere filter value (e.g. "java" beats "coimbatore"). Then
        // candidates who satisfy all application filters come first, then those who satisfy more of them.
        Map<String, Double> keywordWeights = keywordWeights(parsedQuery, filters);
        Comparator<QueryPersonaMatch> byMatchBand = Comparator.comparingInt(QueryService::matchBandRank);
        Comparator<QueryPersonaMatch> byKeywordImportance = Comparator.comparingDouble(
                (QueryPersonaMatch m) -> keywordScore(m, keywordWeights)).reversed();
        Comparator<QueryPersonaMatch> byFilterMatch = Comparator.comparing(QueryPersonaMatch::matchesApplicationFilters).reversed();
        Comparator<QueryPersonaMatch> byFiltersMatchedCount = Comparator.comparingLong(
                (QueryPersonaMatch m) -> m.filterStatus().stream().filter(QueryPersonaMatch.FilterFieldStatus::matched).count()).reversed();
        List<QueryPersonaMatch> ordered = joinedResult.result().stream()
                .sorted(byMatchBand.thenComparing(byKeywordImportance).thenComparing(byFilterMatch).thenComparing(byFiltersMatchedCount))
                .toList();
        QueryResult finalResult = new QueryResult(ordered);

        long matchCount = finalResult.result().stream().filter(QueryPersonaMatch::matchesApplicationFilters).count();
        long keywordOnlyCount = finalResult.result().stream().filter(QueryPersonaMatch::matchedViaKeyword).count();
        long partialKeywordCount = finalResult.result().stream().filter(QueryPersonaMatch::partialKeywordMatch).count();
        log.info("Query completed. Found {} total candidates ({} via keyword fallback, {} partial), {} match application filters",
                finalResult.result().size(), keywordOnlyCount, partialKeywordCount, matchCount);
        return finalResult;
    }

    /** 0 = skill/taxonomy match, 1 = full keyword match, 2 = partial keyword match - lower sorts first. */
    private static int matchBandRank(QueryPersonaMatch match) {
        if (!match.matchedViaKeyword()) {
            return 0;
        }
        return match.partialKeywordMatch() ? 2 : 1;
    }

    private static final double SKILL_KEYWORD_WEIGHT = 3;
    private static final double ROLE_KEYWORD_WEIGHT = 2;
    private static final double OTHER_KEYWORD_WEIGHT = 1;
    private static final double FILTER_KEYWORD_WEIGHT = 0.5;
    private static final double RELATED_KEYWORD_WEIGHT = 0.75;

    /** How much each query word counts toward a keyword match, based on what the LLM took it to mean:
     *  part of a skill > part of a role > unclassified > part of a filter value (location, etc. - already
     *  checked by the filters themselves, so a hit on it alone says little about fit). Words missing from
     *  the map weigh OTHER_KEYWORD_WEIGHT. */
    private static Map<String, Double> keywordWeights(QueryParseResponse parsedQuery, ApplicationFilterResponse filters) {
        Map<String, Double> weights = new HashMap<>();
        // Lowest weight first, so a word that's both (e.g. a skill that's also in a filter) keeps the higher one.
        filters.asValues().forEach(v -> addWords(weights, v, FILTER_KEYWORD_WEIGHT));
        parsedQuery.roles().forEach(e -> addEntityWords(weights, e, ROLE_KEYWORD_WEIGHT));
        parsedQuery.skills().forEach(e -> addEntityWords(weights, e, SKILL_KEYWORD_WEIGHT));
        return weights;
    }

    private static void addEntityWords(Map<String, Double> weights, QueryParseEntity entity, double weight) {
        addWords(weights, entity.canonical(), weight);
        if (entity.synonyms() != null) {
            entity.synonyms().forEach(s -> addWords(weights, s, weight));
        }
    }

    private static void addWords(Map<String, Double> weights, String phrase, double weight) {
        if (phrase == null) {
            return;
        }
        WORD_SPLIT.splitAsStream(phrase.toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .forEach(w -> weights.put(w, weight));
    }

    /** Sum of the matched keywords' weights - 0 for skill-band matches, which have no matched keywords.
     *  A related word (the LLM's equivalent, not typed by the user) counts less than any typed word. */
    private static double keywordScore(QueryPersonaMatch match, Map<String, Double> weights) {
        return match.matchedKeywords().stream().mapToDouble(k -> weights.getOrDefault(k, OTHER_KEYWORD_WEIGHT)).sum()
                + match.relatedKeywords().size() * RELATED_KEYWORD_WEIGHT;
    }

    /** The main (last) word of each equivalent the LLM gave for the query's skills/roles, e.g. "Music
     *  Instructor" -> "instructor" - so the keyword fallback also finds resumes worded differently from the
     *  query. The main word, not the whole phrase: "music instructor" wouldn't match "Yoga Instructor". */
    private static List<String> relatedKeywords(QueryParseResponse parsedQuery, List<String> queryKeywords) {
        return Stream.concat(parsedQuery.skills().stream(), parsedQuery.roles().stream())
                .flatMap(e -> e.synonyms() == null ? Stream.empty() : e.synonyms().stream())
                .map(s -> {
                    List<String> words = WORD_SPLIT.splitAsStream(s.toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).toList();
                    return words.isEmpty() ? "" : words.get(words.size() - 1);
                })
                .filter(w -> w.length() >= MIN_KEYWORD_LENGTH && !STOPWORDS.contains(w) && !queryKeywords.contains(w))
                .distinct()
                .toList();
    }

    /** Splits the raw query into significant words for the keyword fallback - independent of the LLM's
     *  skill-extraction judgment, so a word the LLM doesn't recognize as a "real skill" can still match. */
    private List<String> extractKeywords(String queryText) {
        return WORD_SPLIT.splitAsStream(queryText.toLowerCase(Locale.ROOT))
                .filter(w -> w.length() >= MIN_KEYWORD_LENGTH && !STOPWORDS.contains(w))
                .distinct()
                .toList();
    }

    /** Resolves each requested skill to the group of canonicals it can mean ("teacher" -> every kind of
     *  teacher). AND-required: a candidate needs at least one canonical from EVERY group - if even one skill
     *  has never been seen on any candidate (no match at all), the skill/taxonomy path can never satisfy the
     *  full requirement, so this returns empty rather than silently searching on the subset that did resolve.
     *  (The raw-text keyword fallback in QueryRepository.keywordMatch still independently catches a textual
     *  mention of the unresolved term - just honestly labeled as a keyword match, not a precise skill match.) */
    private List<List<String>> resolveRequired(List<QueryParseEntity> requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> mapping = synonymRepository.lookupBatch(toNamedEntities(requested));

        List<List<String>> groups = new ArrayList<>();
        for (QueryParseEntity entity : requested) {
            List<String> canonicals = resolve(entity, mapping);
            if (canonicals.isEmpty()) {
                log.info("Requested skill '{}' has no known match in the system - skill/taxonomy search returns no results", entity.canonical());
                return List.of();
            }
            groups.add(canonicals);
        }
        return groups.stream().distinct().toList();
    }

    /** Resolves each requested role/title to a canonical known in the system, same as resolveRequired but
     *  best-effort: a role that doesn't resolve is just dropped rather than failing the whole query - job
     *  titles are too inconsistently worded across resumes to treat as a hard requirement. */
    private List<String> resolveOptional(List<QueryParseEntity> requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> mapping = synonymRepository.lookupBatch(toNamedEntities(requested));
        return requested.stream()
                .flatMap(entity -> resolve(entity, mapping).stream())
                .distinct()
                .toList();
    }

    /** Every canonical any of the entity's names (canonical + synonyms, incl. LLM-supplied related terms) maps to. */
    private List<String> resolve(QueryParseEntity entity, Map<String, Set<String>> mapping) {
        return Stream.concat(entity.synonyms().stream(), Stream.of(entity.canonical()))
                .map(synonymRepository::normalize)
                .map(mapping::get)
                .filter(Objects::nonNull)
                .flatMap(Set::stream)
                .distinct()
                .sorted()
                .toList();
    }

    private List<NamedEntity> toNamedEntities(List<QueryParseEntity> entities) {
        return entities.stream().map(e -> new NamedEntity(e.canonical(), e.synonyms())).toList();
    }
}
