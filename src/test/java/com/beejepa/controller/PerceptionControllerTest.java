package com.beejepa.controller;

import com.beejepa.event.EventService;
import com.beejepa.perception.OffHeapFrameRingBuffer;
import com.beejepa.perception.SaccadicTarget;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

@MicronautTest
class PerceptionControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EventService eventService;

    @Test
    void testGetRingBufferTelemetry() {
        HttpResponse<OffHeapFrameRingBuffer.BufferTelemetry> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/perception/ring-buffer"),
                OffHeapFrameRingBuffer.BufferTelemetry.class
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        OffHeapFrameRingBuffer.BufferTelemetry telemetry = response.body();
        Assertions.assertNotNull(telemetry);
        Assertions.assertEquals(OffHeapFrameRingBuffer.DEFAULT_CAPACITY, telemetry.capacity());
        Assertions.assertTrue(telemetry.isOffHeap());
        Assertions.assertEquals(0L, telemetry.droppedFrames());
    }

    @Test
    void testGetSaccades() throws InterruptedException {
        eventService.awaitHotspotMeasurement(10_000);
        HttpResponse<List<SaccadicTarget>> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/perception/saccades"),
                Argument.listOf(SaccadicTarget.class)
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        List<SaccadicTarget> saccades = response.body();
        Assertions.assertNotNull(saccades);
        Assertions.assertFalse(saccades.isEmpty());

        SaccadicTarget first = saccades.getFirst();
        Assertions.assertNotNull(first.eventId());
        Assertions.assertTrue(first.focusCenterX() > 0);
        Assertions.assertTrue(first.focusCenterY() > 0);

        // Fetch single event saccade
        HttpResponse<SaccadicTarget> single = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/perception/saccades/" + first.eventId()),
                SaccadicTarget.class
        );
        Assertions.assertEquals(HttpStatus.OK, single.getStatus());
        Assertions.assertEquals(first.eventId(), single.body().eventId());
    }

    @Test
    void testSimdEvalEndpoint() {
        Map<String, List<Double>> payload = Map.of(
                "predicted", List.of(0.0, 0.0, 0.0, 0.0),
                "target", List.of(3.0, 4.0, 0.0, 0.0)
        );

        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(
                HttpRequest.POST("/api/v1/perception/simd-eval", payload),
                Argument.mapOf(String.class, Object.class)
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        Map<String, Object> body = response.body();
        Assertions.assertNotNull(body);

        double dist = ((Number) body.get("euclideanDistance")).doubleValue();
        Assertions.assertEquals(5.0, dist, 1e-4);
        Assertions.assertTrue(((Number) body.get("vectorBitWidth")).intValue() >= 128);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAblationPyramidEndpoint() throws InterruptedException {
        eventService.awaitHotspotMeasurement(10_000);
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/perception/ablation-pyramid?eventId=evt_rec2125_seq000_f0000"),
                Argument.mapOf(String.class, Object.class)
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        Map<String, Object> body = response.body();
        Assertions.assertNotNull(body);
        Assertions.assertEquals("evt_rec2125_seq000_f0000", body.get("eventId"));
        Assertions.assertFalse(body.containsKey("funnelMetrics"));
        Assertions.assertFalse(body.containsKey("counterfactuals"));

        Map<String, Object> measured = (Map<String, Object>) body.get("measured");
        Assertions.assertNotNull(measured);
        Assertions.assertEquals(20, ((Number) measured.get("eventsTotal")).intValue());
        Assertions.assertEquals(280, ((Number) measured.get("timelineWindows")).intValue());
        Assertions.assertTrue(((Number) measured.get("decisionThresholdTau")).doubleValue() > 13.0);

        List<Map<String, Object>> tiers = (List<Map<String, Object>>) body.get("tiers");
        Assertions.assertNotNull(tiers);
        Assertions.assertEquals(5, tiers.size());
        Assertions.assertEquals("TIER_0_RAW_STREAM", tiers.getFirst().get("id"));
        Assertions.assertEquals("TIER_4_MUSE_SPARK_REASONING", tiers.getLast().get("id"));
    }
}
