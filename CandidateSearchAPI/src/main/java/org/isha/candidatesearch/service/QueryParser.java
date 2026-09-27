package org.isha.candidatesearch.service;

import org.isha.candidatesearch.db.DomainRepository;
import org.isha.candidatesearch.db.QueryCacheRepository;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.llm.LlmClient;
import org.isha.candidatesearch.llm.LlmJson;
import org.isha.candidatesearch.llm.LlmUnavailableException;
import org.isha.candidatesearch.search.Domains;
import org.isha.candidatesearch.search.Roles;
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
    private final DomainRepository domains;

    QueryParser(LlmClient llm, QueryCacheRepository cache, DomainRepository domains) {
        this.llm = llm;
        this.cache = cache;
        this.domains = domains;
    }

    ParsedQuery parse(String query) {
        String key = query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return cache.find(key).map(this::read).orElseGet(() -> {
            List<String> known = domains.names();
            ParsedQuery parsed = Optional.ofNullable(llm.parseQuery(query, known)).map(q -> complete(q, known))
                    .orElseThrow(() -> new LlmUnavailableException(
                            "Search failed: the AI service did not answer - check the backend's OPENAI_* settings and logs"));
            cache.save(key, LlmJson.MAPPER.writeValueAsString(parsed));
            return parsed;
        });
    }

    private ParsedQuery read(String json) {
        return complete(LlmJson.MAPPER.readValue(json, ParsedQuery.class), domains.names());
    }

    /** An alternative that is just the term's last word ("developer" for "software developer") is broader than
     *  what was asked, so it is dropped. */
    private static ParsedQuery.Need tidy(ParsedQuery.Need n) {
        if (n == null || n.term() == null || n.term().isBlank()) {
            return null;
        }
        String[] words = Terms.normalize(n.term()).split(" ");
        String head = words[words.length - 1];
        List<String> alternatives = n.alternatives() == null ? List.of()
                : n.alternatives().stream().filter(a -> words.length == 1 || !Terms.normalize(a).equals(head)).toList();
        return new ParsedQuery.Need(n.term(), alternatives, n.minYears());
    }

    /** No nulls downstream: missing lists become empty, a missing "understood" counts as understood; a role not on the
     *  fixed list (or "other") and a domain not on the domain list are dropped - a query can't invent either. */
    private static ParsedQuery complete(ParsedQuery q, List<String> known) {
        String role = Roles.normalize(q.role());
        String domain = Domains.normalize(q.domain());
        return new ParsedQuery(q.understood() == null || q.understood(),
                Roles.OTHER.equals(role) ? null : role, known.contains(domain) ? domain : null, tidy(q.job()),
                tidyAll(q.must()), tidyAll(q.nice()), q.filters());
    }

    private static List<ParsedQuery.Need> tidyAll(List<ParsedQuery.Need> needs) {
        return needs == null ? List.of() : needs.stream().map(QueryParser::tidy).filter(java.util.Objects::nonNull).toList();
    }
}
