package com.beejepa.config;

import com.beejepa.muse.Sam31Client;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

@Introspected
@ConfigurationProperties("meta.muse")
public class MetaMuseConfig {

    private String baseUrl = "https://api.meta.ai";
    private String apiKey = "";
    private Duration timeout = Duration.ofSeconds(30);

    @PostConstruct
    public void loadDotEnvFallbacks() {
        if (apiKey == null || apiKey.isBlank()) {
            String v = Sam31Client.getEnvOrDotEnv("META_MUSE_API_KEY");
            if (v != null && !v.isBlank()) {
                apiKey = v.trim();
            }
        }
        String baseUrlEnv = Sam31Client.getEnvOrDotEnv("META_MUSE_BASE_URL");
        if ((baseUrl == null || baseUrl.isBlank()) && baseUrlEnv != null && !baseUrlEnv.isBlank()) {
            baseUrl = baseUrlEnv.trim();
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
