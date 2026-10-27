package com.beejepa.event;

import com.beejepa.config.StorageConfig;
import com.beejepa.storage.StorageService;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EventServiceTest {

    private EventService eventService;

    @BeforeEach
    void setUp() {
        StorageConfig storageConfig = new StorageConfig();
        storageConfig.getData().setPath(Paths.get("data").toAbsolutePath().toString());
        storageConfig.getData().setRequireSymlink(false);

        StorageService storageService = new StorageService(storageConfig);
        storageService.init();

        ObjectMapper objectMapper = ObjectMapper.getDefault();
        this.eventService = new EventService(storageService, objectMapper);
    }

    @Test
    void testLazyLoadEventsFromHackathonSample() {
        List<Map<String, Object>> events = eventService.listEvents();
        Assertions.assertNotNull(events);
        Assertions.assertEquals(20, events.size(), "Should load exactly 20 events from hackathon sample manifest");

        Map<String, Object> firstEvent = events.get(0);
        Assertions.assertEquals("evt_rec2125_seq000_f0000", firstEvent.get("eventId"));
        Assertions.assertEquals("rec2125_seq000", firstEvent.get("sequenceId"));
        Assertions.assertEquals("CRITICAL", firstEvent.get("severity"));
        Assertions.assertEquals(16.052, Double.parseDouble(String.valueOf(firstEvent.get("surpriseScoreL2"))), 0.001);
        Assertions.assertNotNull(firstEvent.get("hotspot"));
    }

    @Test
    void testLazyLoadTimelineFromHackathonSample() {
        Map<String, Object> timeline = eventService.getTimeline();
        Assertions.assertNotNull(timeline);
        Assertions.assertEquals(300.0, Double.parseDouble(String.valueOf(timeline.get("totalDurationSec"))));
        Assertions.assertEquals("05:00", timeline.get("formattedDuration"));
        Assertions.assertEquals(10.2, Double.parseDouble(String.valueOf(timeline.get("decisionThreshold"))));

        List<?> points = (List<?>) timeline.get("timeline");
        Assertions.assertNotNull(points);
        Assertions.assertEquals(280, points.size(), "Should contain 280 timeline points");
    }

    @Test
    void testGetMediaClipAndLazyPeakFrame() {
        Optional<File> clip = eventService.getMediaFile("evt_rec2125_seq000_f0000", "clip_raw.mp4");
        Assertions.assertTrue(clip.isPresent(), "Raw event clip should be present in event_clips");
        Assertions.assertTrue(clip.get().exists());

        Optional<File> frame = eventService.getMediaFile("evt_rec2125_seq000_f0000", "peak_frame.jpg");
        Assertions.assertTrue(frame.isPresent(), "Peak frame should be lazily extracted or present");
        Assertions.assertTrue(frame.get().exists());
        Assertions.assertTrue(frame.get().length() > 1000);
    }
}
