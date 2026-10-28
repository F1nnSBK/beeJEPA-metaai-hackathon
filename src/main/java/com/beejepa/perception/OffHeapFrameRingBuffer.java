package com.beejepa.perception;

import io.micronaut.context.annotation.Context;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance off-heap circular ring buffer for video frames and V-JEPA latent metadata
 * using the Java 25 Foreign Function & Memory (FFM) API.
 * Eliminates garbage collection pauses and supports retrospective retrieval of historical frames
 * during cloud API inference latencies.
 */
@Singleton
@Context
public final class OffHeapFrameRingBuffer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OffHeapFrameRingBuffer.class);

    public static final int DEFAULT_CAPACITY = 600; // 10 seconds of 60 fps frames
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024; // 1 MB per frame slot

    // Slot Header Layout (32 bytes total)
    private static final long OFFSET_TIMESTAMP_NS = 0;       // JAVA_LONG (8 bytes)
    private static final long OFFSET_FRAME_INDEX = 8;        // JAVA_LONG (8 bytes)
    private static final long OFFSET_SURPRISE_SIGMA = 16;    // JAVA_FLOAT (4 bytes)
    private static final long OFFSET_HOTSPOT_CX = 20;        // JAVA_FLOAT (4 bytes)
    private static final long OFFSET_HOTSPOT_CY = 24;        // JAVA_FLOAT (4 bytes)
    private static final long OFFSET_PAYLOAD_SIZE = 28;      // JAVA_INT (4 bytes)
    public static final long HEADER_SIZE_BYTES = 32;

    public static final long SLOT_TOTAL_BYTES = HEADER_SIZE_BYTES + MAX_PAYLOAD_BYTES;

    private final int capacity;
    private final long totalAllocatedBytes;
    private final Arena arena;
    private final MemorySegment bufferSegment;
    private final AtomicLong sequenceHead = new AtomicLong(0);

    @Serdeable
    public record FrameEntry(
            long sequenceNumber,
            long timestampNs,
            long frameIndex,
            float surpriseSigma,
            float hotspotCenterX,
            float hotspotCenterY,
            byte[] payload
    ) {}

    @Serdeable
    public record BufferTelemetry(
            int capacity,
            long currentSequence,
            int activeSlotsCount,
            long totalAllocatedBytes,
            long memoryPerSlotBytes,
            boolean isOffHeap,
            long droppedFrames
    ) {}

    public OffHeapFrameRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public OffHeapFrameRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Ring buffer capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.totalAllocatedBytes = (long) capacity * SLOT_TOTAL_BYTES;
        this.arena = Arena.ofShared();
        this.bufferSegment = arena.allocate(totalAllocatedBytes, 64);

        LOG.info("🐝 Initialized FFM Off-Heap Ring Buffer: capacity={} slots, allocatedBytes={} ({} MB)",
                capacity, totalAllocatedBytes, totalAllocatedBytes / (1024 * 1024));
    }

    /**
     * Appends a frame and its V-JEPA latent metadata to the circular off-heap buffer.
     *
     * @param timestampNs Event timestamp in nanoseconds
     * @param frameIndex Monotonic or sequence frame index
     * @param surpriseSigma V-JEPA predictor surprise score in sigmas
     * @param hotspotCenterX Normalized or pixel hotspot X coordinate
     * @param hotspotCenterY Normalized or pixel hotspot Y coordinate
     * @param frameBytes Raw compressed image payload (JPEG/PNG)
     * @return Monotonic sequence number assigned to this frame
     */
    public long appendFrame(
            long timestampNs,
            long frameIndex,
            float surpriseSigma,
            float hotspotCenterX,
            float hotspotCenterY,
            byte[] frameBytes
    ) {
        Objects.requireNonNull(frameBytes, "Frame payload must not be null");
        if (frameBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "Frame payload size (" + frameBytes.length + " bytes) exceeds maximum slot payload ("
                            + MAX_PAYLOAD_BYTES + " bytes)"
            );
        }

        long seq = sequenceHead.getAndIncrement();
        int slotIndex = (int) (seq % capacity);
        long slotOffset = slotIndex * SLOT_TOTAL_BYTES;

        // Write slot header directly into off-heap memory
        bufferSegment.set(ValueLayout.JAVA_LONG, slotOffset + OFFSET_TIMESTAMP_NS, timestampNs);
        bufferSegment.set(ValueLayout.JAVA_LONG, slotOffset + OFFSET_FRAME_INDEX, frameIndex);
        bufferSegment.set(ValueLayout.JAVA_FLOAT, slotOffset + OFFSET_SURPRISE_SIGMA, surpriseSigma);
        bufferSegment.set(ValueLayout.JAVA_FLOAT, slotOffset + OFFSET_HOTSPOT_CX, hotspotCenterX);
        bufferSegment.set(ValueLayout.JAVA_FLOAT, slotOffset + OFFSET_HOTSPOT_CY, hotspotCenterY);
        bufferSegment.set(ValueLayout.JAVA_INT, slotOffset + OFFSET_PAYLOAD_SIZE, frameBytes.length);

        // Copy raw frame bytes off-heap
        if (frameBytes.length > 0) {
            MemorySegment.copy(
                    MemorySegment.ofArray(frameBytes),
                    0,
                    bufferSegment,
                    slotOffset + HEADER_SIZE_BYTES,
                    frameBytes.length
            );
        }

        return seq;
    }

    /**
     * Retrieves a frame by exact sequence number if it hasn't been overwritten yet.
     */
    public Optional<FrameEntry> getBySequence(long sequence) {
        long currentHead = sequenceHead.get();
        long oldestRetrievable = Math.max(0, currentHead - capacity);

        if (sequence < oldestRetrievable || sequence >= currentHead) {
            return Optional.empty();
        }

        int slotIndex = (int) (sequence % capacity);
        return Optional.of(readSlot(sequence, slotIndex));
    }

    /**
     * Retrospectively retrieves the frame closest to a target frame index within the buffered window.
     */
    public Optional<FrameEntry> getNearestFrame(long targetFrameIndex) {
        long currentHead = sequenceHead.get();
        if (currentHead == 0) {
            return Optional.empty();
        }

        long oldestRetrievable = Math.max(0, currentHead - capacity);
        FrameEntry closestEntry = null;
        long closestDiff = Long.MAX_VALUE;

        for (long seq = oldestRetrievable; seq < currentHead; seq++) {
            int slotIndex = (int) (seq % capacity);
            long slotOffset = slotIndex * SLOT_TOTAL_BYTES;
            long fIdx = bufferSegment.get(ValueLayout.JAVA_LONG, slotOffset + OFFSET_FRAME_INDEX);
            long diff = Math.abs(fIdx - targetFrameIndex);

            if (diff < closestDiff) {
                closestDiff = diff;
                closestEntry = readSlot(seq, slotIndex);
                if (diff == 0) {
                    break;
                }
            }
        }

        return Optional.ofNullable(closestEntry);
    }

    /**
     * Retrieves the latest frame recorded in the buffer.
     */
    public Optional<FrameEntry> getLatest() {
        long currentHead = sequenceHead.get();
        if (currentHead == 0) {
            return Optional.empty();
        }
        long latestSeq = currentHead - 1;
        int slotIndex = (int) (latestSeq % capacity);
        return Optional.of(readSlot(latestSeq, slotIndex));
    }

    private FrameEntry readSlot(long sequence, int slotIndex) {
        long slotOffset = slotIndex * SLOT_TOTAL_BYTES;

        long tsNs = bufferSegment.get(ValueLayout.JAVA_LONG, slotOffset + OFFSET_TIMESTAMP_NS);
        long fIdx = bufferSegment.get(ValueLayout.JAVA_LONG, slotOffset + OFFSET_FRAME_INDEX);
        float sigma = bufferSegment.get(ValueLayout.JAVA_FLOAT, slotOffset + OFFSET_SURPRISE_SIGMA);
        float cx = bufferSegment.get(ValueLayout.JAVA_FLOAT, slotOffset + OFFSET_HOTSPOT_CX);
        float cy = bufferSegment.get(ValueLayout.JAVA_FLOAT, slotOffset + OFFSET_HOTSPOT_CY);
        int payloadLen = bufferSegment.get(ValueLayout.JAVA_INT, slotOffset + OFFSET_PAYLOAD_SIZE);

        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            MemorySegment.copy(
                    bufferSegment,
                    slotOffset + HEADER_SIZE_BYTES,
                    MemorySegment.ofArray(payload),
                    0,
                    payloadLen
            );
        }

        return new FrameEntry(sequence, tsNs, fIdx, sigma, cx, cy, payload);
    }

    /**
     * Returns real-time telemetry metrics for edge monitoring.
     */
    public BufferTelemetry getTelemetry() {
        long currentHead = sequenceHead.get();
        int activeCount = (int) Math.min(currentHead, capacity);

        return new BufferTelemetry(
                capacity,
                currentHead,
                activeCount,
                totalAllocatedBytes,
                SLOT_TOTAL_BYTES,
                true,
                0 // Zero dropped frames architecture
        );
    }

    @Override
    @PreDestroy
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
            LOG.info("🐝 FFM Off-Heap Ring Buffer closed and native memory freed.");
        }
    }
}
