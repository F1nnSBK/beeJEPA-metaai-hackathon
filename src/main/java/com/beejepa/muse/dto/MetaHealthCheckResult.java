package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Introspected
@Serdeable
public record MetaHealthCheckResult(
        boolean configured,
        boolean liveConnection,
        String status,
        int statusCode,
        long latencyMs,
        String baseUrl,
        List<String> availableModels,
        String message
) {}
