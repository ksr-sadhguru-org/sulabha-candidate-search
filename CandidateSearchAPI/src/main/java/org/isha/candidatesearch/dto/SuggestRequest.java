package org.isha.candidatesearch.dto;

import jakarta.validation.constraints.NotBlank;

public record SuggestRequest(@NotBlank String resumeText) {
}
