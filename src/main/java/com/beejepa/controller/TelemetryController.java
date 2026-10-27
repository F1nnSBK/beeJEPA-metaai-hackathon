package com.beejepa.controller;

import com.beejepa.telemetry.LatencyStats;
import com.beejepa.telemetry.TelemetryEntry;
import com.beejepa.telemetry.TelemetryService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.List;
import java.util.Map;

@Controller("/api/v1/telemetry")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @Get("/stats")
    public LatencyStats getStats() {
        return telemetryService.getLatencyStats();
    }

    @Get("/logs")
    public List<TelemetryEntry> getLogs(@QueryValue(defaultValue = "50") int limit) {
        return telemetryService.getRecentLogs(limit);
    }

    @Get(value = "/readme-markdown", produces = MediaType.TEXT_PLAIN)
    public String getReadmeMarkdown() {
        return telemetryService.generateReadmeMarkdown();
    }

    @Post("/record")
    public HttpResponse<TelemetryEntry> recordCustomTelemetry(@Body Map<String, Object> payload) {
        String platform = (String) payload.getOrDefault("platform", "EXTERNAL_API");
        String method = (String) payload.getOrDefault("method", "POST");
        String uri = (String) payload.getOrDefault("uri", "/api/v1/external");
        int statusCode = payload.get("statusCode") instanceof Number num ? num.intValue() : 200;
        double durationMs = payload.get("durationMs") instanceof Number num ? num.doubleValue() : 0.0;
        String clientIp = (String) payload.getOrDefault("clientIp", "127.0.0.1");

        TelemetryEntry entry = telemetryService.recordRequest(platform, method, uri, statusCode, durationMs, clientIp);
        return HttpResponse.ok(entry);
    }
}
