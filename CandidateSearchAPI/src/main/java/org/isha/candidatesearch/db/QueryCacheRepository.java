package org.isha.candidatesearch.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Parsed form of each query, keyed by the normalized query text. */
@Repository
public class QueryCacheRepository {

    private final JdbcClient jdbc;

    public QueryCacheRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String key) {
        return jdbc.sql("SELECT parsed_json FROM query_cache WHERE query_key = :key")
                .param("key", key).query(String.class).optional();
    }

    /** Forgets every saved query reading (e.g. after a query prompt change); candidates are untouched. */
    public int clear() {
        return jdbc.sql("DELETE FROM query_cache").update();
    }

    public void save(String key, String parsedJson) {
        jdbc.sql("INSERT INTO query_cache (query_key, parsed_json) VALUES (:key, :json) ON CONFLICT (query_key) DO NOTHING")
                .param("key", key).param("json", parsedJson).update();
    }
}
