package com.beejepa.event;

import com.beejepa.config.JacksonFactory;
import com.beejepa.config.StorageConfig;
import com.beejepa.muse.MuseDiskCache;
import com.beejepa.storage.StorageService;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EventServiceTest {

    private EventService eventService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        StorageConfig storageConfig = new StorageConfig();
        storageConfig.getData().setPath(Paths.get("data").toAbsolutePath().toString());
        storageConfig.getData().setRequireSymlink(false);

        StorageService storageService = new StorageService(storageConfig);
        storageService.init();

        ObjectMapper objectMapper = ObjectMapper.getDefault();
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);
        this.eventService = new EventService(storageService, objectMapper, cache);
    }

    @Test

    void testLazyLoadEventsFromHackathonSample() throws InterruptedException {
        eventService.awaitHotspotMeasurement(10_000);
        List<Map<String, Object>> events = eventService.listEvents();
        Assertions.assertNotNull(events);
        Assertions.assertEquals(20, events.size(), "Should load exactly 20 events from hackathon sample manifest");

        Map<String, Object> firstEvent = events.get(0);
        Assertions.assertEquals("evt_rec2125_seq000_f0000", firstEvent.get("eventId"));
        Assertions.assertEquals("rec2125_seq000", firstEvent.get("sequenceId"));
        Assertions.assertEquals("ABOVE_TAU", firstEvent.get("severity"));
        Assertions.assertEquals(Boolean.TRUE, firstEvent.get("aboveThreshold"));
        Assertions.assertEquals(16.052, Double.parseDouble(String.valueOf(firstEvent.get("tokenDeltaScore"))), 0.001);
        Assertions.assertEquals("MEASURED", firstEvent.get("hotspotStatus"));
        Assertions.assertNotNull(firstEvent.get("hotspot"));
    }

    @Test
    void testLazyLoadTimelineFromHackathonSample() {
        Map<String, Object> timeline = eventService.getTimeline();
        Assertions.assertNotNull(timeline);
        Assertions.assertEquals(300.0, Double.parseDouble(String.valueOf(timeline.get("totalDurationSec"))));
        Assertions.assertEquals("05:00", timeline.get("formattedDuration"));
        double tau = Double.parseDouble(String.valueOf(timeline.get("decisionThreshold")));
        Assertions.assertTrue(tau > 13.0, "Decision threshold tau should be the P99 of timeline windows: " + tau);
        Assertions.assertEquals("P99 of all timeline windows", timeline.get("decisionThresholdMethod"));

        List<?> points = (List<?>) timeline.get("timeline");
        Assertions.assertNotNull(points);
        Assertions.assertEquals(280, points.size(), "Should contain 280 timeline points");
    }

    @Test
    void testGetMediaClipAndInMemoryPeakFrameExtraction() {
        Optional<File> clip = eventService.getMediaFile("evt_rec2125_seq000_f0000", "clip_raw.mp4");
        Assertions.assertTrue(clip.isPresent(), "Raw event clip should be present in event_clips");
        Assertions.assertTrue(clip.get().exists());

        Optional<byte[]> frameBytes = eventService.getOrExtractEventFrameBytes("evt_rec2125_seq000_f0000");
        Assertions.assertTrue(frameBytes.isPresent(),
                "Peak frame bytes should be extracted via ffmpeg pipe and cached in SQLite/Redis");
        Assertions.assertTrue(frameBytes.get().length > 1000);
    }

    @Test
    void testGetSequenceFile() {
        Optional<File> seqFile = eventService.getSequenceFile("clip_00_rec2125_seq000_00_00_to_00_30.mp4");
        Assertions.assertTrue(seqFile.isPresent(), "Sequence clip should be present in sequences directory");
        Assertions.assertTrue(seqFile.get().exists());
        Assertions.assertTrue(seqFile.get().length() > 100_000);
    }

    @Test
    void testLoadFromCsvDirectory(@TempDir Path csvRoot) throws Exception {
        Path meta = java.nio.file.Files.createDirectories(csvRoot.resolve("metadata"));
        java.nio.file.Files.createDirectories(csvRoot.resolve("stitched_sequences"));

        java.nio.file.Files.writeString(meta.resolve("sequence_runs.csv"),
                "sequence_id,recording_id,sequence_index,stitched_video_path,mean_surprise,max_surprise\n"
                        + "rec2123_seq000,rec2123,0,stitched_sequences/full_rec2123_seq000.mp4,11.4,14.8\n");

        java.nio.file.Files.writeString(meta.resolve("timeline_points.csv"),
                "sequence_id,timestamp_sec,token_delta_score,board_peaks_9x9,event_id\n"
                        + "rec2123_seq000,0.0,14.8,24,evt_rec2123_seq000_f0000\n"
                        + "rec2123_seq000,1.07,11.2,18,\n");

        java.nio.file.Files.writeString(meta.resolve("event_catalog.csv"),
                "event_id,sequence_id,peak_frame,surprise_score_l2,board_peaks_16x16,grass_peaks,submanifold_state,pixel_diff_baseline,start_frame\n"
                        + "evt_rec2123_seq000_f0000,rec2123_seq000,0,14.8,26,3,active,4.2,0\n");

        CatalogLoader.RawCatalogData data = CatalogLoader.loadFromCsvDirectory(csvRoot);
        Assertions.assertNotNull(data);
        Assertions.assertEquals("ssd_metadata_csv", data.source());
        Assertions.assertEquals(1, data.sequences().size());
        Assertions.assertEquals(1, data.events().size());
        Assertions.assertEquals(2, data.timeline().size());
        Assertions.assertEquals("evt_rec2123_seq000_f0000", data.events().getFirst().eventId());
        Assertions.assertEquals(26, data.events().getFirst().boardPeaks());
    }
}
