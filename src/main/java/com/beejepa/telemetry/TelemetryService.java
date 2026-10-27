package com.beejepa.telemetry;

import com.beejepa.state.StateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Singleton
public class TelemetryService {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryService.class);

    private final StateRepository stateRepository;
    private final MeterRegistry meterRegistry;

    public TelemetryService(StateRepository stateRepository, MeterRegistry meterRegistry) {
        this.stateRepository = stateRepository;
        this.meterRegistry = meterRegistry;
    }

    public TelemetryEntry recordRequest(
            String platform,
            String method,
            String uri,
            int statusCode,
            double durationMs,
            String clientIp
    ) {
        String id = "tel_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        String safePlatform = (platform != null && !platform.isBlank()) ? platform : "JAVA_HTTP";
        String safeMethod = (method != null && !method.isBlank()) ? method : "GET";
        String safeUri = (uri != null && !uri.isBlank()) ? uri : "/";
        String safeIp = (clientIp != null && !clientIp.isBlank()) ? clientIp : "127.0.0.1";

        TelemetryEntry entry = new TelemetryEntry(
                id,
                safePlatform,
                safeMethod,
                safeUri,
                statusCode,
                durationMs,
                safeIp,
                now
        );

        // 1. Prometheus / Micrometer Metrics Recording
        try {
            Timer.builder("beejepa.http.requests")
                    .description("Execution latency for BeeJEPA endpoints across platforms")
                    .tag("platform", safePlatform)
                    .tag("method", safeMethod)
                    .tag("uri", normalizeUriTag(safeUri))
                    .tag("status", String.valueOf(statusCode))
                    .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                    .register(meterRegistry)
                    .record((long) (durationMs * 1_000_000.0), TimeUnit.NANOSECONDS);

            Counter.builder("beejepa.http.requests.total")
                    .description("Total HTTP request counter")
                    .tag("platform", safePlatform)
                    .tag("method", safeMethod)
                    .tag("status", String.valueOf(statusCode))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            LOG.warn("Failed to record metric to Micrometer registry: {}", e.getMessage());
        }

        // 2. Persistent Storage in SQLite
        try {
            stateRepository.saveTelemetry(entry);
        } catch (Exception e) {
            LOG.error("Failed to persist telemetry log into SQLite: {}", e.getMessage());
        }

        return entry;
    }

    public List<TelemetryEntry> getRecentLogs(int limit) {
        return stateRepository.getRecentTelemetry(limit);
    }

    public LatencyStats getLatencyStats() {
        List<TelemetryEntry> allEntries = stateRepository.getAllTelemetry();
        return computeStats(allEntries, true);
    }

    public LatencyStats computeStats(List<TelemetryEntry> entries, boolean computeBreakdowns) {
        if (entries == null || entries.isEmpty()) {
            return new LatencyStats(
                    0, 0, 0.0,
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Instant.now().toString()
            );
        }

        long total = entries.size();
        long errors = entries.stream().filter(e -> e.statusCode() >= 400).count();
        double errorRate = (total > 0) ? (errors * 100.0 / total) : 0.0;

        List<Double> durations = entries.stream()
                .map(TelemetryEntry::durationMs)
                .sorted()
                .toList();

        double min = durations.getFirst();
        double max = durations.getLast();
        double sum = durations.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / total;

        double p50 = calculatePercentile(durations, 50.0);
        double p90 = calculatePercentile(durations, 90.0);
        double p95 = calculatePercentile(durations, 95.0);
        double p99 = calculatePercentile(durations, 99.0);
        double p999 = calculatePercentile(durations, 99.9);

        Map<String, LatencyStats> endpointBreakdown = Collections.emptyMap();
        Map<String, LatencyStats> platformBreakdown = Collections.emptyMap();

        if (computeBreakdowns) {
            endpointBreakdown = entries.stream()
                    .collect(Collectors.groupingBy(TelemetryEntry::uri))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> computeStats(e.getValue(), false)
                    ));

            platformBreakdown = entries.stream()
                    .collect(Collectors.groupingBy(TelemetryEntry::platform))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> computeStats(e.getValue(), false)
                    ));
        }

        return new LatencyStats(
                total,
                errors,
                round2(errorRate),
                round2(min),
                round2(mean),
                round2(p50),
                round2(p90),
                round2(p95),
                round2(p99),
                round2(p999),
                round2(max),
                endpointBreakdown,
                platformBreakdown,
                Instant.now().toString()
        );
    }

    public String generateReadmeMarkdown() {
        LatencyStats stats = getLatencyStats();
        StringBuilder sb = new StringBuilder();

        sb.append("## Production Latency & Performance Benchmarks\n\n");
        sb.append("Real-time telemetry captured via Micrometer & Prometheus, persisted durably in SQLite.\n\n");

        sb.append("### Overall Latency Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("| :--- | :--- |\n");
        sb.append(String.format(Locale.US, "| **Total Processed Requests** | %,d |\n", stats.totalRequests()));
        sb.append(String.format(Locale.US, "| **Error Count / Rate** | %d (%.2f%%) |\n", stats.errorCount(), stats.errorRatePct()));
        sb.append(String.format(Locale.US, "| **Min Latency** | %.2f ms |\n", stats.minMs()));
        sb.append(String.format(Locale.US, "| **Mean Latency** | %.2f ms |\n", stats.meanMs()));
        sb.append(String.format(Locale.US, "| **p50 (Median)** | %.2f ms |\n", stats.p50Ms()));
        sb.append(String.format(Locale.US, "| **p90** | %.2f ms |\n", stats.p90Ms()));
        sb.append(String.format(Locale.US, "| **p95** | %.2f ms |\n", stats.p95Ms()));
        sb.append(String.format(Locale.US, "| **p99** | **%.2f ms** |\n", stats.p99Ms()));
        sb.append(String.format(Locale.US, "| **p99.9** | %.2f ms |\n", stats.p999Ms()));
        sb.append(String.format(Locale.US, "| **Max Latency** | %.2f ms |\n", stats.maxMs()));
        sb.append(String.format(Locale.US, "| **Benchmark Timestamp** | %s |\n\n", stats.generatedAt()));

        if (!stats.endpointBreakdown().isEmpty()) {
            sb.append("### Per-Endpoint Percentile Breakdown\n\n");
            sb.append("| Endpoint URI | Requests | Mean (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Error Rate |\n");
            sb.append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");

            stats.endpointBreakdown().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().totalRequests(), a.getValue().totalRequests()))
                    .forEach(entry -> {
                        String uri = entry.getKey();
                        LatencyStats s = entry.getValue();
                        sb.append(String.format(Locale.US,
                                "| `%s` | %,d | %.2f | %.2f | %.2f | **%.2f** | %.2f | %.1f%% |\n",
                                uri, s.totalRequests(), s.meanMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.maxMs(), s.errorRatePct()
                        ));
                    });
            sb.append("\n");
        }

        if (!stats.platformBreakdown().isEmpty()) {
            sb.append("### Platform Breakdown (Java App vs. Edge / Microservices)\n\n");
            sb.append("| Platform / Service | Requests | Mean (ms) | p50 (ms) | p99 (ms) |\n");
            sb.append("| :--- | ---: | ---: | ---: | ---: |\n");
            stats.platformBreakdown().forEach((platform, s) -> {
                sb.append(String.format(Locale.US,
                        "| `%s` | %,d | %.2f | %.2f | **%.2f** |\n",
                        platform, s.totalRequests(), s.meanMs(), s.p50Ms(), s.p99Ms()
                ));
            });
            sb.append("\n");
        }

        sb.append("### Prometheus Scraping Configuration\n\n");
        sb.append("All metrics are exposed at the standard Prometheus scrape endpoint:\n");
        sb.append("```yaml\n");
        sb.append("scrape_configs:\n");
        sb.append("  - job_name: 'beejepa-java'\n");
        sb.append("    metrics_path: '/prometheus'\n");
        sb.append("    scrape_interval: 5s\n");
        sb.append("    static_configs:\n");
        sb.append("      - targets: ['localhost:8080']\n");
        sb.append("```\n");

        return sb.toString();
    }

    private double calculatePercentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0.0;
        if (sorted.size() == 1) return sorted.getFirst();

        double rank = (percentile / 100.0) * (sorted.size() - 1);
        int lowerIndex = (int) Math.floor(rank);
        int upperIndex = (int) Math.ceil(rank);

        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }

        double weight = rank - lowerIndex;
        return sorted.get(lowerIndex) * (1.0 - weight) + sorted.get(upperIndex) * weight;
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private String normalizeUriTag(String uri) {
        // Collapse dynamic IDs (e.g. /api/v1/events/evt_rec2125_... -> /api/v1/events/{id})
        return uri.replaceAll("evt_[a-zA-Z0-9_]+", "{eventId}")
                .replaceAll("tel_[a-zA-Z0-9_]+", "{telId}");
    }
}
