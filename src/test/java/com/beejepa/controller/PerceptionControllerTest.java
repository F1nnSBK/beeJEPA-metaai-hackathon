package com.beejepa.controller;

import com.beejepa.muse.SamGeometry;
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
    void testGetSaccades() {
        HttpResponse<List<SaccadicTarget>> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/perception/saccades"),
                Argument.listOf(SaccadicTarget.class)
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        List<SaccadicTarget> saccades = response.body();
        Assertions.assertNotNull(saccades);
        Assertions.assertFalse(saccades.isEmpty());

        SaccadicTarget first = saccades.getFirst();
        Assertions.assertEquals("evt_rec2125_seq000_f0000", first.eventId());
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
    void testAblationPyramidEndpoint() {
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/v1/perception/ablation-pyramid?eventId=evt_rec2125_seq000_f0000"),
                Argument.mapOf(String.class, Object.class)
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatus());
        Map<String, Object> body = response.body();
        Assertions.assertNotNull(body);
        Assertions.assertEquals("evt_rec2125_seq000_f0000", body.get("eventId"));

        Map<String, Object> funnel = (Map<String, Object>) body.get("funnelMetrics");
        Assertions.assertNotNull(funnel);
        Assertions.assertEquals(88, ((Number) funnel.get("fullFramePatches")).intValue());
        Assertions.assertEquals(88, ((Number) funnel.get("nativeCropPatches")).intValue());
        Assertions.assertEquals(729, ((Number) funnel.get("fovealCropPatches")).intValue());
        Assertions.assertEquals(68.6, ((Number) funnel.get("attentionFlopRatio")).doubleValue(), 0.1);
        Assertions.assertEquals(30.0, ((Number) funnel.get("staticB1PointingHitRatePct")).doubleValue(), 0.1);
        Assertions.assertEquals(21.0, ((Number) funnel.get("randomGazeB3PointingHitRatePct")).doubleValue(), 0.1);
        Assertions.assertTrue(((Number) funnel.get("hotspotBeeCoverageRatio")).doubleValue() > 0.22);
        Assertions.assertTrue(((Number) funnel.get("boardBeesCount")).intValue() > 0);
        Assertions.assertTrue(((Number) funnel.get("hotspotBeesCount")).intValue() > 0);

        List<Map<String, Object>> tiers = (List<Map<String, Object>>) body.get("tiers");
        Assertions.assertNotNull(tiers);
        Assertions.assertEquals(5, tiers.size());
        Assertions.assertEquals("TIER_0_RAW_STREAM", tiers.getFirst().get("id"));
        Assertions.assertEquals("TIER_4_MUSE_SPARK_REASONING", tiers.getLast().get("id"));

        List<Map<String, Object>> counterfactuals = (List<Map<String, Object>>) body.get("counterfactuals");
        Assertions.assertNotNull(counterfactuals);
        Assertions.assertEquals(6, counterfactuals.size());
        Assertions.assertEquals(30.0, ((Number) counterfactuals.get(3).get("pointingHitRatePct")).doubleValue(), 0.1);
        Assertions.assertEquals(100.0, ((Number) counterfactuals.get(5).get("pointingHitRatePct")).doubleValue(), 0.1);
    }

    @Test
    void testSamGeometryDefensiveBeeBallingVoidGuard() {
        SamGeometry.BoundingBox hotspot = new SamGeometry.BoundingBox(200, 300, 400, 500); // area = 40,000 px^2
        List<SamGeometry.ContainedBee> peripheralBees = List.of(
                new SamGeometry.ContainedBee(0, List.of(205, 305, 235, 335), 1.0), // 900 px^2
                new SamGeometry.ContainedBee(1, List.of(365, 465, 395, 495), 1.0)  // 900 px^2
        );
        double coverage = SamGeometry.computeHotspotBeeCoverageRatio(hotspot, peripheralBees);
        Assertions.assertTrue(coverage < 0.22);
        // Under extreme surpriseSigma >= 12.5 with <22% bee coverage, classifyHotspot triggers the bee-balling guard
        Assertions.assertEquals(
                "defensive_bee_balling_or_intruder_candidate",
                SamGeometry.classifyHotspot(peripheralBees, hotspot, 16.05)
        );
        // Under moderate surpriseSigma < 12.5, standard multi-bee cluster classification applies
        Assertions.assertEquals(
                "multi_bee_interaction_cluster",
                SamGeometry.classifyHotspot(peripheralBees, hotspot, 9.5)
        );
    }
}
