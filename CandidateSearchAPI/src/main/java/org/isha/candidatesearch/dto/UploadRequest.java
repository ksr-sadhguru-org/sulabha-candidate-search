package org.isha.candidatesearch.dto;

import jakarta.validation.constraints.NotBlank;

public record UploadRequest(@NotBlank String candidateId, @NotBlank String resumeName, @NotBlank String resumeText,
                            ApplicantDetails applicantDetails) {
}
