package org.isha.resumesearch.dto;

import java.util.List;

/** A skill or role extracted from a resume: canonical name, synonyms, and a 0-100 confidence score. */
public record ScoredEntity(String canonical, List<String> synonyms, int score) {
}
