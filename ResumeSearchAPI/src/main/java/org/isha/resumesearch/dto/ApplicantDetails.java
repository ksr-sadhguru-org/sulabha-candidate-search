package org.isha.resumesearch.dto;

public record ApplicantDetails(
        String name,
        String emailFrom,
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
