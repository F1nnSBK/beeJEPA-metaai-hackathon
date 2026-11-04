package com.beejepa.config;

import com.beejepa.muse.Sam31Client;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.annotation.PostConstruct;

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
    private boolean allowInferenceCalls = true;
    private String systemPrompt = "";

    @PostConstruct
    public void loadDotEnvFallbacks() {
        if (apiKey == null || apiKey.isBlank()) {
            String v = Sam31Client.getEnvOrDotEnv("BASETEN_API_KEY");
            if (v != null && !v.isBlank()) {
                apiKey = v.trim();
            }
        }
        if (apiKey1 == null || apiKey1.isBlank()) {
            String v = Sam31Client.getEnvOrDotEnv("BASETEN_API_KEY1");
            if (v != null && !v.isBlank()) {
                apiKey1 = v.trim();
            }
        }
        if (apiKey2 == null || apiKey2.isBlank()) {
            String v = Sam31Client.getEnvOrDotEnv("BASETEN_API_KEY2");
            if (v != null && !v.isBlank()) {
                apiKey2 = v.trim();
            }
        }
        String baseUrlEnv = Sam31Client.getEnvOrDotEnv("BASETEN_BASE_URL");
        if ((baseUrl == null || baseUrl.isBlank()) && baseUrlEnv != null && !baseUrlEnv.isBlank()) {
            baseUrl = baseUrlEnv.trim();
        }
        String modelEnv = Sam31Client.getEnvOrDotEnv("BASETEN_MUSE_MODEL");
        if ((model == null || model.isBlank()) && modelEnv != null && !modelEnv.isBlank()) {
            model = modelEnv.trim();
        }
        String allowEnv = Sam31Client.getEnvOrDotEnv("BASETEN_ALLOW_INFERENCE");
        if (allowEnv != null && !allowEnv.isBlank()) {
            allowInferenceCalls = Boolean.parseBoolean(allowEnv.trim());
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

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
        return key != null && !key.isBlank();
    }
}
