package org.isha.candidatesearch.dto;

import java.util.List;

/** The LLM's reading of an HR query. field: the field the query's skills or job belong to (null when it names none);
 *  must: what a candidate has to be or know; nice: ranking-only extras; filters: form-field checks shown per candidate. */
public record ParsedQuery(Boolean understood, String field, List<Need> must, List<Need> nice, Filters filters) {

    /** generic: a job word that means different things in different fields ("developer", "engineer", "teacher") -
     *  it only counts through a job in the query's field. minYears: years tied to this need, else null. */
    public record Need(String term, List<String> alternatives, Boolean generic, Double minYears) {

        public boolean isGeneric() {
            return Boolean.TRUE.equals(generic);
        }
    }

    public record Location(String city, String state, String country) {
    }

    public record Filters(
            Location location, List<String> languages, Double minTotalYears, String nationality, String gender,
            String maritalStatus, String noticePeriod, Double maxSalary, String stayInAshram, String doneIshaProgram,
            String isMeditator, String anyKindJob, String durationWithIsha) {
    }
}
