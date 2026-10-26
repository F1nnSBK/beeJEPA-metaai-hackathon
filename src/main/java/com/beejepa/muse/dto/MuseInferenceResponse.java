package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

@Introspected
@Serdeable
public record MuseInferenceResponse(
        String id,
        String model,
        List<Double> embedding,
        String outputText,
        Map<String, Object> metadata
) {
}
