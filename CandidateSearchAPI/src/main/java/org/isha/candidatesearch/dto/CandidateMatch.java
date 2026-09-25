package org.isha.candidatesearch.dto;

import java.util.List;

/**
 * One search result and why it is there.
 * matchType: "profile" (every must-have found in the search profile), "related" (some only via an equivalent
 * term), "text" (some only in the resume text), "filters" (filter-only query).
 */
public record CandidateMatch(
        String candidateId, String name, String matchType,
        List<MatchedEntry> matched, List<String> textMatches, List<String> niceMatches,
        List<FilterCheck> filters, int filtersPassed,
        String experience, Double totalYears, String qualification, String jobLocation, String languages,
        String isMeditator, String stayInAshram) {

    /** viaTerm: the query term or equivalent that found this entry; related: found via an equivalent. */
    public record MatchedEntry(String name, int score, Double years, String viaTerm, boolean related) {
    }

    /** status: "pass" | "fail" | "not_on_file". */
    public record FilterCheck(String field, String label, String status, String requested, String candidateValue) {
    }
}
