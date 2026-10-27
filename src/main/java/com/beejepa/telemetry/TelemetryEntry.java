package com.beejepa.telemetry;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Introspected
@Serdeable
public record TelemetryEntry(
        String id,
        String platform, // e.g. "JAVA_HTTP", "META_MUSE", "SAM_3_1", "V_JEPA"
        String method,
        String uri,
        int statusCode,
        double durationMs,
        String clientIp,
        Instant timestamp
) {
}
