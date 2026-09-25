package org.isha.candidatesearch.dto;

import java.util.List;

/** The LLM's reading of an HR query. must: what a candidate has to be or know; nice: ranking-only extras
 *  (seniority, education); filters: form-field checks shown per candidate. */
public record ParsedQuery(Boolean understood, List<Need> must, List<Need> nice, Filters filters) {

    /** minYears: years of experience tied to this need ("python developer with 8+ years"), else null. */
    public record Need(String term, List<String> alternatives, Double minYears) {
    }

    public record Location(String city, String state, String country) {
    }

    public record Filters(
            Location location, List<String> languages, Double minTotalYears, String nationality, String gender,
            String maritalStatus, String noticePeriod, Double maxSalary, String stayInAshram, String doneIshaProgram,
            String isMeditator, String anyKindJob, String durationWithIsha) {
    }
}
