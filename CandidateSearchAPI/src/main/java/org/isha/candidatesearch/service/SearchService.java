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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Search: parse the query (cached) into the job asked (role + domain + wording), the skills asked, extras and
 * filters; find who meets them; check filters; rank.
 * The job: role + domain -> jobs with both are direct matches, other roles in the same domain follow as related,
 * other domains are left out; role only -> that role in any domain; domain only -> that domain in any role.
 * Skills count wherever they appear. Ranking, strongest first: skills met, then the job (direct > same domain >
 * none), then how things were found (profile > equivalent > resume text), filters passed, extras, score, years.
 */
@Service
public class SearchService {

    static final String NOT_UNDERSTOOD = "We couldn't understand this search. Try describing the role, skills or location, e.g. 'Java developer in Coimbatore'.";
    static final String TOO_VAGUE = "Please add a role, skill or location to search for, e.g. 'Java developer in Coimbatore'.";

    private static final int JOB_DIRECT = 2, JOB_SAME_DOMAIN = 1, JOB_NONE = 0;

    /** A result plus what it is ranked by. */
    private record Ranked(CandidateMatch match, int skillsMet, int job, int quality, int niceMet, int score, double years) {
    }

    private static final Comparator<Ranked> RANKING = Comparator.comparingInt(Ranked::skillsMet).reversed()
            .thenComparing(Comparator.comparingInt(Ranked::job).reversed())
            .thenComparing(Comparator.comparingInt(Ranked::quality).reversed())
            .thenComparing(Comparator.comparingInt((Ranked r) -> r.match().filtersPassed()).reversed())
            .thenComparing(Comparator.comparingInt(Ranked::niceMet).reversed())
            .thenComparing(Comparator.comparingInt(Ranked::score).reversed())
            .thenComparing(Comparator.comparingDouble(Ranked::years).reversed())
            .thenComparing(r -> Objects.requireNonNullElse(r.match().name(), "~"))
            .thenComparing(r -> r.match().candidateId());

    /** Who meets the job directly, and who only has a job in the same domain (another role). */
    private record JobMatches(Map<String, Result> direct, Map<String, Hit> sameDomain) {
    }

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
        if (!q.understood()) {
            return new SearchResponse(NOT_UNDERSTOOD, q, List.of(), 0);
        }
        boolean asksJob = q.role() != null || q.domain() != null || q.job() != null;
        // Only extras asked for ("senior") - treat them as the skills rather than listing everyone.
        boolean onlyExtras = q.must().isEmpty() && !asksJob;
        List<Need> skills = onlyExtras ? q.nice() : q.must();
        List<Need> nice = onlyExtras ? List.of() : q.nice();
        boolean filterOnly = skills.isEmpty() && !asksJob;
        if (filterOnly && !FilterEvaluator.hasAny(q.filters())) {
            return new SearchResponse(TOO_VAGUE, q, List.of(), 0);
        }

        List<Map<String, Result>> skillResults = skills.stream().map(matcher::matchSkill).toList();
        JobMatches job = asksJob ? matchJob(q) : new JobMatches(Map.of(), Map.of());
        List<Map<String, Result>> niceResults = nice.stream().map(matcher::matchNice).toList();
        // Everyone meeting a skill or the job, or with a job in the same domain; a filter-only query considers
        // everyone and keeps those passing all filters.
        Set<String> ids = filterOnly ? null : Stream.of(
                        skillResults.stream().flatMap(m -> m.keySet().stream()),
                        job.direct().keySet().stream(), job.sameDomain().keySet().stream())
                .flatMap(s -> s).collect(Collectors.toSet());

        int needs = skills.size() + (asksJob ? 1 : 0);
        List<CandidateMatch> results = candidates.findRows(ids).values().stream()
                .map(row -> rank(row, q, skills, skillResults, job, needs, nice, niceResults, filterOnly))
                .filter(r -> !filterOnly || r.match().filtersPassed() == r.match().filters().size())
                .sorted(RANKING)
                .map(Ranked::match)
                .toList();
        List<CandidateMatch> page = results.stream().skip(offset).limit(limit).toList();
        return new SearchResponse(results.isEmpty() ? noMatchMessage(skills, q, filterOnly) : null, q, page, results.size());
    }

    /**
     * The job asked: its wording matched against job entries (in the query's domain when there is one), plus every
     * job with the asked role and/or domain; with both, other roles in that domain are related (same domain).
     */
    private JobMatches matchJob(ParsedQuery q) {
        Map<String, Result> direct = new HashMap<>();
        if (q.job() != null) {
            direct.putAll(matcher.matchJob(q.job(), q.domain(), q.role() == null && q.domain() == null));
        }
        if (q.role() != null || q.domain() != null) {
            String label = Stream.of(q.role(), q.domain()).filter(Objects::nonNull).collect(Collectors.joining(" + "));
            expertise.findJobs(q.role(), q.domain()).stream()
                    .map(h -> relabel(h, label))
                    .collect(Collectors.groupingBy(Hit::candidateId))
                    .forEach((id, hits) -> direct.merge(id, new Result(NeedMatcher.EXACT, hits, null),
                            (a, b) -> a.quality() >= b.quality() ? a : b));
        }
        Map<String, Hit> sameDomain = q.role() == null || q.domain() == null ? Map.of()
                : expertise.findJobs(null, q.domain()).stream()
                        .filter(h -> !direct.containsKey(h.candidateId()))
                        .collect(Collectors.toMap(Hit::candidateId, h -> h, (a, b) -> a.score() >= b.score() ? a : b));
        return new JobMatches(direct, sameDomain);
    }

    private static Hit relabel(Hit h, String via) {
        return new Hit(h.candidateId(), h.expertiseId(), h.name(), h.kind(), h.role(), h.domain(), h.source(), h.score(), h.years(), via);
    }

    private Ranked rank(Row row, ParsedQuery q, List<Need> skills, List<Map<String, Result>> skillResults, JobMatches job,
                        int needs, List<Need> nice, List<Map<String, Result>> niceResults, boolean filterOnly) {
        List<Result> perSkill = skillResults.stream().map(m -> m.get(row.id())).toList(); // null: skill not met
        Result jobResult = job.direct().get(row.id());
        Hit sameDomainJob = job.sameDomain().get(row.id());
        List<Result> met = new ArrayList<>(perSkill.stream().filter(Objects::nonNull).toList());
        if (jobResult != null) {
            met.add(jobResult);
        }

        List<FilterCheck> filters = Stream.of(
                        FilterEvaluator.evaluate(q.filters(), row).stream(),
                        IntStream.range(0, skills.size()).mapToObj(i -> yearsCheck(skills.get(i), perSkill.get(i))),
                        Stream.of(q.job() == null ? null : yearsCheck(q.job(), jobResult)))
                .flatMap(s -> s).filter(Objects::nonNull).toList();
        List<String> niceMet = IntStream.range(0, nice.size())
                .filter(i -> niceResults.get(i).containsKey(row.id())).mapToObj(i -> nice.get(i).term()).toList();
        int quality = met.stream().mapToInt(Result::quality).min().orElse(NeedMatcher.SAME_DOMAIN);
        String type = filterOnly ? "filters"
                : met.isEmpty() ? "domain"
                : met.size() < needs ? "partial" : matchType(quality);
        List<MatchedEntry> entries = met.isEmpty() && sameDomainJob != null
                ? List.of(new MatchedEntry(sameDomainJob.name(), sameDomainJob.score(), sameDomainJob.years(),
                        "same domain: " + sameDomainJob.domain(), true))
                : matchedEntries(met);

        CandidateMatch match = new CandidateMatch(row.id(), row.name(), type, met.size(), needs,
                entries, met.stream().map(Result::textTerm).filter(Objects::nonNull).distinct().toList(), niceMet,
                filters, (int) filters.stream().filter(f -> FilterEvaluator.PASS.equals(f.status())).count(),
                row.experience(), row.totalYears(), row.qualification(), row.jobLocation(), row.languages(),
                row.isMeditator(), row.stayInAshram());
        int jobLevel = jobResult != null ? JOB_DIRECT : sameDomainJob != null ? JOB_SAME_DOMAIN : JOB_NONE;
        int score = met.isEmpty() && sameDomainJob != null ? sameDomainJob.score() : met.stream().mapToInt(Result::bestScore).sum();
        double years = met.isEmpty() && sameDomainJob != null ? Objects.requireNonNullElse(sameDomainJob.years(), 0.0)
                : met.stream().map(Result::years).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        return new Ranked(match, (int) perSkill.stream().filter(Objects::nonNull).count(), jobLevel, quality, niceMet.size(), score, years);
    }

    /** "Python experience: 9 years (wanted 8+)" - for a skill or job asked with years ("python developer with 8+ years"). */
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

    private static String matchType(int quality) {
        return switch (quality) {
            case NeedMatcher.EXACT -> "profile";
            case NeedMatcher.RELATED -> "related";
            default -> "text";
        };
    }

    /** "No candidates match 'astronaut' in Coimbatore." */
    private static String noMatchMessage(List<Need> skills, ParsedQuery q, boolean filterOnly) {
        if (filterOnly) {
            return "No candidates match these filters.";
        }
        List<String> asked = new ArrayList<>();
        if (q.job() != null) {
            asked.add(q.job().term());
        } else {
            Stream.of(q.role(), q.domain()).filter(Objects::nonNull).forEach(asked::add);
        }
        skills.forEach(s -> asked.add(s.term()));
        String place = q.filters() == null || q.filters().location() == null ? null
                : Stream.of(q.filters().location().city(), q.filters().location().state(), q.filters().location().country())
                .filter(p -> p != null && !p.isBlank()).findFirst().orElse(null);
        return "No candidates match '" + String.join("' and '", asked) + "'" + (place == null ? "" : " in " + place) + ".";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
