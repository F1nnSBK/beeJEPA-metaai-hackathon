package com.beejepa.telemetry;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Introspected
@Serdeable
public record LatencyStats(
        long totalRequests,
        long errorCount,
        double errorRatePct,
        double minMs,
        double meanMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms,
        double p999Ms,
        double maxMs,
        Map<String, LatencyStats> endpointBreakdown,
        Map<String, LatencyStats> platformBreakdown,
        String generatedAt
) {
}
