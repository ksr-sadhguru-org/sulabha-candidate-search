package org.isha.resumesearch.dto;

import java.util.List;

/** A skill/role asked for in a query. relatedWords: single, distinctive words a resume might use instead
 *  (e.g. "instructor" for "Teacher"), chosen by the LLM - searched in resume text as related keywords. */
public record QueryParseEntity(String canonical, List<String> synonyms, List<String> relatedWords) {
}
