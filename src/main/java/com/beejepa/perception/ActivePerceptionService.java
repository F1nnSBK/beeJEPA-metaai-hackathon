package com.beejepa.perception;

import com.beejepa.event.AnomalyEvent;
import com.beejepa.event.EventService;
import com.beejepa.muse.SamGeometry;
import com.beejepa.muse.VisualCropRenderer;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autonomous Active Perception Service coordinating peripheral stream tracking,
 * saccadic target acquisition, and foveal zoom over the hive entrance flight
 * board.
 */
@Singleton
@Context
public class ActivePerceptionService {

    private static final Logger LOG = LoggerFactory.getLogger(ActivePerceptionService.class);
    private static final int DEFAULT_FOVEA_RADIUS = 60; // 120x120 px foveal zone

    private final OffHeapFrameRingBuffer ringBuffer;
    private final SimdLatentEvaluator simdEvaluator;
    private final EventService eventService;

    private final Map<String, SaccadicTarget> targetsCache = new ConcurrentHashMap<>();
    private final List<SaccadicTarget> trajectory = new ArrayList<>();

    public ActivePerceptionService(
            OffHeapFrameRingBuffer ringBuffer,
            SimdLatentEvaluator simdEvaluator,
            EventService eventService) {
        this.ringBuffer = ringBuffer;
        this.simdEvaluator = simdEvaluator;
        this.eventService = eventService;
    }

    @PostConstruct
    public void initialize() {
        LOG.info("🐝 Initializing Active Perception System: loading real events into FFM off-heap ring buffer...");
        refreshTrajectory();
    }

    /**
     * Synchronizes and calculates the sequential saccadic trajectory across real
     * events.
     */
    public synchronized List<SaccadicTarget> refreshTrajectory() {
        trajectory.clear();
        targetsCache.clear();

        List<Map<String, Object>> events = eventService.listEvents();
        int prevX = 315;
        int prevY = 390;

        for (Map<String, Object> ev : events) {
            String eventId = String.valueOf(ev.get("eventId"));
            long frameIndex = ev.get("frameIndex") instanceof Number n ? n.longValue() : 0L;
            double timestampSec = ev.get("timestampSec") instanceof Number n ? n.doubleValue() : 0.0;
            String formattedTime = String.valueOf(ev.getOrDefault("formattedTime", "00:00"));
            double surprise = ev.get("surpriseScoreL2") instanceof Number n ? n.doubleValue() : 0.0;
            String severity = String.valueOf(ev.getOrDefault("severity", "MEDIUM"));
            int boardBees = ev.get("boardBees") instanceof Number n ? n.intValue() : 24;

            AnomalyEvent.Hotspot hotspot = ev.get("hotspot") instanceof AnomalyEvent.Hotspot h ? h : null;
            double normCx = hotspot != null ? hotspot.normCenterX() : 0.58;
            double normCy = hotspot != null ? hotspot.normCenterY() : 0.40;

            int focusX = (int) Math.round(normCx * 540);
            int focusY = (int) Math.round(normCy * 960);

            // Calculate Euclidean saccadic jump distance from previous fixation point
            double dx = focusX - prevX;
            double dy = focusY - prevY;
            double jumpDistance = Math.round(Math.sqrt(dx * dx + dy * dy) * 100.0) / 100.0;
            prevX = focusX;
            prevY = focusY;

            int y1 = Math.max(0, focusY - DEFAULT_FOVEA_RADIUS);
            int x1 = Math.max(0, focusX - DEFAULT_FOVEA_RADIUS);
            int y2 = Math.min(SamGeometry.FRAME_SIZE, focusY + DEFAULT_FOVEA_RADIUS);
            int x2 = Math.min(SamGeometry.FRAME_SIZE, focusX + DEFAULT_FOVEA_RADIUS);

            // Ingest real keyframe into FFM off-heap ring buffer if available
            ingestFrameIntoRingBuffer(eventId, frameIndex, timestampSec, (float) surprise, focusX, focusY);

            // Estimate hotspot contained bees based on surprise and board counts
            int hotspotBees = surprise >= 14.0 ? 15 : (surprise >= 12.0 ? 11 : (surprise >= 10.0 ? 6 : 2));
            String classification = hotspotBees >= 2 ? "multi_bee_interaction_cluster" : "single_bee_inspection";

            SaccadicTarget target = new SaccadicTarget(
                    eventId,
                    frameIndex,
                    timestampSec,
                    formattedTime,
                    surprise,
                    severity,
                    focusX,
                    focusY,
                    DEFAULT_FOVEA_RADIUS,
                    List.of(y1, x1, y2, x2),
                    boardBees,
                    hotspotBees,
                    classification,
                    jumpDistance);

            trajectory.add(target);
            targetsCache.put(eventId, target);
        }

        LOG.info("🐝 Active Perception System ready: mapped {} saccadic targets across timeline.", trajectory.size());
        return Collections.unmodifiableList(trajectory);
    }

    private void ingestFrameIntoRingBuffer(
            String eventId,
            long frameIndex,
            double timestampSec,
            float surpriseSigma,
            float focusX,
            float focusY) {
        Optional<File> frameFile = eventService.getMediaFile(eventId, "peak_frame.jpg");
        if (frameFile.isPresent() && frameFile.get().exists()) {
            try {
                byte[] bytes = Files.readAllBytes(frameFile.get().toPath());
                long timestampNs = (long) (timestampSec * 1_000_000_000L);
                ringBuffer.appendFrame(timestampNs, frameIndex, surpriseSigma, focusX, focusY, bytes);
            } catch (IOException e) {
                LOG.warn("Failed to ingest keyframe into off-heap ring buffer for event {}: {}", eventId,
                        e.getMessage());
            }
        }
    }

    public List<SaccadicTarget> getSaccadicTrajectory() {
        if (trajectory.isEmpty()) {
            refreshTrajectory();
        }
        return Collections.unmodifiableList(trajectory);
    }

    public Optional<SaccadicTarget> getTarget(String eventId) {
        if (targetsCache.isEmpty()) {
            refreshTrajectory();
        }
        return Optional.ofNullable(targetsCache.get(eventId));
    }

    public OffHeapFrameRingBuffer.BufferTelemetry getRingBufferTelemetry() {
        return ringBuffer.getTelemetry();
    }

    public SimdLatentEvaluator getSimdEvaluator() {
        return simdEvaluator;
    }

    public OffHeapFrameRingBuffer getRingBuffer() {
        return ringBuffer;
    }
}
