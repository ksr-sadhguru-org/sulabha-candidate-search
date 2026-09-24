package org.isha.resumesearch.dto;

import jakarta.validation.constraints.NotBlank;

public record SuggestApplicantDetailsRequest(@NotBlank String resumeText) {
}
