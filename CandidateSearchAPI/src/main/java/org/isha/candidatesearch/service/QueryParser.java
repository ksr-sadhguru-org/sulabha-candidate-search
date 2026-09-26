package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.FieldRepository;
import org.isha.candidatesearch.db.QueryCacheRepository;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.llm.LlmClient;
import org.isha.candidatesearch.llm.LlmJson;
import org.isha.candidatesearch.llm.LlmUnavailableException;
import org.isha.candidatesearch.search.Fields;
import org.isha.candidatesearch.search.Terms;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Parses a query once with the LLM and caches the result, so the same query always searches the same way. */
@Component
class QueryParser {

    private final LlmClient llm;
    private final QueryCacheRepository cache;
    private final FieldRepository fields;

    QueryParser(LlmClient llm, QueryCacheRepository cache, FieldRepository fields) {
        this.llm = llm;
        this.cache = cache;
        this.fields = fields;
    }

    ParsedQuery parse(String query) {
        String key = query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return cache.find(key).map(this::read).orElseGet(() -> {
            List<String> known = fields.names();
            ParsedQuery parsed = Optional.ofNullable(llm.parseQuery(query, known)).map(q -> complete(q, known))
                    .orElseThrow(() -> new LlmUnavailableException(
                            "Search failed: the AI service did not answer - check the backend's OPENAI_* settings and logs"));
            cache.save(key, LlmJson.MAPPER.writeValueAsString(parsed));
            return parsed;
        });
    }

    private ParsedQuery read(String json) {
        return complete(LlmJson.MAPPER.readValue(json, ParsedQuery.class), fields.names());
    }

    /** No nulls downstream: missing lists become empty, a missing "understood" counts as understood, and a field that
     *  isn't on the list is dropped - a query can't invent a field, since nobody would be in it. */
    /** Two guards on the AI's reading of one need:
     *  - a qualified job ("software developer", "music teacher") is never generic - only a lone word can be;
     *  - an alternative that is just the term's last word ("developer" for "software developer") is broader than
     *    what was asked, so it is dropped. */
    private static ParsedQuery.Need tidy(ParsedQuery.Need n) {
        String[] words = Terms.normalize(n.term()).split(" ");
        String head = words[words.length - 1];
        List<String> alternatives = n.alternatives() == null ? List.of()
                : n.alternatives().stream().filter(a -> words.length == 1 || !Terms.normalize(a).equals(head)).toList();
        return new ParsedQuery.Need(n.term(), alternatives, words.length == 1 && n.isGeneric(), n.minYears());
    }

    private static ParsedQuery complete(ParsedQuery q, List<String> known) {
        String field = Fields.normalize(q.field());
        return new ParsedQuery(q.understood() == null || q.understood(), known.contains(field) ? field : null,
                q.must() == null ? List.of() : q.must().stream().filter(n -> n.term() != null && !n.term().isBlank()).map(QueryParser::tidy).toList(),
                q.nice() == null ? List.of() : q.nice().stream().filter(n -> n.term() != null && !n.term().isBlank()).map(QueryParser::tidy).toList(),
                q.filters());
    }
}
