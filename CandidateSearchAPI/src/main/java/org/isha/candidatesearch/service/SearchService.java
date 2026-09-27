package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.CandidateRepository;
import org.isha.candidatesearch.db.CandidateRepository.Row;
import org.isha.candidatesearch.db.ExpertiseRepository;
import org.isha.candidatesearch.db.ExpertiseRepository.Hit;
import org.isha.candidatesearch.dto.CandidateMatch;
import org.isha.candidatesearch.dto.CandidateMatch.FilterCheck;
import org.isha.candidatesearch.dto.CandidateMatch.MatchedEntry;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.dto.ParsedQuery.Need;
import org.isha.candidatesearch.dto.SearchResponse;
import org.isha.candidatesearch.service.NeedMatcher.Result;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Search: parse the query (cached), find who meets the must-haves, check filters, rank.
 * When the query names skills, a generic job word in it ("developer") is context: it restricts the field and
 * ranks, but is not counted as a must-have. Others with a job in the same field as the direct matches follow last.
 * Ranking, strongest first: how many must-haves are met (all, some, none - same field only), how they were found
 * (profile > equivalent > resume text), then filters passed, then ranking extras met ("senior"), score, relevant years.
 */
@Service
public class SearchService {

    static final String NOT_UNDERSTOOD = "We couldn't understand this search. Try describing the role, skills or location, e.g. 'Java developer in Coimbatore'.";
    static final String TOO_VAGUE = "Please add a role, skill or location to search for, e.g. 'Java developer in Coimbatore'.";

    /** A result plus what it is ranked by. */
    private record Ranked(CandidateMatch match, int mustMet, int quality, int niceMet, int score, double years) {
    }

    private static final Comparator<Ranked> RANKING = Comparator.comparingInt(Ranked::mustMet).reversed()
            .thenComparing(Comparator.comparingInt(Ranked::quality).reversed())
            .thenComparing(Comparator.comparingInt((Ranked r) -> r.match().filtersPassed()).reversed())
            .thenComparing(Comparator.comparingInt(Ranked::niceMet).reversed())
            .thenComparing(Comparator.comparingInt(Ranked::score).reversed())
            .thenComparing(Comparator.comparingDouble(Ranked::years).reversed())
            .thenComparing(r -> Objects.requireNonNullElse(r.match().name(), "~"))
            .thenComparing(r -> r.match().candidateId());

    private final QueryParser parser;
    private final NeedMatcher matcher;
    private final CandidateRepository candidates;
    private final ExpertiseRepository expertise;

    public SearchService(QueryParser parser, NeedMatcher matcher, CandidateRepository candidates, ExpertiseRepository expertise) {
        this.parser = parser;
        this.matcher = matcher;
        this.candidates = candidates;
        this.expertise = expertise;
    }

    /** The ranked page [offset, offset + limit) of everyone matching; total counts them all. */
    public SearchResponse search(String query, int offset, int limit) {
        ParsedQuery q = parser.parse(query);
        // Only extras asked for ("senior") - treat them as the must-haves rather than listing everyone.
        List<Need> asked = q.must().isEmpty() ? q.nice() : q.must();
        boolean namesSkill = asked.stream().anyMatch(n -> !n.isGeneric());
        List<Need> must = namesSkill ? asked.stream().filter(n -> !n.isGeneric()).toList() : asked;
        List<Need> nice = Stream.concat(q.must().isEmpty() ? Stream.<Need>empty() : q.nice().stream(),
                namesSkill ? asked.stream().filter(Need::isGeneric) : Stream.<Need>empty()).toList();
        if (!q.understood()) {
            return new SearchResponse(NOT_UNDERSTOOD, q, List.of(), 0);
        }
        if (must.isEmpty() && !FilterEvaluator.hasAny(q.filters())) {
            return new SearchResponse(TOO_VAGUE, q, List.of(), 0);
        }

        // The field only restricts a query that names a specific skill; a generic job alone ("teacher") stays open.
        String field = namesSkill ? q.field() : null;
        List<Map<String, Result>> mustResults = must.stream().map(n -> matcher.matchMust(n, field)).toList();
        List<Map<String, Result>> niceResults = nice.stream().map(matcher::matchNice).toList();
        // Everyone meeting at least one must-have (those meeting all rank first); a filter-only query considers
        // everyone and keeps those passing all filters.
        Set<String> ids = must.isEmpty() ? null : mustResults.stream().flatMap(m -> m.keySet().stream())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Hit> sameField = must.isEmpty() ? Map.of() : sameFieldJobs(mustResults, q.field(), ids);
        if (ids != null) {
            ids.addAll(sameField.keySet());
        }

        List<CandidateMatch> results = candidates.findRows(ids).values().stream()
                .map(row -> rank(row, q, must, mustResults, nice, niceResults, sameField.get(row.id())))
                .filter(r -> !must.isEmpty() || r.match().filtersPassed() == r.match().filters().size())
                .sorted(RANKING)
                .map(Ranked::match)
                .toList();
        List<CandidateMatch> page = results.stream().skip(offset).limit(limit).toList();
        return new SearchResponse(results.isEmpty() ? noMatchMessage(must, q) : null, q, page, results.size());
    }

    /**
     * Candidates not matched yet whose job is in the same field as the direct matches: the most common field among
     * the jobs that matched (a Java trainer doesn't make "java developer" a teaching search), or the query's field
     * when no job matched. The best such job per candidate.
     */
    private Map<String, Hit> sameFieldJobs(List<Map<String, Result>> mustResults, String queryField, Set<String> matched) {
        Map<String, Long> jobFields = mustResults.stream().flatMap(m -> m.values().stream())
                .filter(r -> r.quality() >= NeedMatcher.RELATED)
                .flatMap(r -> r.hits().stream())
                .filter(h -> "profession".equalsIgnoreCase(h.kind()) && h.field() != null)
                .collect(java.util.stream.Collectors.groupingBy(Hit::field, java.util.stream.Collectors.counting()));
        long most = jobFields.values().stream().mapToLong(Long::longValue).max().orElse(0);
        Set<String> fields = most > 0
                ? jobFields.entrySet().stream().filter(e -> e.getValue() == most).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                : queryField == null ? Set.of() : Set.of(queryField);
        return expertise.findJobsInFields(fields).stream()
                .filter(h -> !matched.contains(h.candidateId()))
                .collect(java.util.stream.Collectors.toMap(Hit::candidateId, h -> h, (a, b) -> a.score() >= b.score() ? a : b));
    }

    private Ranked rank(Row row, ParsedQuery q, List<Need> must, List<Map<String, Result>> mustResults,
                        List<Need> nice, List<Map<String, Result>> niceResults, Hit sameFieldJob) {
        List<Result> perNeed = mustResults.stream().map(m -> m.get(row.id())).toList(); // null: need not met
        List<Result> met = perNeed.stream().filter(Objects::nonNull).toList();
        List<FilterCheck> filters = Stream.concat(FilterEvaluator.evaluate(q.filters(), row).stream(),
                IntStream.range(0, must.size()).mapToObj(i -> yearsCheck(must.get(i), perNeed.get(i))).filter(Objects::nonNull)).toList();
        List<String> niceMet = IntStream.range(0, nice.size())
                .filter(i -> niceResults.get(i).containsKey(row.id())).mapToObj(i -> nice.get(i).term()).toList();
        int quality = met.stream().mapToInt(Result::quality).min().orElse(0);

        String type = sameFieldJob != null && met.isEmpty() ? "field"
                : !must.isEmpty() && met.size() < must.size() ? "partial" : matchType(must.isEmpty(), quality);
        List<MatchedEntry> entries = sameFieldJob != null && met.isEmpty()
                ? List.of(new MatchedEntry(sameFieldJob.name(), sameFieldJob.score(), sameFieldJob.years(), "same field: " + sameFieldJob.field(), true))
                : matchedEntries(met);
        CandidateMatch match = new CandidateMatch(row.id(), row.name(), type, met.size(), must.size(),
                entries, met.stream().map(Result::textTerm).filter(Objects::nonNull).distinct().toList(), niceMet,
                filters, (int) filters.stream().filter(f -> FilterEvaluator.PASS.equals(f.status())).count(),
                row.experience(), row.totalYears(), row.qualification(), row.jobLocation(), row.languages(),
                row.isMeditator(), row.stayInAshram());
        if (sameFieldJob != null && met.isEmpty()) {
            return new Ranked(match, 0, NeedMatcher.SAME_FIELD, niceMet.size(), sameFieldJob.score(),
                    Objects.requireNonNullElse(sameFieldJob.years(), 0.0));
        }
        return new Ranked(match, met.size(), quality, niceMet.size(), met.stream().mapToInt(Result::bestScore).sum(),
                met.stream().map(Result::years).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum());
    }

    /** "Python experience: 9 years (wanted 8+)" - for a must-have asked with years ("python developer with 8+ years"). */
    private static FilterCheck yearsCheck(Need need, Result result) {
        if (need.minYears() == null) {
            return null;
        }
        Double years = result == null ? null : result.years();
        String status = result == null ? FilterEvaluator.FAIL : years == null ? FilterEvaluator.NOT_ON_FILE : years >= need.minYears() ? FilterEvaluator.PASS : FilterEvaluator.FAIL;
        return new FilterCheck("experience:" + need.term(), capitalize(need.term()) + " experience", status,
                FilterEvaluator.fmt(need.minYears()) + "+ years", years == null ? null : FilterEvaluator.fmt(years) + " years");
    }

    /** One entry per matched area of expertise, strongest first. */
    private static List<MatchedEntry> matchedEntries(List<Result> met) {
        Map<String, MatchedEntry> byName = new LinkedHashMap<>();
        met.forEach(r -> r.hits().stream().sorted(Comparator.comparingInt(Hit::score).reversed())
                .forEach(h -> byName.putIfAbsent(h.name().toLowerCase(),
                        new MatchedEntry(h.name(), h.score(), h.years(), h.term(), r.quality() == NeedMatcher.RELATED))));
        return byName.values().stream().sorted(Comparator.comparingInt(MatchedEntry::score).reversed()).toList();
    }

    private static String matchType(boolean filterOnly, int quality) {
        if (filterOnly) return "filters";
        return switch (quality) {
            case NeedMatcher.EXACT -> "profile";
            case NeedMatcher.RELATED -> "related";
            default -> "text";
        };
    }

    /** "No candidates match 'astronaut' in Coimbatore." */
    private static String noMatchMessage(List<Need> must, ParsedQuery q) {
        if (must.isEmpty()) {
            return "No candidates match these filters.";
        }
        String terms = String.join("' and '", must.stream().map(Need::term).toList());
        String place = q.filters() == null || q.filters().location() == null ? null
                : Stream.of(q.filters().location().city(), q.filters().location().state(), q.filters().location().country())
                .filter(p -> p != null && !p.isBlank()).findFirst().orElse(null);
        return "No candidates match '" + terms + "'" + (place == null ? "" : " in " + place) + ".";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
