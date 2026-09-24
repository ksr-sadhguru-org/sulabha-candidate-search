package org.isha.resumesearch.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.isha.resumesearch.config.ResumeSearchProperties;
import org.isha.resumesearch.dto.ApplicantDetails;
import org.isha.resumesearch.dto.ApplicationFilterResponse;
import org.isha.resumesearch.dto.QueryParseResponse;
import org.isha.resumesearch.dto.ResumeParseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calls an OpenAI-compatible chat.completions endpoint (public OpenAI or Azure OpenAI, depending on
 * resumesearch.openai.base-url) with response_format=json_object, then parses the model's JSON reply
 * into the target record. Using chat.completions + JSON mode (rather than the newer Responses API)
 * keeps this portable across both public OpenAI and Azure OpenAI resources.
 */
@Service
@ConditionalOnProperty(prefix = "resumesearch", name = "mock-llm", havingValue = "false", matchIfMissing = true)
public class AzureLlmExtractor implements LlmExtractor {

    private static final Logger log = LoggerFactory.getLogger(AzureLlmExtractor.class);

    private final RestClient openAiRestClient;
    private final ResumeSearchProperties properties;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public AzureLlmExtractor(RestClient openAiRestClient, ResumeSearchProperties properties) {
        this.openAiRestClient = openAiRestClient;
        this.properties = properties;
    }

    @Override
    public ResumeParseResponse extractFromResume(String resumeText) {
        return complete(properties.openai().model(), Prompts.RESUME_PARSE.formatted(resumeText), ResumeParseResponse.class);
    }

    @Override
    public QueryParseResponse extractFromQuery(String query) {
        return complete(properties.openai().queryModel(), Prompts.QUERY_PARSE.formatted(query), QueryParseResponse.class);
    }

    @Override
    public ApplicationFilterResponse extractApplicationFilters(String query) {
        return complete(properties.openai().queryModel(), Prompts.APPLICATION_FILTER.formatted(query), ApplicationFilterResponse.class);
    }

    @Override
    public ApplicantDetails suggestApplicantDetails(String resumeText) {
        return complete(properties.openai().model(), Prompts.APPLICANT_DETAILS_SUGGEST.formatted(resumeText), ApplicantDetails.class);
    }

    private <T> T complete(String model, String prompt, Class<T> targetType) {
        try {
            // temperature=0: this is structured data extraction, not creative generation - the same input
            // should consistently produce the same output (e.g. identical search queries shouldn't
            // sometimes match and sometimes not, purely from LLM sampling randomness).
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object"),
                    "temperature", 0
            );
            log.info("LLM request [{}]: {}", targetType.getSimpleName(), prompt);
            JsonNode response = openAiRestClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            String content = Objects.requireNonNull(response, "empty LLM response").at("/choices/0/message/content").asString();
            log.info("LLM response [{}]: {}", targetType.getSimpleName(), content);
            return jsonMapper.readValue(content, targetType);
        } catch (Exception e) {
            log.error("Error calling LLM for {}: {}", targetType.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }
}
