package org.isha.candidatesearch.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(@NotBlank String query) {
}
