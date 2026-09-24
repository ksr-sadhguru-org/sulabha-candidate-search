package org.isha.resumesearch.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(@NotBlank String query) {
}
