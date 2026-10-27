package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.ContainedBee;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class MuseDiskCacheTest {

    @Test
    void testSpatialSummaryPersistence(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(new ObjectMapper(), tempDir);

        EventSpatialSummary summary = new EventSpatialSummary(
                "evt_test_001",
                "12:00:00",
                4.25,
                "HIGH",
                25,
                3,
                "multi_bee_interaction_cluster",
                List.of(new ContainedBee(0, List.of(300, 350, 340, 390), 0.85)),
                "dummy_frame_base64",
                "dummy_crop_base64"
        );

        // Before saving, should be empty
        Assertions.assertTrue(cache.loadSpatialSummary("evt_test_001").isEmpty());

        // Save
        cache.saveSpatialSummary(summary);

        // Load back
        Optional<EventSpatialSummary> loaded = cache.loadSpatialSummary("evt_test_001");
        Assertions.assertTrue(loaded.isPresent());
        Assertions.assertEquals("evt_test_001", loaded.get().eventId());
        Assertions.assertEquals("12:00:00", loaded.get().timestamp());
        Assertions.assertEquals(4.25, loaded.get().surpriseScoreSigma());
        Assertions.assertEquals(25, loaded.get().landingBoardBeeCount());
        Assertions.assertEquals(3, loaded.get().beesInsideHotspotCount());
        Assertions.assertEquals("multi_bee_interaction_cluster", loaded.get().geometricPreClassification());
        Assertions.assertEquals(1, loaded.get().containedBees().size());
        Assertions.assertEquals("dummy_frame_base64", loaded.get().frameBase64());
        Assertions.assertEquals("dummy_crop_base64", loaded.get().cropBase64());
    }

    @Test
    void testMuseResponsesPersistence(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(new ObjectMapper(), tempDir);

        String key = "what happened at my hive today?|evt_001,evt_002";
        String report = "### Summary\nHive activity is high.";

        // Initially empty
        Assertions.assertTrue(cache.getMuseResponse(key).isEmpty());
        Assertions.assertTrue(cache.getAnyFallbackResponse().isEmpty());

        // Save response
        cache.saveMuseResponse(key, report);

        // Lookup with exact and varied case/spaces
        Assertions.assertEquals(Optional.of(report), cache.getMuseResponse(key));
        Assertions.assertEquals(Optional.of(report), cache.getMuseResponse("  WHAT HAPPENED AT MY HIVE TODAY?|evt_001,evt_002  "));
        Assertions.assertEquals(Optional.of(report), cache.getAnyFallbackResponse());

        // Reload from disk in a fresh instance
        MuseDiskCache newCacheInstance = new MuseDiskCache(new ObjectMapper(), tempDir);
        Assertions.assertEquals(Optional.of(report), newCacheInstance.getMuseResponse(key));
    }
}
