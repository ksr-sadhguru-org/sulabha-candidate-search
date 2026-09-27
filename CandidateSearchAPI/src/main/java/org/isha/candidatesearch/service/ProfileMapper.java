package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.ExpertiseRepository.NewExpertise;
import org.isha.candidatesearch.dto.Profile;
import org.isha.candidatesearch.search.Domains;
import org.isha.candidatesearch.search.Roles;
import org.isha.candidatesearch.search.SkillCompetencies;
import org.isha.candidatesearch.search.Terms;

import java.time.Year;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Turns an LLM profile plus the form's skill competencies into the expertise rows stored for one candidate. */
final class ProfileMapper {

    /** Score curve by relevant years, interpolated between these points: {years, score}. */
    private static final double[][] CURVE = {{0, 55}, {1, 65}, {3, 75}, {6, 85}, {10, 93}, {20, 97}};
    private static final int STALE_AFTER_YEARS = 5, STALE_PENALTY = 8;

    private ProfileMapper() {
    }

    static List<NewExpertise> toExpertise(Profile profile, String skillCompetencies) {
        Stream<NewExpertise> fromResume = Objects.requireNonNullElse(profile.entries(), List.<Profile.Entry>of()).stream()
                .filter(e -> e.name() != null && !e.name().isBlank())
                .map(e -> new NewExpertise(e.name().strip(), e.kind(), jobRole(e), jobDomain(e), "resume", score(e), e.years(),
                        terms(Stream.concat(Stream.of(e.name()), Objects.requireNonNullElse(e.terms(), List.<String>of()).stream()))));
        // The reviewed form list: a skill without years gets the candidate's total years.
        Stream<NewExpertise> fromForm = SkillCompetencies.parse(skillCompetencies).stream()
                .map(s -> {
                    Double years = Objects.requireNonNullElse(s.years(), profile.totalYears());
                    return new NewExpertise(s.name(), "skill", null, null, "form", scoreForYears(years, null), years, terms(Stream.of(s.name())));
                });
        return Stream.concat(fromResume, fromForm).filter(e -> !e.terms().isEmpty()).toList();
    }

    /** Only job (profession) entries carry a role and domain; skills count wherever they appear. An unlisted role
     *  becomes "other". */
    static String jobRole(Profile.Entry e) {
        return isJob(e) ? Objects.requireNonNullElse(Roles.normalize(e.role()), Roles.OTHER) : null;
    }

    static String jobDomain(Profile.Entry e) {
        return isJob(e) ? Domains.normalize(e.domain()) : null;
    }

    private static boolean isJob(Profile.Entry e) {
        return "profession".equalsIgnoreCase(e.kind());
    }

    private static int score(Profile.Entry e) {
        return "education".equalsIgnoreCase(e.kind()) && e.score() != null
                ? Math.clamp(e.score(), 0, 100)
                : scoreForYears(e.years(), e.lastUsedYear());
    }

    /** Same years, same score, for every candidate - less if not used in the last few years. */
    static int scoreForYears(Double years, Integer lastUsedYear) {
        double y = years == null ? 0 : Math.max(0, years);
        int i = 1;
        while (i < CURVE.length - 1 && y > CURVE[i][0]) i++;
        double[] lo = CURVE[i - 1], hi = CURVE[i];
        double base = Math.min(hi[1], lo[1] + (y - lo[0]) * (hi[1] - lo[1]) / (hi[0] - lo[0]));
        boolean stale = lastUsedYear != null && Year.now().getValue() - lastUsedYear > STALE_AFTER_YEARS;
        return (int) Math.round(base) - (stale ? STALE_PENALTY : 0);
    }

    private static Set<String> terms(Stream<String> raw) {
        return raw.filter(Objects::nonNull).map(Terms::normalize).filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
