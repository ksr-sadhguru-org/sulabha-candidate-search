package org.isha.candidatesearch.dto;

/** The HR application form. Most fields can be AI-suggested from the resume; the Isha-specific ones
 *  (stayInAshram, isMeditator, ...) are only ever entered by hand. */
public record ApplicantDetails(
        String name,
        String emailFrom,
        String phone,
        String dob,
        String gender,
        String maritalStatus,
        String address,
        String qualification,
        String experience,
        String skillCompetencies,
        String otherInterests,
        String reasonForChange,
        String noticePeriod,
        Double salaryExpected,
        String stayInAshram,
        String anyKindJob,
        String durationWithIsha,
        String doneIshaProgram,
        String linkedinProfile,
        String jobId,
        String nationality,
        String jobLocation,
        String languages,
        String applicantPrograms,
        String isMeditator
) {
}
