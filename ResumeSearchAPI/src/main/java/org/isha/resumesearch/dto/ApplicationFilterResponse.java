package org.isha.resumesearch.dto;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
    /** Every requested filter value (nulls dropped), regardless of which field it belongs to. */
    public List<String> asValues() {
        return Stream.of(experience, qualification, nationality, jobLocation, languages, gender, maritalStatus,
                        noticePeriod, salaryExpected, stayInAshram, durationWithIsha, doneIshaProgram, isMeditator, anyKindJob)
                .filter(Objects::nonNull)
                .toList();
    }

    public static ApplicationFilterResponse empty() {
        return new ApplicationFilterResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
