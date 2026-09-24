package org.isha.resumesearch.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;
import java.util.Map;

public record QueryPersonaMatch(
        String uniquefileId,
        Map<String, Integer> scores,
        boolean matchedViaKeyword,
        List<String> matchedKeywords,
        boolean partialKeywordMatch,
        boolean matchesApplicationFilters,
        List<FilterFieldStatus> filterStatus,
        @JsonUnwrapped ApplicationDataRow applicationData
) {
    /** A skill-match row before it's been enriched with application data. */
    public static QueryPersonaMatch skillOnly(String uniquefileId, Map<String, Integer> scores) {
        return new QueryPersonaMatch(uniquefileId, scores, false, List.of(), false, true, List.of(), null);
    }

    /** A candidate found only via the raw-text keyword fallback, not the curated skill/role taxonomy.
     *  {@code partialKeywordMatch} is true when none of the matched keywords landed on a whole word - e.g.
     *  "sing" only ever appeared as a substring inside "Perusing", never as a standalone word. */
    public static QueryPersonaMatch keywordOnly(String uniquefileId, List<String> matchedKeywords, boolean partialKeywordMatch) {
        return new QueryPersonaMatch(uniquefileId, Map.of(), true, matchedKeywords, partialKeywordMatch, true, List.of(), null);
    }

    public QueryPersonaMatch withApplicationData(boolean matchesFilters, List<FilterFieldStatus> filterStatus, ApplicationDataRow row) {
        return new QueryPersonaMatch(uniquefileId, scores, matchedViaKeyword, matchedKeywords, partialKeywordMatch, matchesFilters, filterStatus, row);
    }

    /** One requested application filter's outcome for this candidate - only present for fields the query
     *  actually mentioned. candidateValue is null when the candidate has nothing on file for that field. */
    public record FilterFieldStatus(String field, boolean matched, String requestedValue, String candidateValue) {
    }

    public record ApplicationDataRow(
            String name, String experience, String qualification, String nationality, String jobLocation, String languages,
            String gender, String maritalStatus, String noticePeriod, Double salaryExpected, String stayInAshram,
            String durationWithIsha, String doneIshaProgram, String isMeditator, String anyKindJob
    ) {
    }
}
