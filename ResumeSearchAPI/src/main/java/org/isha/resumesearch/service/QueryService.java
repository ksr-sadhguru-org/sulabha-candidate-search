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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        List<String> requiredSkills = resolveRequired(parsedQuery.skills());
        List<String> optionalRoles = resolveOptional(parsedQuery.roles());
        QueryResult skillResults = queryRepository.getQueryResult(requiredSkills, optionalRoles);

        List<QueryPersonaMatch> combinedMatches = new ArrayList<>(skillResults.result());
        Set<String> alreadyMatchedIds = new HashSet<>(skillResults.result().stream().map(QueryPersonaMatch::uniquefileId).toList());
        Map<String, QueryRepository.KeywordMatch> keywordMatches = queryRepository.keywordMatch(extractKeywords(queryText));
        for (Map.Entry<String, QueryRepository.KeywordMatch> entry : keywordMatches.entrySet()) {
            if (alreadyMatchedIds.add(entry.getKey())) {
                combinedMatches.add(QueryPersonaMatch.keywordOnly(entry.getKey(), entry.getValue().keywords(), entry.getValue().partial()));
            }
        }

        QueryResult joinedResult = queryRepository.leftJoinWithApplicationData(new QueryResult(combinedMatches), filters);
        // Three bands, strongest match first: skill/taxonomy match, then full keyword match, then partial
        // keyword match (e.g. "sing" only ever found inside "Perusing"). Within each band, candidates who
        // also satisfy the application filters are shown before those who don't.
        Comparator<QueryPersonaMatch> byMatchBand = Comparator.comparingInt(QueryService::matchBandRank);
        Comparator<QueryPersonaMatch> byFilterMatch = Comparator.comparing(QueryPersonaMatch::matchesApplicationFilters).reversed();
        List<QueryPersonaMatch> ordered = joinedResult.result().stream()
                .sorted(byMatchBand.thenComparing(byFilterMatch))
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

    /** Splits the raw query into significant words for the keyword fallback - independent of the LLM's
     *  skill-extraction judgment, so a word the LLM doesn't recognize as a "real skill" can still match. */
    private List<String> extractKeywords(String queryText) {
        return WORD_SPLIT.splitAsStream(queryText.toLowerCase(Locale.ROOT))
                .filter(w -> w.length() >= MIN_KEYWORD_LENGTH && !STOPWORDS.contains(w))
                .distinct()
                .toList();
    }

    /** Resolves each requested skill to a canonical known in the system. AND-required means all of them
     *  must match - if even one has never been seen on any candidate (no exact or fuzzy synonym match at
     *  all), the skill/taxonomy path can never satisfy the full requirement, so this returns empty rather
     *  than silently searching on the subset that did resolve. (The raw-text keyword fallback in
     *  QueryRepository.keywordMatch still independently catches a textual mention of the unresolved term -
     *  just honestly labeled as a keyword match, not a precise skill match.) */
    private List<String> resolveRequired(List<QueryParseEntity> requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        Map<String, String> mapping = synonymRepository.lookupBatch(toNamedEntities(requested));

        List<String> resolved = new ArrayList<>();
        for (QueryParseEntity entity : requested) {
            Optional<String> canonical = resolve(entity, mapping);
            if (canonical.isEmpty()) {
                log.info("Requested skill '{}' has no known match in the system - skill/taxonomy search returns no results", entity.canonical());
                return List.of();
            }
            resolved.add(canonical.get());
        }
        return resolved.stream().distinct().toList();
    }

    /** Resolves each requested role/title to a canonical known in the system, same as resolveRequired but
     *  best-effort: a role that doesn't resolve is just dropped rather than failing the whole query - job
     *  titles are too inconsistently worded across resumes to treat as a hard requirement. */
    private List<String> resolveOptional(List<QueryParseEntity> requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        Map<String, String> mapping = synonymRepository.lookupBatch(toNamedEntities(requested));
        return requested.stream()
                .map(entity -> resolve(entity, mapping))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    private Optional<String> resolve(QueryParseEntity entity, Map<String, String> mapping) {
        return Stream.concat(entity.synonyms().stream(), Stream.of(entity.canonical()))
                .map(synonymRepository::normalize)
                .map(mapping::get)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private List<NamedEntity> toNamedEntities(List<QueryParseEntity> entities) {
        return entities.stream().map(e -> new NamedEntity(e.canonical(), e.synonyms())).toList();
    }
}
