package org.isha.candidatesearch.llm;

import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.dto.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Keyword-based stand-in for the LLM (candidatesearch.mock-llm=true), so the pipeline runs without an API key. */
@Service
@ConditionalOnProperty(prefix = "candidatesearch", name = "mock-llm", havingValue = "true")
public class MockLlmClient implements LlmClient {

    private static final List<String> KEYWORDS = List.of(
            "java", "python", "spring boot", "django", "plumber", "electrician", "carpenter", "teacher", "accountant", "tally");

    @Override
    public Profile buildProfile(String resumeText, String verifiedSkills, List<String> fields) {
        String lower = resumeText.toLowerCase(Locale.ROOT);
        List<Profile.Entry> entries = KEYWORDS.stream()
                .filter(lower::contains)
                .map(k -> new Profile.Entry(k, "skill", null, null, 3.0, null, List.of(k)))
                .toList();
        return new Profile(3.0, entries);
    }

    @Override
    public ParsedQuery parseQuery(String query, List<String> fields) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<ParsedQuery.Need> must = KEYWORDS.stream()
                .filter(lower::contains)
                .map(k -> new ParsedQuery.Need(k, List.of(), false, null))
                .toList();
        ParsedQuery.Filters filters = new ParsedQuery.Filters(null, List.of(), null, null, null, null, null, null,
                lower.contains("ashram") ? "Yes" : null, null, lower.contains("meditator") ? "Yes" : null, null, null);
        return new ParsedQuery(true, null, must, List.of(), filters);
    }

    @Override
    public ApplicantDetails suggestApplicantDetails(String resumeText) {
        return new ApplicantDetails(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
