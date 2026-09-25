package org.isha.candidatesearch.llm;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** JSON in the LLM's own shape (camelCase keys, as the prompts ask for) - also used for the query cache. */
public final class LlmJson {

    private LlmJson() {
    }

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
}
