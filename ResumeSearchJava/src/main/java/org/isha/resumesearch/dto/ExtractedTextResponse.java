package org.isha.resumesearch.dto;

public record ExtractedTextResponse(String resumeName, String extractedText, DuplicateMatch duplicateOf) {

    /** Null unless this resume's content exactly matches an already-stored candidate. */
    public record DuplicateMatch(String uniquefileId, String resumeName) {
    }
}
