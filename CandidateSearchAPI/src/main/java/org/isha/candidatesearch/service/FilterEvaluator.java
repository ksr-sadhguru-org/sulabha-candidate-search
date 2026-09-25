package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.CandidateRepository.Row;
import org.isha.candidatesearch.dto.CandidateMatch.FilterCheck;
import org.isha.candidatesearch.dto.ParsedQuery.Filters;
import org.isha.candidatesearch.dto.ParsedQuery.Location;
import org.isha.candidatesearch.search.Locations;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Checks a candidate's form fields against the query's filters: pass, fail, or not on file. */
final class FilterEvaluator {

    static final String PASS = "pass", FAIL = "fail", NOT_ON_FILE = "not_on_file";

    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    private FilterEvaluator() {
    }

    static boolean hasAny(Filters f) {
        return f != null && Stream.of(Locations.format(f.location()), f.languages() == null || f.languages().isEmpty() ? null : "x",
                        f.minTotalYears(), f.nationality(), f.gender(), f.maritalStatus(), f.noticePeriod(), f.maxSalary(),
                        yesNo(f.stayInAshram()), yesNo(f.doneIshaProgram()), yesNo(f.isMeditator()), yesNo(f.anyKindJob()),
                        f.durationWithIsha())
                .anyMatch(v -> v != null && !v.toString().isBlank());
    }

    static List<FilterCheck> evaluate(Filters f, Row r) {
        if (f == null) {
            return List.of();
        }
        return Stream.of(
                        location(f.location(), r),
                        languages(f.languages(), r.languages()),
                        totalYears(f.minTotalYears(), r),
                        check("nationality", "Nationality", f.nationality(), r.nationality(), v -> containsIgnoreCase(v, f.nationality())),
                        check("gender", "Gender", f.gender(), r.gender(), v -> v.equalsIgnoreCase(f.gender())),
                        check("maritalStatus", "Marital status", f.maritalStatus(), r.maritalStatus(), v -> v.equalsIgnoreCase(f.maritalStatus())),
                        check("noticePeriod", "Notice period", f.noticePeriod(), r.noticePeriod(),
                                v -> v.equalsIgnoreCase(ApplicantNormalization.noticePeriod(f.noticePeriod()))),
                        check("maxSalary", "Salary expected", f.maxSalary() == null ? null : String.valueOf(f.maxSalary()),
                                r.salaryExpected() == null ? null : String.valueOf(r.salaryExpected()), v -> r.salaryExpected() <= f.maxSalary()),
                        yesNoCheck("stayInAshram", "Stay in ashram", f.stayInAshram(), r.stayInAshram()),
                        yesNoCheck("doneIshaProgram", "Done Isha program", f.doneIshaProgram(), r.doneIshaProgram()),
                        yesNoCheck("isMeditator", "Meditator", f.isMeditator(), r.isMeditator()),
                        yesNoCheck("anyKindJob", "Any kind of job", f.anyKindJob(), r.anyKindJob()),
                        check("durationWithIsha", "Duration with Isha", f.durationWithIsha(), r.durationWithIsha(),
                                v -> v.equalsIgnoreCase(ApplicantNormalization.durationWithIsha(f.durationWithIsha()))))
                .filter(Objects::nonNull).toList();
    }

    /** Compares at the most specific level asked: city, else state, else country. */
    private static FilterCheck location(Location wanted, Row r) {
        if (wanted == null) {
            return null;
        }
        String[][] levels = {{wanted.city(), r.city()}, {wanted.state(), r.state()}, {wanted.country(), r.country()}};
        return Arrays.stream(levels).filter(l -> l[0] != null && !l[0].isBlank()).findFirst()
                .map(l -> check("jobLocation", "Location", Locations.format(wanted), r.jobLocation(),
                        v -> l[1] != null ? l[1].equalsIgnoreCase(l[0].strip()) : containsIgnoreCase(v, l[0].strip())))
                .orElse(null);
    }

    private static FilterCheck languages(List<String> wanted, String have) {
        if (wanted == null || wanted.isEmpty()) {
            return null;
        }
        List<String> spoken = have == null ? List.of() : Arrays.stream(have.split(",|/|;|\\band\\b"))
                .map(s -> s.strip().toLowerCase(Locale.ROOT)).toList();
        return check("languages", "Languages", String.join(", ", wanted), have,
                v -> wanted.stream().allMatch(w -> spoken.contains(w.strip().toLowerCase(Locale.ROOT))));
    }

    /** Uses the profile's total years; falls back to the form's experience range ("7-10") when that decides it. */
    private static FilterCheck totalYears(Double min, Row r) {
        if (min == null) {
            return null;
        }
        String requested = fmt(min) + "+ years";
        if (r.totalYears() != null) {
            return new FilterCheck("experience", "Experience", r.totalYears() >= min ? PASS : FAIL, requested, fmt(r.totalYears()) + " years");
        }
        List<Integer> range = r.experience() == null ? List.of()
                : NUMBERS.matcher(r.experience()).results().map(java.util.regex.MatchResult::group).map(Integer::parseInt).toList();
        String status = range.isEmpty() ? NOT_ON_FILE
                : range.getFirst() >= min ? PASS
                : range.size() > 1 && range.getLast() < min ? FAIL : NOT_ON_FILE;
        return new FilterCheck("experience", "Experience", status, requested, r.experience());
    }

    private static FilterCheck yesNoCheck(String field, String label, String wanted, String have) {
        String w = yesNo(wanted);
        return check(field, label, w, have, v -> v.toLowerCase(Locale.ROOT).startsWith(w.toLowerCase(Locale.ROOT)));
    }

    /** "Yes"/"No", or null for anything else ("any", blank). */
    private static String yesNo(String value) {
        return value != null && (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("no")) ? value : null;
    }

    private static FilterCheck check(String field, String label, String wanted, String have, Predicate<String> passes) {
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        String status = have == null || have.isBlank() ? NOT_ON_FILE : passes.test(have) ? PASS : FAIL;
        return new FilterCheck(field, label, status, wanted, have);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    static String fmt(double years) {
        return years == Math.floor(years) ? String.valueOf((long) years) : String.valueOf(years);
    }
}
