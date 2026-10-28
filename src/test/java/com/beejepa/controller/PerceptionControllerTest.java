package com.beejepa.controller;

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
}
