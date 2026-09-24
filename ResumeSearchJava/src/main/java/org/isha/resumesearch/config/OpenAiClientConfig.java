package org.isha.resumesearch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Builds the fluent RestClient used to call OpenAI-compatible chat completions (works against
 *  the public OpenAI API or an Azure OpenAI resource, depending on resumesearch.openai.base-url). */
@Configuration
public class OpenAiClientConfig {

    @Bean
    RestClient openAiRestClient(RestClient.Builder builder, ResumeSearchProperties properties) {
        String baseUrl = StringUtils.hasText(properties.openai().baseUrl())
                ? properties.openai().baseUrl()
                : "https://api.openai.com/v1/";
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.openai().apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }
}
