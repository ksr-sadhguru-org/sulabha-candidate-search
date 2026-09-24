package org.isha.resumesearch.dto;

import java.util.List;

/** A skill or role extracted from a resume: canonical name, synonyms, a 0-100 score, and the years of
 *  experience relevant to this specific item (null when unknown - e.g. manually declared skills). */
public record ScoredEntity(String canonical, List<String> synonyms, int score, Double years) {
}
