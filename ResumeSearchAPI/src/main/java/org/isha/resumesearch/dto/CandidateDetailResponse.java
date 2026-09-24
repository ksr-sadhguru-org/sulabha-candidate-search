package org.isha.resumesearch.dto;

public record CandidateDetailResponse(
        String uniquefileId,
        String resumeName,
        String resumeText,
        ApplicantDetails applicantDetails
) {
}
