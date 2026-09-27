package org.isha.candidatesearch.dto;

import java.util.List;

/**
 * The LLM's reading of an HR query.
 * role / domain: the job asked for - its kind of work and its subject - each from the known lists, null when unsaid.
 * job: the job as worded, with alternatives, for matching profile terms (null when no job is asked).
 * must: specific skills, tools or languages a candidate must have. nice: ranking-only extras (seniority, education).
 */
public record ParsedQuery(Boolean understood, String role, String domain, Need job, List<Need> must, List<Need> nice,
                          Filters filters) {

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
