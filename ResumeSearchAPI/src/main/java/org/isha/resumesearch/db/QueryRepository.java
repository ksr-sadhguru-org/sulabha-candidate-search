package org.isha.resumesearch.db;

import org.isha.resumesearch.dto.ApplicationFilterResponse;
import org.isha.resumesearch.dto.QueryPersonaMatch;
import org.isha.resumesearch.dto.QueryResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The skill-matching pivot query, the application-data filter query, and their left join. */
@Repository
public class QueryRepository {

    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    // Fields where the LLM commonly produces multi-value or loosely worded text (e.g. "Coimbatore or
    // Chennai", "Bachelor's or higher") rather than a single exact value, so exact-match would rarely hit.
    // (durationWithIsha is intentionally excluded here - kept as exact match, matching the Python fix.)
    private static final Set<String> FUZZY_MATCH_FIELDS = Set.of(
            "languages", "jobLocation", "qualification", "nationality", "noticePeriod",
            "maritalStatus", "gender", "stayInAshram", "doneIshaProgram", "isMeditator", "anyKindJob"
    );

    private final JdbcClient jdbcClient;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public QueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Broad fallback: candidates whose raw resume text literally contains ANY of these words, independent
     *  of the curated skill/role taxonomy - catches cases the LLM's skill-extraction judgment call misses.
     *  Returns which specific keyword(s) matched each candidate, and whether ANY of those hits landed on a
     *  whole word - a candidate whose only hits are substrings inside other words (e.g. "sing" inside
     *  "Perusing") is flagged as a partial match rather than a genuine keyword match. */
    public Map<String, KeywordMatch> keywordMatch(List<String> keywords) {
        Map<String, List<String>> matchedKeywordsById = new LinkedHashMap<>();
        Map<String, Boolean> wholeWordHitById = new LinkedHashMap<>();
        for (String keyword : keywords) {
            // Postgres word-boundary regex (\y = boundary on both sides); keywords are pre-filtered to
            // [a-z0-9]+ by extractKeywords, so no regex-escaping is needed here.
            String wholeWordPattern = "\\y" + keyword + "\\y";
            List<Object[]> rows = jdbcClient.sql("""
                            SELECT uniquefile_id, (resume_text ~* :boundary) AS is_whole_word
                            FROM candidates_table WHERE LOWER(resume_text) LIKE LOWER(:kw)
                            """)
                    .param("kw", "%" + keyword + "%")
                    .param("boundary", wholeWordPattern)
                    .query((rs, rowNum) -> new Object[]{rs.getString("uniquefile_id"), rs.getBoolean("is_whole_word")})
                    .list();
            for (Object[] row : rows) {
                String id = (String) row[0];
                boolean isWholeWord = (Boolean) row[1];
                matchedKeywordsById.computeIfAbsent(id, k -> new ArrayList<>()).add(keyword);
                if (isWholeWord) {
                    wholeWordHitById.put(id, true);
                } else {
                    wholeWordHitById.putIfAbsent(id, false);
                }
            }
        }
        Map<String, KeywordMatch> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : matchedKeywordsById.entrySet()) {
            boolean partial = !wholeWordHitById.getOrDefault(e.getKey(), false);
            result.put(e.getKey(), new KeywordMatch(e.getValue(), partial));
        }
        return result;
    }

    /** @param partial true when none of {@code keywords} landed on a whole word - every hit was a substring inside a larger word. */
    public record KeywordMatch(List<String> keywords, boolean partial) {
    }

    /** Pivots candidate_search scores per requested attribute. A candidate must have at least one canonical
     *  from EVERY requiredGroups entry (one group per requested skill) to qualify; optionalAttributes (roles/titles) are scored/shown when
     *  present but don't gate inclusion - job-title wording varies too much across resumes to treat a
     *  role term as a hard requirement the way a specific technical skill is. */
    public QueryResult getQueryResult(List<List<String>> requiredGroups, List<String> optionalAttributes) {
        List<String> attributeList = Stream.concat(requiredGroups.stream().flatMap(List::stream), optionalAttributes.stream())
                .distinct().toList();
        if (attributeList.isEmpty()) {
            return new QueryResult(List.of());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("attributeList", attributeList);

        // Positional aliases (s0/d0, s1/d1, ...) because a canonical can contain characters that aren't valid in
        // an identifier ("c++", "child-centered teaching"). d = the entry the matched row belongs to (display_name).
        StringBuilder sql = new StringBuilder("SELECT uniquefile_id");
        for (int i = 0; i < attributeList.size(); i++) {
            sql.append(", MAX(CASE WHEN canonical = :attr").append(i).append(" THEN score END) AS s").append(i)
                    .append(", MAX(CASE WHEN canonical = :attr").append(i).append(" THEN COALESCE(display_name, canonical) END) AS d").append(i);
            params.put("attr" + i, attributeList.get(i));
        }
        sql.append(" FROM candidate_search WHERE canonical IN (:attributeList) GROUP BY uniquefile_id");

        // Each required skill is a group of canonicals it can mean ("teacher" -> music/sanskrit/... teacher):
        // a candidate needs at least one from every group, and ranks by their best score in each group.
        List<String> groupBest = new ArrayList<>();
        List<String> groupYears = new ArrayList<>();
        for (int g = 0; g < requiredGroups.size(); g++) {
            params.put("group" + g, requiredGroups.get(g));
            groupBest.add("MAX(CASE WHEN canonical IN (:group" + g + ") THEN score END)");
            groupYears.add("COALESCE(MAX(CASE WHEN canonical IN (:group" + g + ") THEN years END), 0)");
        }
        if (!groupBest.isEmpty()) {
            sql.append(" HAVING ").append(groupBest.stream().map(b -> b + " IS NOT NULL").collect(Collectors.joining(" AND ")));
        }
        List<String> orderBy = new ArrayList<>();
        if (!groupBest.isEmpty()) {
            orderBy.add("(" + String.join(" + ", groupBest) + ") DESC");
        }
        if (!optionalAttributes.isEmpty()) {
            params.put("optional", optionalAttributes);
            orderBy.add("MAX(CASE WHEN canonical IN (:optional) THEN score END) DESC NULLS LAST");
            groupYears.add("COALESCE(MAX(CASE WHEN canonical IN (:optional) THEN years END), 0)");
        }
        // Equal scores are common (the LLM rounds coarsely), so fall back to years of experience relevant to
        // the requested items - more precise than the score, and per item, so unrelated career years never count.
        // MAX per group, not SUM: an entry's included rows all carry its years, so SUM would count them repeatedly.
        orderBy.add("(" + String.join(" + ", groupYears) + ") DESC");
        sql.append(" ORDER BY ").append(String.join(", ", orderBy));

        List<QueryPersonaMatch> matches = jdbcClient.sql(sql.toString()).params(params).query((rs, rowNum) -> {
            // One chip entry per entry the matched rows belong to - "java", "java developer" and "senior java
            // developer" matched for the same candidate all show as "senior java developer" once.
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (int i = 0; i < attributeList.size(); i++) {
                Object raw = rs.getObject("s" + i);
                if (raw != null) {
                    scores.merge(rs.getString("d" + i), (int) Math.round(((Number) raw).doubleValue()), Math::max);
                }
            }
            return QueryPersonaMatch.skillOnly(rs.getString("uniquefile_id"), scores);
        }).list();

        return new QueryResult(matches);
    }

    /** Per-field pass/fail against the extracted application filters. Only fields the query actually
     *  mentioned are included; each maps to the set of candidate IDs whose application data satisfies
     *  that one field independently of the others - lets the caller show a candidate exactly which
     *  requested filters they do and don't satisfy, rather than one combined yes/no. */
    public Map<String, Set<String>> queryApplicationTableByField(ApplicationFilterResponse filters) {
        Map<String, Set<String>> matchesByField = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : asFieldMap(filters).entrySet()) {
            String field = e.getKey();
            String value = e.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            List<String> whereClauses = new ArrayList<>();
            Map<String, Object> params = new LinkedHashMap<>();
            addFieldClause(field, value, whereClauses, params);
            if (whereClauses.isEmpty()) {
                continue; // e.g. experience/salary text had no usable number in it
            }
            String sql = "SELECT DISTINCT uniquefile_id FROM candidate_application_data WHERE " + String.join(" AND ", whereClauses);
            Set<String> ids = new HashSet<>(jdbcClient.sql(sql).params(params).query((rs, rowNum) -> rs.getString("uniquefile_id")).list());
            matchesByField.put(field, ids);
        }
        return matchesByField;
    }

    private void addFieldClause(String field, String value, List<String> whereClauses, Map<String, Object> params) {
        switch (field) {
            case "experience" -> matchInt(value).ifPresent(minYears -> {
                whereClauses.add("CAST(REGEXP_REPLACE(experience, '[^0-9]', '', 'g') AS INTEGER) >= :minExperience");
                params.put("minExperience", minYears);
            });
            case "salaryExpected" -> matchInt(value).ifPresent(maxSalary -> {
                whereClauses.add("salary_expected <= :maxSalary");
                params.put("maxSalary", (double) maxSalary);
            });
            default -> {
                if (FUZZY_MATCH_FIELDS.contains(field)) {
                    addFuzzyClause(field, value, whereClauses, params);
                } else {
                    whereClauses.add("LOWER(" + toColumn(field) + ") = LOWER(:" + field + ")");
                    params.put(field, value);
                }
            }
        }
    }

    /** Left-joins skill matches with application data; ALL skill matches are kept, each annotated with
     *  exactly which requested filters it does and doesn't satisfy (empty when the query had no filters). */
    public QueryResult leftJoinWithApplicationData(QueryResult skillResults, ApplicationFilterResponse filters) {
        List<String> allIds = skillResults.result().stream().map(QueryPersonaMatch::uniquefileId).toList();
        Map<String, QueryPersonaMatch.ApplicationDataRow> dataById = allIds.isEmpty() ? Map.of() : fetchApplicationData(allIds);

        Map<String, Set<String>> matchesByField = queryApplicationTableByField(filters);
        Map<String, String> requestedValues = asFieldMap(filters);
        // No filters requested at all: fall back to "has any application data on file", matching the old
        // all-fields-combined query's behavior for this case rather than treating every candidate as a match.
        Set<String> anyApplicationDataId = matchesByField.isEmpty() ? allApplicationDataIds() : Set.of();

        List<QueryPersonaMatch> enriched = skillResults.result().stream().map(m -> {
            String id = m.uniquefileId();
            List<QueryPersonaMatch.FilterFieldStatus> statuses = filterStatuses(id, matchesByField, requestedValues, dataById.get(id));
            boolean matchesAll = matchesByField.isEmpty()
                    ? anyApplicationDataId.contains(id)
                    : statuses.stream().allMatch(QueryPersonaMatch.FilterFieldStatus::matched);
            return m.withApplicationData(matchesAll, statuses, dataById.get(id));
        }).toList();
        return new QueryResult(enriched);
    }

    private List<QueryPersonaMatch.FilterFieldStatus> filterStatuses(
            String uniquefileId, Map<String, Set<String>> matchesByField, Map<String, String> requestedValues,
            QueryPersonaMatch.ApplicationDataRow row) {
        Map<String, Object> candidateValues = row == null ? Map.of() : asFieldMap(row);
        List<QueryPersonaMatch.FilterFieldStatus> statuses = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : matchesByField.entrySet()) {
            String field = entry.getKey();
            boolean matched = entry.getValue().contains(uniquefileId);
            Object candidateValue = candidateValues.get(field);
            statuses.add(new QueryPersonaMatch.FilterFieldStatus(
                    field, matched, requestedValues.get(field), candidateValue == null ? null : candidateValue.toString()));
        }
        return statuses;
    }

    private Set<String> allApplicationDataIds() {
        return new HashSet<>(jdbcClient.sql("SELECT DISTINCT uniquefile_id FROM candidate_application_data")
                .query((rs, rowNum) -> rs.getString("uniquefile_id")).list());
    }

    private Map<String, QueryPersonaMatch.ApplicationDataRow> fetchApplicationData(List<String> ids) {
        return jdbcClient.sql("""
                        SELECT uniquefile_id, name, experience, qualification, nationality, job_location, languages,
                               gender, marital_status, notice_period, salary_expected, stay_in_ashram,
                               duration_with_isha, done_isha_program, is_meditator, any_kind_job
                        FROM candidate_application_data
                        WHERE uniquefile_id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> Map.entry(rs.getString("uniquefile_id"), new QueryPersonaMatch.ApplicationDataRow(
                        rs.getString("name"), rs.getString("experience"), rs.getString("qualification"), rs.getString("nationality"),
                        rs.getString("job_location"), rs.getString("languages"), rs.getString("gender"),
                        rs.getString("marital_status"), rs.getString("notice_period"),
                        salaryAsDouble(rs), rs.getString("stay_in_ashram"),
                        rs.getString("duration_with_isha"), rs.getString("done_isha_program"),
                        rs.getString("is_meditator"), rs.getString("any_kind_job"))))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Double salaryAsDouble(ResultSet rs) throws SQLException {
        BigDecimal value = rs.getBigDecimal("salary_expected");
        return value == null ? null : value.doubleValue();
    }

    private void addFuzzyClause(String field, String value, List<String> whereClauses, Map<String, Object> params) {
        String normalized = value.toLowerCase(Locale.ROOT).strip();
        if (normalized.equals("any") || normalized.equals("any preference") || normalized.equals("no preference")) {
            return; // no real preference stated - don't filter on it
        }
        String column = toColumn(field);
        List<String> conditions = new ArrayList<>();
        int idx = 0;
        for (String part : value.split(",|\\bor\\b")) {
            String v = part.strip();
            if (v.isEmpty()) {
                continue;
            }
            String paramName = field + "_" + (idx++);
            conditions.add("LOWER(" + column + ") LIKE LOWER(:" + paramName + ")");
            params.put(paramName, "%" + v + "%");
        }
        if (!conditions.isEmpty()) {
            whereClauses.add("(" + String.join(" OR ", conditions) + ")");
        }
    }

    private Optional<Integer> matchInt(String value) {
        Matcher m = DIGITS.matcher(value);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private String toColumn(String camelCaseField) {
        return camelCaseField.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** Field name (camelCase, matching the record's own components) -> value, for the WHERE-builder loop above. */
    @SuppressWarnings("unchecked")
    private Map<String, String> asFieldMap(ApplicationFilterResponse f) {
        return jsonMapper.convertValue(f, Map.class);
    }

    /** Same idea for a stored candidate's own application data, so its field values can be looked up by
     *  the same camelCase keys asFieldMap(ApplicationFilterResponse) uses. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asFieldMap(QueryPersonaMatch.ApplicationDataRow row) {
        return jsonMapper.convertValue(row, Map.class);
    }
}
