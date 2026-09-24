package org.isha.resumesearch.dto;

import jakarta.validation.constraints.NotBlank;

public record ResumeUploadRequest(
        @NotBlank String uniquefileId,
        @NotBlank String resumeName,
        @NotBlank String resumeText,
        ApplicantDetails applicantDetails
) {
}
