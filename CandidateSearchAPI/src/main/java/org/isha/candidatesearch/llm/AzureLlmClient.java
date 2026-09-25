package org.isha.candidatesearch.llm;

import org.isha.candidatesearch.config.CandidateSearchProperties;
import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.dto.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Calls an OpenAI-compatible chat.completions endpoint (public OpenAI or Azure OpenAI) in JSON mode. */
@Service
@ConditionalOnProperty(prefix = "candidatesearch", name = "mock-llm", havingValue = "false", matchIfMissing = true)
public class AzureLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AzureLlmClient.class);

    private final RestClient openAiRestClient;
    private final CandidateSearchProperties properties;
    private final ObjectMapper jsonMapper = LlmJson.MAPPER;

    public AzureLlmClient(RestClient openAiRestClient, CandidateSearchProperties properties) {
        this.openAiRestClient = openAiRestClient;
        this.properties = properties;
    }

    @Override
    public Profile buildProfile(String resumeText, String verifiedSkills) {
        String skills = verifiedSkills == null || verifiedSkills.isBlank() ? "none" : verifiedSkills;
        return complete(properties.openai().model(), Prompts.PROFILE.formatted(skills, resumeText), Profile.class);
    }

    @Override
    public ParsedQuery parseQuery(String query) {
        return complete(properties.openai().queryModel(), Prompts.QUERY.formatted(query), ParsedQuery.class);
    }

    @Override
    public ApplicantDetails suggestApplicantDetails(String resumeText) {
        return complete(properties.openai().model(), Prompts.SUGGEST.formatted(resumeText), ApplicantDetails.class);
    }

    private <T> T complete(String model, String prompt, Class<T> targetType) {
        try {
            // temperature 0: extraction, not generation - the same input should give the same output.
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object"),
                    "temperature", 0
            );
            JsonNode response = openAiRestClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            String content = Objects.requireNonNull(response, "empty LLM response").at("/choices/0/message/content").asString();
            log.info("LLM {} -> {}", targetType.getSimpleName(), content);
            return jsonMapper.readValue(content, targetType);
        } catch (Exception e) {
            log.error("LLM call for {} failed: {}", targetType.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }
}
