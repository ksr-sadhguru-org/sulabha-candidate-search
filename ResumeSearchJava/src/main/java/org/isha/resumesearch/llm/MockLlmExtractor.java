package org.isha.resumesearch.llm;

import org.isha.resumesearch.dto.ApplicantDetails;
import org.isha.resumesearch.dto.ApplicationFilterResponse;
import org.isha.resumesearch.dto.QueryParseEntity;
import org.isha.resumesearch.dto.QueryParseResponse;
import org.isha.resumesearch.dto.ResumeParseResponse;
import org.isha.resumesearch.dto.ScoredEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, keyword-based stand-in for the real LLM calls, enabled via resumesearch.mock-llm=true.
 * Lets the DB/matching pipeline (uploads, synonym normalization, SQL search) be exercised locally
 * without an API key or LLM cost.
 */
@Service
@ConditionalOnProperty(prefix = "resumesearch", name = "mock-llm", havingValue = "true")
public class MockLlmExtractor implements LlmExtractor {

    private static final List<String> SKILL_KEYWORDS = List.of(
            "React", "Node.js", "Python", "Django", "FastAPI", "AWS", "Docker", "PostgreSQL", "Redis", "Git", "JavaScript"
    );
    private static final List<String> LOCATIONS = List.of("coimbatore", "chennai", "bangalore", "remote");
    private static final List<String> LANGUAGES = List.of("tamil", "english", "hindi");
    private static final List<String> NATIONALITIES = List.of("indian", "american", "british");
    private static final Pattern EXPERIENCE = Pattern.compile("(\\d+)\\+?\\s*years?");
    // Only match when an explicit labelled field is present (e.g. "GENDER: MALE"), not just the bare word
    // anywhere in the text - mirrors the real prompt's "only when explicitly stated" instruction.
    private static final Pattern GENDER = Pattern.compile("gender\\s*[:\\-]\\s*(male|female)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARITAL_STATUS = Pattern.compile("marital\\s*status\\s*[:\\-]\\s*(single|married|divorced)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOB = Pattern.compile("date\\s*of\\s*birth\\s*[:\\-]\\s*([0-9./\\-]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public ResumeParseResponse extractFromResume(String resumeText) {
        String lower = resumeText.toLowerCase(Locale.ROOT);
        int eduScore = educationScore(lower);
        List<ScoredEntity> explicitSkills = findSkills(lower);
        List<ScoredEntity> impliedSkills = (lower.contains("led a team") || lower.contains("team of"))
                ? List.of(new ScoredEntity("Team Leadership", List.of(), eduScore))
                : List.of();
        return new ResumeParseResponse(explicitSkills, impliedSkills, List.of(role(lower, eduScore)));
    }

    @Override
    public QueryParseResponse extractFromQuery(String query) {
        List<QueryParseEntity> skills = findSkills(query.toLowerCase(Locale.ROOT)).stream()
                .map(s -> new QueryParseEntity(s.canonical(), List.of()))
                .toList();
        return new QueryParseResponse(skills, List.of());
    }

    @Override
    public ApplicationFilterResponse extractApplicationFilters(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        Matcher expMatch = EXPERIENCE.matcher(lower);
        String experience = expMatch.find() ? expMatch.group(1) + "+ years" : null;
        String jobLocation = LOCATIONS.stream().filter(lower::contains).findFirst()
                .map(loc -> capitalize(loc)).orElse(null);
        String languages = String.join(", ", LANGUAGES.stream().filter(lower::contains).map(this::capitalize).toList());
        return new ApplicationFilterResponse(
                experience, null, null, jobLocation, languages.isBlank() ? null : languages,
                null, null, null, null,
                lower.contains("ashram") ? "Yes" : null,
                null, null,
                lower.contains("meditator") ? "Yes" : null,
                null
        );
    }

    @Override
    public ApplicantDetails suggestApplicantDetails(String resumeText) {
        String lower = resumeText.toLowerCase(Locale.ROOT);
        Matcher expMatch = EXPERIENCE.matcher(lower);
        String experience = expMatch.find() ? experienceBucket(Integer.parseInt(expMatch.group(1))) : null;
        String nationality = NATIONALITIES.stream().filter(lower::contains).findFirst().map(this::capitalize).orElse(null);
        String jobLocation = LOCATIONS.stream().filter(lower::contains).findFirst().map(this::capitalize).orElse(null);
        String languages = String.join(", ", LANGUAGES.stream().filter(lower::contains).map(this::capitalize).toList());
        String qualification;
        if (lower.contains("master") || lower.contains("mca") || lower.contains("m.tech")) {
            qualification = "Master's";
        } else if (lower.contains("bachelor") || lower.contains("b.e") || lower.contains("b.tech")) {
            qualification = "Bachelor's";
        } else {
            qualification = null;
        }
        String dob = matchGroup(DOB, resumeText);
        String gender = matchGroup(GENDER, resumeText, this::capitalize);
        String maritalStatus = matchGroup(MARITAL_STATUS, resumeText, this::capitalize);

        return new ApplicantDetails(
                null, null, dob, gender, maritalStatus, null,
                qualification, experience, null, null, null, null, null,
                null, null, null, null,
                null, null, nationality, jobLocation, languages.isBlank() ? null : languages, null,
                null
        );
    }

    private String matchGroup(Pattern pattern, String text) {
        return matchGroup(pattern, text, s -> s);
    }

    private String matchGroup(Pattern pattern, String text, java.util.function.Function<String, String> transform) {
        Matcher m = pattern.matcher(text);
        return m.find() ? transform.apply(m.group(1)) : null;
    }

    private String experienceBucket(int years) {
        if (years <= 3) return "0 ~ 3years";
        if (years <= 6) return "4 ~ 6years";
        if (years <= 10) return "7 ~ 10years";
        return "10+years";
    }

    private List<ScoredEntity> findSkills(String lowerText) {
        return SKILL_KEYWORDS.stream()
                .filter(kw -> lowerText.contains(kw.toLowerCase(Locale.ROOT)))
                .map(kw -> new ScoredEntity(kw, List.of(), 80))
                .toList();
    }

    private int educationScore(String lower) {
        if (lower.contains("master") || lower.contains("mca") || lower.contains("m.tech")) return 90;
        if (lower.contains("bachelor") || lower.contains("b.e") || lower.contains("b.tech")) return 78;
        return 60;
    }

    private ScoredEntity role(String lower, int score) {
        String title;
        if (lower.contains("senior")) {
            title = (lower.contains("full stack") || (lower.contains("react") && lower.contains("python")))
                    ? "Senior Full Stack Developer" : "Senior Developer";
        } else if (lower.contains("junior") || lower.contains("backend developer")) {
            title = "Backend Developer";
        } else {
            title = "Software Developer";
        }
        return new ScoredEntity(title, List.of(), score);
    }

    private String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
