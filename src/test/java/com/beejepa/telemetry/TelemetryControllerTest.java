package com.beejepa.telemetry;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

@MicronautTest
class TelemetryControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void testTelemetryEndpointsAndPrometheusScraping() {
        // 1. Make a real request to trigger TelemetryHttpFilter
        HttpResponse<String> pingResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/ping"),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, pingResponse.getStatus());
        Assertions.assertTrue(pingResponse.getHeaders().contains("Server-Timing"));

        // 2. Fetch Prometheus endpoint to verify standard Prometheus scrape output
        HttpResponse<String> prometheusResponse = client.toBlocking().exchange(
                HttpRequest.GET("/prometheus"),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, prometheusResponse.getStatus());
        String prometheusBody = prometheusResponse.body();
        Assertions.assertNotNull(prometheusBody);
        Assertions.assertTrue(prometheusBody.contains("beejepa_http_requests") || prometheusBody.contains("jvm_"));

        // 3. Fetch Telemetry Stats
        HttpResponse<LatencyStats> statsResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/telemetry/stats"),
                LatencyStats.class
        );
        Assertions.assertEquals(HttpStatus.OK, statsResponse.getStatus());
        LatencyStats stats = statsResponse.body();
        Assertions.assertNotNull(stats);
        Assertions.assertTrue(stats.totalRequests() > 0);
        Assertions.assertTrue(stats.p99Ms() >= 0.0);

        // 4. Fetch Markdown summary for README
        HttpResponse<String> markdownResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/telemetry/readme-markdown"),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, markdownResponse.getStatus());
        String markdown = markdownResponse.body();
        Assertions.assertNotNull(markdown);
        Assertions.assertTrue(markdown.contains("## Production Latency & Performance Benchmarks"));
        Assertions.assertTrue(markdown.contains("| **p99** |"));
    }
}
