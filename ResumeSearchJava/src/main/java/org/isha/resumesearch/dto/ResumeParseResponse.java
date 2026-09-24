package org.isha.resumesearch.dto;

import java.util.List;

public record ResumeParseResponse(
        List<ScoredEntity> explicitSkills,
        List<ScoredEntity> impliedSkills,
        List<ScoredEntity> roles
) {
}
