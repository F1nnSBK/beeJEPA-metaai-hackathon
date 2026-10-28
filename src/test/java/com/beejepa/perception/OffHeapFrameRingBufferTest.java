package com.beejepa.perception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class OffHeapFrameRingBufferTest {

    private OffHeapFrameRingBuffer ringBuffer;

    @BeforeEach
    void setUp() {
        // Small capacity buffer for fast testing of wrap-around
        ringBuffer = new OffHeapFrameRingBuffer(5);
    }

    @AfterEach
    void tearDown() {
        if (ringBuffer != null) {
            ringBuffer.close();
        }
    }

    @Test
    void testInitialTelemetry() {
        OffHeapFrameRingBuffer.BufferTelemetry telemetry = ringBuffer.getTelemetry();
        Assertions.assertEquals(5, telemetry.capacity());
        Assertions.assertEquals(0, telemetry.currentSequence());
        Assertions.assertEquals(0, telemetry.activeSlotsCount());
        Assertions.assertTrue(telemetry.isOffHeap());
        Assertions.assertTrue(telemetry.totalAllocatedBytes() > 0);
    }

    @Test
    void testAppendAndRetrieve() {
        byte[] payload = "mock_frame_bytes_bee_entrance".getBytes(StandardCharsets.UTF_8);
        long seq = ringBuffer.appendFrame(1000_000L, 42L, 12.5f, 315.0f, 390.0f, payload);

        Assertions.assertEquals(0L, seq);

        Optional<OffHeapFrameRingBuffer.FrameEntry> entryOpt = ringBuffer.getBySequence(seq);
        Assertions.assertTrue(entryOpt.isPresent());

        OffHeapFrameRingBuffer.FrameEntry entry = entryOpt.get();
        Assertions.assertEquals(0L, entry.sequenceNumber());
        Assertions.assertEquals(1000_000L, entry.timestampNs());
        Assertions.assertEquals(42L, entry.frameIndex());
        Assertions.assertEquals(12.5f, entry.surpriseSigma(), 1e-4f);
        Assertions.assertEquals(315.0f, entry.hotspotCenterX(), 1e-4f);
        Assertions.assertEquals(390.0f, entry.hotspotCenterY(), 1e-4f);
        Assertions.assertArrayEquals(payload, entry.payload());
    }

    @Test
    void testRetrospectiveNearestFrameLookup() {
        ringBuffer.appendFrame(100L, 10L, 5.0f, 100f, 100f, new byte[]{1});
        ringBuffer.appendFrame(200L, 20L, 6.0f, 100f, 100f, new byte[]{2});
        ringBuffer.appendFrame(300L, 30L, 7.0f, 100f, 100f, new byte[]{3});

        // Exact match
        Optional<OffHeapFrameRingBuffer.FrameEntry> exact = ringBuffer.getNearestFrame(20L);
        Assertions.assertTrue(exact.isPresent());
        Assertions.assertEquals(20L, exact.get().frameIndex());

        // Nearest match (e.g. query at frame 23 should resolve to frame 20)
        Optional<OffHeapFrameRingBuffer.FrameEntry> nearest = ringBuffer.getNearestFrame(23L);
        Assertions.assertTrue(nearest.isPresent());
        Assertions.assertEquals(20L, nearest.get().frameIndex());
    }

    @Test
    void testCircularWrapAround() {
        // Capacity is 5, insert 8 items
        for (int i = 0; i < 8; i++) {
            ringBuffer.appendFrame(i * 1000L, i * 10L, (float) i, 0f, 0f, new byte[]{(byte) i});
        }

        // Sequences 0, 1, 2 should have been overwritten and no longer retrievable
        Assertions.assertTrue(ringBuffer.getBySequence(0L).isEmpty());
        Assertions.assertTrue(ringBuffer.getBySequence(1L).isEmpty());
        Assertions.assertTrue(ringBuffer.getBySequence(2L).isEmpty());

        // Sequences 3 to 7 must be retrievable
        for (long seq = 3; seq <= 7; seq++) {
            Optional<OffHeapFrameRingBuffer.FrameEntry> entry = ringBuffer.getBySequence(seq);
            Assertions.assertTrue(entry.isPresent(), "Sequence " + seq + " should be valid in circular buffer");
            Assertions.assertEquals(seq * 10L, entry.get().frameIndex());
        }

        // Telemetry check
        OffHeapFrameRingBuffer.BufferTelemetry telemetry = ringBuffer.getTelemetry();
        Assertions.assertEquals(8L, telemetry.currentSequence());
        Assertions.assertEquals(5, telemetry.activeSlotsCount());
    }
}

