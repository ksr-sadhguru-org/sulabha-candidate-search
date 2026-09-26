package org.isha.candidatesearch.dto;

import java.util.List;

/** One page of ranked results. total: how many matched in all; message: shown when the query was unclear or
 *  matched no one, null otherwise. */
public record SearchResponse(String message, ParsedQuery parsed, List<CandidateMatch> results, int total) {
}
