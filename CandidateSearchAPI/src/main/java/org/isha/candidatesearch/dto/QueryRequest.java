package org.isha.candidatesearch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** offset / limit page through the ranked results (defaults: first 20). */
public record QueryRequest(@NotBlank String query, @Min(0) Integer offset, @Min(1) @Max(100) Integer limit) {

    public static final int DEFAULT_LIMIT = 20;

    public int offsetOrDefault() {
        return offset == null ? 0 : offset;
    }

    public int limitOrDefault() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }
}
