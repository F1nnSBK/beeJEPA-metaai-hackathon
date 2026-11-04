package com.beejepa.perception;

import com.beejepa.event.AnomalyEvent;
import com.beejepa.event.EventService;
import com.beejepa.muse.MuseDiskCache;
import com.beejepa.muse.SamGeometry;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the saccadic fixation trajectory across measured event hotspots and
 * buffers peak frames off-heap.
 */
@Singleton
@Context
public class ActivePerceptionService {

    private static final Logger LOG = LoggerFactory.getLogger(ActivePerceptionService.class);
    private static final int PRELOAD_FRAMES = 4;

    private final OffHeapFrameRingBuffer ringBuffer;
    private final SimdLatentEvaluator simdEvaluator;
    private final EventService eventService;
    private final MuseDiskCache museDiskCache;

    public final Map<String, float[]> latentStore = new ConcurrentHashMap<>();
    private final Map<String, SaccadicTarget> targetsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> eventBufferSequences = new ConcurrentHashMap<>();
    private final List<SaccadicTarget> trajectory = new ArrayList<>();
    private volatile long trajectoryHotspotVersion = -1;

    public ActivePerceptionService(
            OffHeapFrameRingBuffer ringBuffer,
            SimdLatentEvaluator simdEvaluator,
            EventService eventService) {
        this(ringBuffer, simdEvaluator, eventService, new MuseDiskCache());
    }

    @Inject
    public ActivePerceptionService(
            OffHeapFrameRingBuffer ringBuffer,
            SimdLatentEvaluator simdEvaluator,
            EventService eventService,
            MuseDiskCache museDiskCache) {
        this.ringBuffer = ringBuffer;
        this.simdEvaluator = simdEvaluator;
        this.eventService = eventService;
        this.museDiskCache = museDiskCache;
    }

    @PostConstruct
    public void initialize() {
        LOG.info("Initializing Active Perception System");
        refreshTrajectory();
    }

    /**
     * Rebuilds the trajectory over all events that currently have a measured
     * hotspot.
     */
    public synchronized List<SaccadicTarget> refreshTrajectory() {
        long version = eventService.getHotspotVersion();
        trajectory.clear();
        targetsCache.clear();

        Integer prevX = null;
        Integer prevY = null;
        for (Map<String, Object> ev : eventService.listEvents()) {
            if (!(ev.get("hotspot") instanceof AnomalyEvent.Hotspot hotspot) || hotspot.boundingBoxPx() == null) {
                continue;
            }
            String eventId = String.valueOf(ev.get("eventId"));
            long frameIndex = ev.get("frameIndex") instanceof Number n ? n.longValue() : 0L;
            double timestampSec = ev.get("timestampSec") instanceof Number n ? n.doubleValue() : 0.0;
            String formattedTime = String.valueOf(ev.getOrDefault("formattedTime", ""));
            double score = ev.get("tokenDeltaScore") instanceof Number n ? n.doubleValue() : Double.NaN;
            String severity = String.valueOf(ev.getOrDefault("severity", ""));

            int[] box = hotspot.boundingBoxPx(); // [y1, x1, y2, x2]
            int focusX = (box[1] + box[3]) / 2;
            int focusY = (box[0] + box[2]) / 2;
            int radius = Math.max(box[2] - box[0], box[3] - box[1]) / 2;
            double jumpDistance = 0.0;
            if (prevX != null) {
                jumpDistance = Math.round(Math.hypot(focusX - prevX, focusY - prevY) * 100.0) / 100.0;
            }
            prevX = focusX;
            prevY = focusY;

            if (eventBufferSequences.size() < PRELOAD_FRAMES) {
                ingestFrameIntoRingBuffer(eventId, frameIndex, timestampSec, (float) score, focusX, focusY);
            }

            Optional<SamGeometry.EventSpatialSummary> sam = museDiskCache != null
                    ? museDiskCache.loadSpatialSummary(eventId)
                    : Optional.empty();
            SaccadicTarget target = new SaccadicTarget(
                    eventId,
                    frameIndex,
                    timestampSec,
                    formattedTime,
                    score,
                    severity,
                    focusX,
                    focusY,
                    radius,
                    List.of(box[0], box[1], box[2], box[3]),
                    sam.map(SamGeometry.EventSpatialSummary::landingBoardBeeCount).orElse(-1),
                    sam.map(SamGeometry.EventSpatialSummary::beesInsideHotspotCount).orElse(-1),
                    sam.map(SamGeometry.EventSpatialSummary::geometricPreClassification).orElse("not_segmented"),
                    jumpDistance);
            trajectory.add(target);
            targetsCache.put(eventId, target);
        }
        trajectoryHotspotVersion = version;
        LOG.info("Active Perception trajectory: {} fixation targets with measured hotspots", trajectory.size());
        return Collections.unmodifiableList(trajectory);
    }

    private void refreshIfHotspotsChanged() {
        if (eventService.getHotspotVersion() != trajectoryHotspotVersion) {
            refreshTrajectory();
        }
    }

    private void ingestFrameIntoRingBuffer(String eventId, long frameIndex, double timestampSec,
            float score, float focusX, float focusY) {
        if (eventBufferSequences.containsKey(eventId)) {
            return;
        }
        Optional<byte[]> frameBytesOpt = eventService.getOrExtractEventFrameBytes(eventId);
        if (frameBytesOpt.isPresent() && frameBytesOpt.get().length > 0) {
            byte[] rawBytes = frameBytesOpt.get();
            long timestampNs = (long) (timestampSec * 1_000_000_000L);
            long seq = ringBuffer.appendFrame(timestampNs, frameIndex, score, focusX, focusY, rawBytes);
            eventBufferSequences.put(eventId, seq);
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO
                        .read(new java.io.ByteArrayInputStream(rawBytes));
                if (img != null) {
                    latentStore.put(eventId, com.beejepa.event.HeatmapHotspotExtractor.extractBoardEnergyLatent64(img));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public Map<String, float[]> getLatentStore() {
        return latentStore;
    }

    /**
     * Returns the peak frame of an event from the FFM off-heap ring buffer,
     * ingesting it on demand.
     */
    public Optional<byte[]> getBufferedFrameBytes(String eventId) {
        if (eventId == null) {
            return Optional.empty();
        }
        if (!eventBufferSequences.containsKey(eventId)) {
            SaccadicTarget target = targetsCache.get(eventId);
            if (target != null) {
                ingestFrameIntoRingBuffer(target.eventId(), target.frameIndex(), target.timestampSec(),
                        (float) target.tokenDeltaScore(), target.focusCenterX(), target.focusCenterY());
            }
        }
        Long seq = eventBufferSequences.get(eventId);
        if (seq != null) {
            Optional<OffHeapFrameRingBuffer.FrameEntry> entry = ringBuffer.getBySequence(seq);
            if (entry.isPresent() && entry.get().payload() != null && entry.get().payload().length > 0) {
                return Optional.of(entry.get().payload());
            }
        }
        return Optional.empty();
    }

    public List<SaccadicTarget> getSaccadicTrajectory() {
        refreshIfHotspotsChanged();
        return Collections.unmodifiableList(trajectory);
    }

    public Optional<SaccadicTarget> getTarget(String eventId) {
        refreshIfHotspotsChanged();
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

    /**
     * Assembles the 5-tier pipeline role breakdown and measured telemetry for an
     * event.
     */
    public Map<String, Object> buildAblationPyramid(String requestedEventId) {
        refreshIfHotspotsChanged();
        Map<String, Object> event = null;
        if (requestedEventId != null && !requestedEventId.isBlank()) {
            event = eventService.getEvent(requestedEventId.trim()).orElse(null);
        }
        if (event == null) {
            List<Map<String, Object>> all = eventService.listEvents();
            event = all.isEmpty() ? null : all.getFirst();
        }
        Map<String, Object> timeline = eventService.getTimeline();
        List<Map<String, Object>> allEvents = eventService.listEvents();
        long withHotspot = allEvents.stream().filter(e -> e.get("hotspot") != null).count();

        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("datasetSource", timeline.get("source"));
        measured.put("timelineWindows",
                timeline.get("summary") instanceof Map<?, ?> s ? s.get("timeline_points_count") : null);
        measured.put("eventsTotal", allEvents.size());
        measured.put("eventsAboveTau",
                timeline.get("summary") instanceof Map<?, ?> s ? s.get("events_above_tau") : null);
        measured.put("eventsWithMeasuredHotspot", withHotspot);
        measured.put("decisionThresholdTau", timeline.get("decisionThreshold"));
        measured.put("decisionThresholdMethod", timeline.get("decisionThresholdMethod"));
        measured.put("ringBuffer", ringBuffer.getTelemetry());
        measured.put("vectorBitWidth", simdEvaluator != null ? simdEvaluator.vectorBitWidth() : null);

        String eventId = event != null ? String.valueOf(event.get("eventId")) : null;
        if (event != null) {
            measured.put("eventId", eventId);
            measured.put("recordingId", event.get("recordingId"));
            measured.put("timeInRecording", event.get("formattedTime"));
            measured.put("tokenDeltaScore", event.get("tokenDeltaScore"));
            measured.put("aboveThreshold", event.get("aboveThreshold"));
            measured.put("boardTokenPeaks", event.get("boardPeaks"));
            measured.put("hotspotStatus", event.get("hotspotStatus"));
            measured.put("activeBoardCells", event.get("activeCells"));
            if (event.get("hotspot") instanceof AnomalyEvent.Hotspot hs) {
                measured.put("hotspotGrid", List.of(hs.tubeletGridX(), hs.tubeletGridY()));
                measured.put("hotspotBoxPx", hs.boundingBoxPx());
            }
            Optional<SamGeometry.EventSpatialSummary> sam = museDiskCache != null
                    ? museDiskCache.loadSpatialSummary(eventId)
                    : Optional.empty();
            measured.put("samStatus", sam.isPresent() ? "SEGMENTED" : "NOT_SEGMENTED");
            sam.ifPresent(s -> {
                measured.put("samBoardBees", s.landingBoardBeeCount());
                measured.put("samBeesInsideHotspot", s.beesInsideHotspotCount());
                measured.put("samClassification", s.geometricPreClassification());
            });
        }

        List<Map<String, Object>> tiers = List.of(
                tier("TIER_0_RAW_STREAM", 0, "Raw 60 fps Stream",
                        "Tier 0 · Recorded entrance video",
                        "1080x960 side-by-side dataset video (camera | V-JEPA overlay)",
                        "What does the camera record?",
                        "Recorded 59.94 fps frames",
                        "Frames for every later tier",
                        "No visual input."),
                tier("TIER_1_VJEPA_WORLD_MODEL", 1, "V-JEPA 2.1 Token-Δ",
                        "Tier 1 · V-JEPA 2.1 token-delta trigger and measured hotspot",
                        "V-JEPA 2.1 ViT-B/16, 43x43 token grid",
                        "When and where does the scene change unexpectedly?",
                        "Token-delta score per ~1.07 s window",
                        "Peak events (score vs. tau = P99) and the board hotspot measured from the overlay",
                        "No temporal trigger and no spatial focus; every frame would need inspection."),
                tier("TIER_2_SAM31_SEGMENTATION", 2, "SAM 3.1 'bee'",
                        "Tier 2 · Meta SAM 3.1 bee segmentation on the peak frame",
                        "Meta SAM 3.1 (concept prompt 'bee')",
                        "Which bee instances are on the landing board?",
                        "Peak frame (688x688 crop)",
                        "Bee boxes on the landing board",
                        "V-JEPA tokens carry no object identity; bee counts would be unknown."),
                tier("TIER_3_IOA_GEOMETRIC_BRIDGE", 3, "IoA ≥ 0.25",
                        "Tier 3 · Containment of SAM bees in the V-JEPA hotspot",
                        "SamGeometry intersection-over-area",
                        "How many segmented bees lie inside the measured hotspot?",
                        "SAM bee boxes + measured hotspot box",
                        "Count of bees with IoA ≥ 0.25",
                        "No link between the V-JEPA trigger location and segmented instances."),
                tier("TIER_4_MUSE_SPARK_REASONING", 4, "Muse Spark Open",
                        "Tier 4 · Meta Muse Spark Open briefing",
                        "meta-models/muse-spark-open",
                        "What does this mean for the beekeeper?",
                        "Measured digest + hotspot crops",
                        "<REPORT> briefing + <SPOKEN> narration",
                        "Numbers only; no interpretation or spoken briefing."));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("measured", measured);
        result.put("tiers", tiers);
        return result;
    }

    private static Map<String, Object> tier(String id, int idx, String shortTitle, String name, String component,
            String question, String input, String output, String without) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("tierIndex", idx);
        t.put("name", name);
        t.put("shortTitle", shortTitle);
        t.put("modelOrComponent", component);
        t.put("roleQuestion", question);
        t.put("inputSignal", input);
        t.put("outputSignal", output);
        t.put("whatBreaksWithoutIt", without);
        return t;
    }
}
