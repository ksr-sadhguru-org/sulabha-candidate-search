package org.isha.candidatesearch.dto;

import java.util.List;

/** The LLM's search profile of one resume: one entry per area of expertise, plus total years of work. */
public record Profile(Double totalYears, List<Entry> entries) {

    /** kind: profession | skill | education | language. terms: every phrase a search may use that this entry proves.
     *  score: education only (by level) - other entries are scored in code from years and lastUsedYear. */
    public record Entry(String name, String kind, Integer score, Double years, Integer lastUsedYear, List<String> terms) {
    }
}
