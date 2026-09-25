package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.QueryCacheRepository;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.llm.LlmClient;
import org.isha.candidatesearch.llm.LlmJson;
import org.isha.candidatesearch.llm.LlmUnavailableException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Parses a query once with the LLM and caches the result, so the same query always searches the same way. */
@Component
class QueryParser {

    private final LlmClient llm;
    private final QueryCacheRepository cache;

    QueryParser(LlmClient llm, QueryCacheRepository cache) {
        this.llm = llm;
        this.cache = cache;
    }

    ParsedQuery parse(String query) {
        String key = query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return cache.find(key).map(QueryParser::read).orElseGet(() -> {
            ParsedQuery parsed = Optional.ofNullable(llm.parseQuery(query)).map(QueryParser::complete)
                    .orElseThrow(() -> new LlmUnavailableException(
                            "Search failed: the AI service did not answer - check the backend's OPENAI_* settings and logs"));
            cache.save(key, LlmJson.MAPPER.writeValueAsString(parsed));
            return parsed;
        });
    }

    private static ParsedQuery read(String json) {
        return complete(LlmJson.MAPPER.readValue(json, ParsedQuery.class));
    }

    /** No nulls downstream: missing lists become empty, a missing "understood" counts as understood. */
    private static ParsedQuery complete(ParsedQuery q) {
        return new ParsedQuery(q.understood() == null || q.understood(),
                q.must() == null ? List.of() : q.must().stream().filter(n -> n.term() != null && !n.term().isBlank()).toList(),
                q.nice() == null ? List.of() : q.nice().stream().filter(n -> n.term() != null && !n.term().isBlank()).toList(),
                q.filters());
    }
}
