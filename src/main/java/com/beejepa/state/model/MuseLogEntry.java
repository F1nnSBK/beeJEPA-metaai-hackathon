package com.beejepa.state.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Introspected
@Serdeable
public record MuseLogEntry(
        String id,
        String endpoint,
        String requestSummary,
        int statusCode,
        long latencyMs,
        Instant timestamp
) {
}
