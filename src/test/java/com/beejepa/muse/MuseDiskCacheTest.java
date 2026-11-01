package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.beejepa.muse.SamGeometry.ContainedBee;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class MuseDiskCacheTest {

    @Test
    void testSpatialSummaryPersistenceAndBoardBees(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);

        EventSpatialSummary summary = new EventSpatialSummary(
                "evt_test_001",
                "12:00:00",
                4.25,
                "HIGH",
                25,
                3,
                "multi_bee_interaction_cluster",
                List.of(new ContainedBee(0, List.of(300, 350, 340, 390), 0.85)),
                List.of(List.of(300, 350, 340, 390), List.of(210, 380, 240, 410)),
                "dummy_frame_base64",
                "dummy_crop_base64"
        );

        Assertions.assertTrue(cache.loadSpatialSummary("evt_test_001").isEmpty());

        cache.saveSpatialSummary(summary);

        Optional<EventSpatialSummary> loaded = cache.loadSpatialSummary("evt_test_001");
        Assertions.assertTrue(loaded.isPresent());
        Assertions.assertEquals("evt_test_001", loaded.get().eventId());
        Assertions.assertEquals(25, loaded.get().landingBoardBeeCount());
        Assertions.assertEquals(3, loaded.get().beesInsideHotspotCount());
        Assertions.assertEquals(2, loaded.get().boardBees().size());
        Assertions.assertEquals(1, loaded.get().containedBees().size());
    }

    @Test
    void testRejectsCorruptedZeroHotspotEntranceCacheAndRecoversFromBackup(@TempDir Path tempDir) throws Exception {
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);

        // 1. Valid f1217 summary saved first
        EventSpatialSummary validF1217 = new EventSpatialSummary(
                "evt_rec2125_seq000_f1217",
                "00:20",
                13.72,
                "CRITICAL",
                35,
                12,
                "multi_bee_interaction_cluster",
                List.of(new ContainedBee(0, List.of(193, 412, 232, 439), 1.0)),
                List.of(List.of(193, 412, 232, 439)),
                "valid_frame",
                "valid_crop"
        );
        cache.saveSpatialSummary(validF1217);
        Assertions.assertTrue(cache.loadSpatialSummary("evt_rec2125_seq000_f1217").isPresent());

        // 2. Write a second valid version so .bak is created, then corrupt the primary file
        cache.saveSpatialSummary(validF1217);
        Path primaryFile = tempDir.resolve("sam_spatial_evt_rec2125_seq000_f1217.json");
        Files.writeString(primaryFile, "{corrupted_truncated_json...");

        // Should recover automatically from .bak
        Optional<EventSpatialSummary> recovered = cache.loadSpatialSummary("evt_rec2125_seq000_f1217");
        Assertions.assertTrue(recovered.isPresent());
        Assertions.assertEquals(12, recovered.get().beesInsideHotspotCount());

        // 3. A buggy 0-hotspot-bee f1217 summary is rejected by isValidSpatialSummary
        EventSpatialSummary buggyZeroBeeF1217 = new EventSpatialSummary(
                "evt_rec2125_seq000_f1217",
                "00:20",
                13.72,
                "CRITICAL",
                37,
                0,
                "potential_non_bee_anomaly",
                List.of(),
                "frame",
                "crop"
        );
        Assertions.assertFalse(cache.isValidSpatialSummary(buggyZeroBeeF1217));
    }

    @Test
    void testMuseResponsesPersistence(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);

        String key = "what happened at my hive today?|evt_001,evt_002";
        String report = "### Summary\nHive activity is high.";

        Assertions.assertTrue(cache.getMuseResponse(key).isEmpty());
        Assertions.assertTrue(cache.getAnyFallbackResponse().isEmpty());

        cache.saveMuseResponse(key, report);

        Assertions.assertEquals(Optional.of(report), cache.getMuseResponse(key));
        Assertions.assertEquals(Optional.of(report), cache.getMuseResponse("  WHAT HAPPENED AT MY HIVE TODAY?|evt_001,evt_002  "));
        Assertions.assertEquals(Optional.of(report), cache.getAnyFallbackResponse());

        MuseDiskCache newCacheInstance = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);
        Assertions.assertEquals(Optional.of(report), newCacheInstance.getMuseResponse(key));
    }
}
