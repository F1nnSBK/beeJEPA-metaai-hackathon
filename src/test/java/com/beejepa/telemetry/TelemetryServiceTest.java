package com.beejepa.telemetry;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

@MicronautTest
class TelemetryServiceTest {

    @Inject
    TelemetryService telemetryService;

    @Test
    void testRecordAndRetrieveTelemetry() {
        TelemetryEntry entry = telemetryService.recordRequest(
                "JAVA_HTTP",
                "GET",
                "/api/v1/events",
                200,
                14.5,
                "127.0.0.1"
        );

        Assertions.assertNotNull(entry);
        Assertions.assertEquals("JAVA_HTTP", entry.platform());
        Assertions.assertEquals("GET", entry.method());
        Assertions.assertEquals("/api/v1/events", entry.uri());
        Assertions.assertEquals(200, entry.statusCode());
        Assertions.assertEquals(14.5, entry.durationMs());

        List<TelemetryEntry> recent = telemetryService.getRecentLogs(10);
        Assertions.assertFalse(recent.isEmpty());
        Assertions.assertTrue(recent.stream().anyMatch(e -> e.id().equals(entry.id())));
    }

    @Test
    void testPercentileCalculations() {
        String testPlatform = "BENCH_" + System.nanoTime();
        // Record controlled durations: 1ms, 2ms, 3ms, ..., 100ms
        for (int i = 1; i <= 100; i++) {
            telemetryService.recordRequest(
                    testPlatform,
                    "POST",
                    "/api/v1/benchmark",
                    i == 100 ? 500 : 200, // 1 error out of 100
                    (double) i,
                    "192.168.1.50"
            );
        }

        LatencyStats stats = telemetryService.getLatencyStats();
        Assertions.assertTrue(stats.totalRequests() >= 100);
        Assertions.assertTrue(stats.minMs() >= 0.0);
        Assertions.assertTrue(stats.p50Ms() > 0.0);
        Assertions.assertTrue(stats.p95Ms() >= stats.p50Ms());
        Assertions.assertTrue(stats.p99Ms() >= stats.p95Ms());
        Assertions.assertTrue(stats.maxMs() >= stats.p99Ms());

        Assertions.assertTrue(stats.platformBreakdown().containsKey(testPlatform));
        LatencyStats benchStats = stats.platformBreakdown().get(testPlatform);
        Assertions.assertEquals(100, benchStats.totalRequests());
        Assertions.assertEquals(1, benchStats.errorCount());
        Assertions.assertTrue(benchStats.minMs() >= 1.0);
        Assertions.assertEquals(1.0, benchStats.errorRatePct());
        Assertions.assertEquals(1.0, benchStats.minMs());
        Assertions.assertEquals(100.0, benchStats.maxMs());
        Assertions.assertEquals(50.5, benchStats.meanMs());
        Assertions.assertEquals(50.5, benchStats.p50Ms());
        Assertions.assertEquals(99.01, benchStats.p99Ms());
    }

    @Test
    void testGenerateReadmeMarkdown() {
        telemetryService.recordRequest(
                "META_MUSE",
                "POST",
                "/api/v1/muse/query",
                200,
                342.1,
                "10.0.0.1"
        );

        String markdown = telemetryService.generateReadmeMarkdown();
        Assertions.assertNotNull(markdown);
        Assertions.assertTrue(markdown.contains("## Production Latency & Performance Benchmarks"));
        Assertions.assertTrue(markdown.contains("**p99**"));
        Assertions.assertTrue(markdown.contains("/prometheus"));
        Assertions.assertTrue(markdown.contains("scrape_configs:"));
    }
}
