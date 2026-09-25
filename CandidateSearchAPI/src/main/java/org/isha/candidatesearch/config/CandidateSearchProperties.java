package org.isha.candidatesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "candidatesearch")
public record CandidateSearchProperties(Openai openai, boolean mockLlm) {

    public record Openai(String apiKey, String baseUrl, String model, String queryModel) {
    }
}
