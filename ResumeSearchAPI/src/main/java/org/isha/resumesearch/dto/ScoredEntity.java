package org.isha.resumesearch.dto;

import java.util.List;

/** One area of expertise extracted from a resume: type ("role" or "skill"), canonical name, synonyms, a single
 *  0-100 score, the years of experience relevant to it (null when unknown - e.g. manually declared skills), and
 *  what it includes - the skills, tools and earlier titles it's made up of, so the candidate can be found by any. */
public record ScoredEntity(String type, String canonical, List<String> synonyms, int score, Double years, List<String> includes) {
}
