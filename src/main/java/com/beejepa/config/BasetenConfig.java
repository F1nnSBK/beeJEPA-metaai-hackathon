package com.beejepa.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

import java.time.Duration;

@Introspected
@ConfigurationProperties("baseten")
public class BasetenConfig {

    private String apiKey = "";
    private String apiKey1 = "";
    private String apiKey2 = "";
    private String baseUrl = "https://inference.baseten.co/v1";
    private String model = "meta-models/muse-spark-open-1026";
    private Duration timeout = Duration.ofSeconds(30);
    private boolean allowInferenceCalls = false;

    public String getApiKey() {
        if (apiKey1 != null && !apiKey1.isBlank()) {
            return apiKey1;
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return apiKey2 != null ? apiKey2 : "";
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKey1() {
        return apiKey1;
    }

    public void setApiKey1(String apiKey1) {
        this.apiKey1 = apiKey1;
    }

    public String getApiKey2() {
        return apiKey2;
    }

    public void setApiKey2(String apiKey2) {
        this.apiKey2 = apiKey2;
    }

    public String getFallbackApiKey() {
        if (apiKey2 != null && !apiKey2.isBlank()) {
            return apiKey2;
        }
        return apiKey != null ? apiKey : "";
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
        String key = getApiKey();
        return key != null && !key.isBlank() && !key.equals("default_mock_key");
    }
}
