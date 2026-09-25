package org.isha.candidatesearch.dto;

public record ExtractedTextResponse(String resumeName, String extractedText, DuplicateMatch duplicateOf) {

    /** Null unless this resume's text exactly matches an already-stored candidate. */
    public record DuplicateMatch(String candidateId, String resumeName) {
    }
}
