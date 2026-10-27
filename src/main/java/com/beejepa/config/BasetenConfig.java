package com.beejepa.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

import java.time.Duration;

@Introspected
@ConfigurationProperties("baseten")
public class BasetenConfig {

    private String apiKey = "";
    private String baseUrl = "https://inference.baseten.co/v1";
    private String model = "meta-models/muse-spark-open";
    private Duration timeout = Duration.ofSeconds(30);
    private boolean allowInferenceCalls = false;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isAllowInferenceCalls() {
        return allowInferenceCalls;
    }

    public void setAllowInferenceCalls(boolean allowInferenceCalls) {
        this.allowInferenceCalls = allowInferenceCalls;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.equals("default_mock_key");
    }
}
