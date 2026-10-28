package com.beejepa.perception;

import com.beejepa.event.EventService;
import com.beejepa.storage.StorageService;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class ActivePerceptionServiceTest {

    private OffHeapFrameRingBuffer ringBuffer;
    private SimdLatentEvaluator simdEvaluator;
    private EventService eventService;
    private ActivePerceptionService perceptionService;

    @BeforeEach
    void setUp() {
        ringBuffer = new OffHeapFrameRingBuffer(50);
        simdEvaluator = new SimdLatentEvaluator();
        com.beejepa.config.StorageConfig storageConfig = new com.beejepa.config.StorageConfig();
        storageConfig.getData().setPath(java.nio.file.Paths.get("data").toAbsolutePath().toString());
        storageConfig.getData().setRequireSymlink(false);

        StorageService storageService = new StorageService(storageConfig);
        storageService.init();

        ObjectMapper objectMapper = ObjectMapper.getDefault();
        eventService = new EventService(storageService, objectMapper);
        perceptionService = new ActivePerceptionService(ringBuffer, simdEvaluator, eventService);
        perceptionService.initialize();
    }

    @AfterEach
    void tearDown() {
        if (ringBuffer != null) {
            ringBuffer.close();
        }
    }

    @Test
    void testTrajectoryGenerationFromRealEvents() {
        List<SaccadicTarget> trajectory = perceptionService.getSaccadicTrajectory();
        Assertions.assertFalse(trajectory.isEmpty(), "Trajectory should be loaded from real manifest events");
        Assertions.assertTrue(trajectory.size() >= 10, "Should contain at least 10 sample events");

        SaccadicTarget first = trajectory.getFirst();
        Assertions.assertEquals("evt_rec2125_seq000_f0000", first.eventId());
        Assertions.assertTrue(first.surpriseScoreSigma() > 10.0, "First peak event surprise should exceed tau=10.2");
        Assertions.assertNotNull(first.foveaCropBox());
        Assertions.assertEquals(4, first.foveaCropBox().size());
    }

    @Test
    void testTargetRetrievalByEventId() {
        Optional<SaccadicTarget> targetOpt = perceptionService.getTarget("evt_rec2125_seq000_f0000");
        Assertions.assertTrue(targetOpt.isPresent());

        SaccadicTarget target = targetOpt.get();
        Assertions.assertEquals("evt_rec2125_seq000_f0000", target.eventId());
        Assertions.assertEquals("multi_bee_interaction_cluster", target.classification());
        Assertions.assertTrue(target.focusCenterX() > 0);
        Assertions.assertTrue(target.focusCenterY() > 0);
    }

    @Test
    void testRingBufferIngestionIntegrity() {
        OffHeapFrameRingBuffer.BufferTelemetry telemetry = perceptionService.getRingBufferTelemetry();
        Assertions.assertTrue(telemetry.activeSlotsCount() > 0, "Real keyframes should have been ingested into off-heap buffer");
        Assertions.assertTrue(telemetry.isOffHeap());
        Assertions.assertEquals(0L, telemetry.droppedFrames());
    }
}
