package org.isha.resumesearch.dto;

import java.util.List;

/** The LLM's reading of one resume: one entry per area of expertise (see ScoredEntity). */
public record ResumeParseResponse(List<ScoredEntity> items) {
}
