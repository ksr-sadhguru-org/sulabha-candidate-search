package org.isha.resumesearch.dto;

import java.util.List;

/** skills are AND-required for a skill/taxonomy match; roles (job titles/seniority/education) are
 *  optional - they're scored/shown when a candidate has them, but don't exclude a candidate who
 *  doesn't, since job-title wording varies too much across resumes to treat as a hard requirement. */
public record QueryParseResponse(List<QueryParseEntity> skills, List<QueryParseEntity> roles) {
}
