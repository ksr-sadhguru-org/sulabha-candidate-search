package org.isha.candidatesearch.dto;

import java.util.List;

/** message: shown when the query was unclear or matched no one; null otherwise. */
public record SearchResponse(String message, ParsedQuery parsed, List<CandidateMatch> results) {
}
