package org.isha.resumesearch.service;

import org.isha.resumesearch.dto.ApplicantDetails;

import java.util.Map;

/** Normalizes free-form applicant field values to standardized DB values - ported from normalize_applicant_details in db_operations.py. */
public final class ApplicantNormalization {

    private ApplicantNormalization() {
    }

    private static final Map<String, String> GENDER = Map.of("Male", "male", "Female", "female");
    private static final Map<String, String> MARITAL_STATUS = Map.of("Single", "single", "Married", "married", "Divorced", "divorced");
    private static final Map<String, String> EXPERIENCE = Map.of(
            "0 ~ 3years", "0-3", "4 ~ 6years", "4-6", "7 ~ 10years", "7-10", "10+years", "10+"
    );
    private static final Map<String, String> NOTICE_PERIOD = Map.ofEntries(
            Map.entry("Immediate", "immediate"), Map.entry("Less than a Week", "less_than_a_week"),
            Map.entry("Less than 15 Days", "less_than_15_days"), Map.entry("1 Month", "1month"),
            Map.entry("2 Months", "2months"), Map.entry("3 Months", "3months"), Map.entry("6 Months", "6months"),
            Map.entry("More than 6 Months", "more_than_6_months")
    );
    private static final Map<String, String> STAY_IN_ASHRAM = Map.of(
            "Yes", "yes", "No", "no", "No, I would like to work from above stated preferred location", "no"
    );
    private static final Map<String, String> ANY_KIND_JOB = Map.of(
            "Yes", "yes",
            "No, only relevant to my Experience", "no-exp",
            "No, only relevant to my Qualification", "no-qual"
    );
    private static final Map<String, String> DURATION_WITH_ISHA = Map.of(
            "Minimum 1 Year", "minimum-1", "1 ~ 2 Years", "1~2", "2 ~ 3 Years", "2~3",
            "3 ~ 4 Years", "3~4", "5+ Years", "5+"
    );
    private static final Map<String, String> DONE_ISHA_PROGRAM = Map.of("Yes", "yes", "No", "no");
    private static final Map<String, String> IS_MEDITATOR = Map.of("yes", "YES", "no", "NO", "ieo", "IEO", "YES", "YES", "NO", "NO", "IEO", "IEO");

    public static ApplicantDetails normalize(ApplicantDetails d) {
        return new ApplicantDetails(
                d.name(), d.emailFrom(), d.dob(),
                mapOrKeep(d.gender(), GENDER),
                mapOrKeep(d.maritalStatus(), MARITAL_STATUS),
                d.address(), d.qualification(),
                mapOrKeep(d.experience(), EXPERIENCE),
                d.skillCompetencies(), d.otherInterests(), d.reasonForChange(),
                mapOrKeep(d.noticePeriod(), NOTICE_PERIOD),
                d.salaryExpected(),
                mapOrKeep(d.stayInAshram(), STAY_IN_ASHRAM),
                mapOrKeep(d.anyKindJob(), ANY_KIND_JOB),
                mapOrKeep(d.durationWithIsha(), DURATION_WITH_ISHA),
                mapOrKeep(d.doneIshaProgram(), DONE_ISHA_PROGRAM),
                d.linkedinProfile(), d.jobId(), d.nationality(), d.jobLocation(), d.languages(), d.applicantPrograms(),
                mapOrKeep(d.isMeditator(), IS_MEDITATOR)
        );
    }

    private static String mapOrKeep(String value, Map<String, String> map) {
        return value == null ? null : map.getOrDefault(value, value);
    }
}
