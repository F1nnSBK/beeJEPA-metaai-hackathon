package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Introspected
@Serdeable
public record MuseInferenceRequest(
        String prompt,
        String model,
        Double temperature,
        Integer maxTokens,
        Map<String, Object> parameters
) {
    public MuseInferenceRequest(String prompt) {
        this(prompt, "meta-muse-v1", 0.7, 512, Map.of());
    }
}
