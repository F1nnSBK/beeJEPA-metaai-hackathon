package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Introspected
@Serdeable
public record StagedMuseRequest(
        boolean staged,
        boolean sent,
        String targetUrl,
        String httpMethod,
        String model,
        Map<String, String> headers,
        Map<String, Object> payload,
        String prompt,
        String eventId,
        Map<String, Object> eventData,
        int estimatedTokens,
        long creditsConsumed,
        String statusMessage,
        String preparedAt
) {}
