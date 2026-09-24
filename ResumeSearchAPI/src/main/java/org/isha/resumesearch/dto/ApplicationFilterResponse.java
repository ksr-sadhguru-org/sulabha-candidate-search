package org.isha.resumesearch.dto;

/** Application-level filters extracted from a free-text HR query. All fields are nullable - null means "not mentioned". */
public record ApplicationFilterResponse(
        String experience,
        String qualification,
        String nationality,
        String jobLocation,
        String languages,
        String gender,
        String maritalStatus,
        String noticePeriod,
        String salaryExpected,
        String stayInAshram,
        String durationWithIsha,
        String doneIshaProgram,
        String isMeditator,
        String anyKindJob
) {
    public static ApplicationFilterResponse empty() {
        return new ApplicationFilterResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
