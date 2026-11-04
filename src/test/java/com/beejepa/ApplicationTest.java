package com.beejepa;

import com.beejepa.muse.MuseDiskCache;
import com.beejepa.muse.SamGeometry;
import com.beejepa.state.StateService;
import com.beejepa.storage.StorageService;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

@MicronautTest
class ApplicationTest {

    @Inject
    EmbeddedApplication<?> application;

    @Inject
    StateService stateService;

    @Inject
    StorageService storageService;

    @Inject
    com.beejepa.controller.MuseController museController;

    @Inject
    MuseDiskCache museDiskCache;

    @Inject
    @io.micronaut.http.client.annotation.Client("/")
    io.micronaut.http.client.HttpClient httpClient;

    @Test
    void testSequenceVideoStreaming() {
        var req = io.micronaut.http.HttpRequest.GET("/api/v1/sequences/clip_00_rec2125_seq000_00_00_to_00_30.mp4")
                .header("Range", "bytes=0-100");
        var resp = httpClient.toBlocking().exchange(req, byte[].class);
        Assertions.assertTrue(resp.getStatus().getCode() == 200 || resp.getStatus().getCode() == 206);
    }

    @Test
    void testMuseControllerWiring() {
        Assertions.assertNotNull(museController);
    }

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

    @Test
    void testStatePersistence() {
        stateService.setState("test_key", "bee_value_123");
        var val = stateService.getState("test_key");
        Assertions.assertTrue(val.isPresent());
        Assertions.assertEquals("bee_value_123", val.get());
    }

    @Inject
    io.micronaut.json.JsonMapper jsonMapper;

    @Test
    void testEventSpatialSummarySerialization() throws Exception {
        var summary = new com.beejepa.muse.SamGeometry.EventSpatialSummary(
                "evt-1",
                "00:00",
                16.05,
                "HIGH",
                38,
                15,
                "multibee_cluster",
                java.util.List.of(new com.beejepa.muse.SamGeometry.ContainedBee(0, java.util.List.of(10, 20, 30, 40), 0.85)),
                "frameB64",
                "cropB64"
        );
        String json = jsonMapper.writeValueAsString(summary);
        Assertions.assertNotNull(json);
        Assertions.assertTrue(json.contains("evt-1"));
        Assertions.assertTrue(json.contains("16.05"));
    }

    @Test
    void testMuseAskEndpointSerialization() {
        String question = "What happened at the hive entrance? Analyze the segmented anomaly.";
        for (String id : List.of("evt_rec2125_seq000_f0000", "evt_rec2125_seq000_f1217", "evt_rec2125_seq001_f0320")) {
            museDiskCache.saveSpatialSummary(new SamGeometry.EventSpatialSummary(
                    id, "00:00", 16.05, "CRITICAL", 28, 11, "multi_bee_interaction_cluster",
                    List.of(
                            new SamGeometry.ContainedBee(0, List.of(220, 320, 380, 460), 0.92),
                            new SamGeometry.ContainedBee(1, List.of(240, 340, 360, 440), 0.88)
                    ),
                    List.of(List.of(220, 320, 380, 460), List.of(240, 340, 360, 440)),
                    "frameB64", "cropB64"
            ));
        }
        String cacheKey = question + "|,evt_rec2125_seq000_f0000,evt_rec2125_seq000_f1217,evt_rec2125_seq001_f0320";
        museDiskCache.saveMuseResponse(
                cacheKey,
                "<REPORT>### Live Cached Hive Report\n- 28 bees on landing board.</REPORT>\n<SPOKEN>Twenty-eight bees on the landing board.</SPOKEN>"
        );

        var req = io.micronaut.http.HttpRequest.POST("/api/v1/muse/ask", Map.of(
                "question", question
        ));
        var resp = httpClient.toBlocking().exchange(req, String.class);
        Assertions.assertEquals(200, resp.getStatus().getCode());
        Assertions.assertNotNull(resp.body());
        Assertions.assertTrue(resp.body().contains("spokenSummary") || resp.body().contains("events"));
    }

    @Test
    void testStorageStatus() {
        Map<String, Object> status = storageService.getStorageStatus();
        Assertions.assertNotNull(status);
        Assertions.assertTrue(status.containsKey("sqliteDatabasePath"));
    }

    @Test
    void testMuseTtsEndpoint() {
        var req = io.micronaut.http.HttpRequest.GET("/api/v1/muse/tts?text=Hive+entrance+status+normal");
        var resp = httpClient.toBlocking().exchange(req, byte[].class);
        Assertions.assertEquals(200, resp.getStatus().getCode());
        Assertions.assertNotNull(resp.body());
        Assertions.assertTrue(resp.body().length > 512);
        Assertions.assertEquals("audio/mpeg", resp.getContentType().map(Object::toString).orElse(""));
    }
}
