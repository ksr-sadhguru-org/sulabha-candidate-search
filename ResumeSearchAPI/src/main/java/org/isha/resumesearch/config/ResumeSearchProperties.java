package org.isha.resumesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "resumesearch")
public record ResumeSearchProperties(Openai openai, boolean mockLlm) {

    public record Openai(String apiKey, String baseUrl, String model, String queryModel) {
    }
}
