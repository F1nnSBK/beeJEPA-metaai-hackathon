package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Introspected
@Serdeable
public record StagedSamRequest(
        boolean staged,
        boolean sent,
        String targetUrl,
        String httpMethod,
        String model,
        Map<String, String> headers,
        Map<String, Object> imagePayload,
        Map<String, Object> videoPayload,
        String prompt,
        String eventId,
        Map<String, Object> hotspot,
        String peakFrameUrl,
        String videoClipUrl,
        String curlCommand,
        long creditsConsumed,
        String statusMessage,
        String preparedAt
) {
}
