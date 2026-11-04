package com.beejepa.event;

import com.beejepa.muse.MuseDiskCache;
import com.beejepa.muse.SamGeometry;
import com.beejepa.storage.StorageService;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides access to the sequence timeline, peak event catalog, hotspot
 * measurements, and media files.
 */
@Singleton
public class EventService {

    private static final Logger LOG = LoggerFactory.getLogger(EventService.class);
    private static final String HOTSPOT_KEY_PREFIX = "hotspot.overlay.v1:";
    private static final String HOTSPOT_NONE = "none";
    private static final String FRAME_KEY_SUFFIX = "@peak.v2";

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final MuseDiskCache museDiskCache;

    private volatile Catalog catalog;
    private final Map<String, Optional<HeatmapHotspotExtractor.MeasuredHotspot>> measuredHotspots = new ConcurrentHashMap<>();
    private final AtomicBoolean measurementStarted = new AtomicBoolean(false);
    private final AtomicLong hotspotVersion = new AtomicLong(0);
    private volatile Thread measurementThread;

    public EventService(StorageService storageService, ObjectMapper objectMapper) {
        this(storageService, objectMapper, new MuseDiskCache());
    }

    @Inject
    public EventService(StorageService storageService, ObjectMapper objectMapper, MuseDiskCache museDiskCache) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.museDiskCache = museDiskCache != null ? museDiskCache : new MuseDiskCache();
    }

    public MuseDiskCache getMuseDiskCache() {
        return museDiskCache;
    }

    record SequenceInfo(
            int index,
            String sequenceId,
            String recordingId,
            int sequenceIndexInRecording,
            String filename,
            double startSecAxis,
            double meanScore,
            double maxScore) {
    }


    record EventInfo(
            String eventId,
            String recordingId,
            String sequenceId,
            int sequenceAxisIndex,
            String sequenceClip,
            double timeSecAxis,
            double timeSecLocal,
            double recordingTimeSec,
            int peakFrame,
            double score,
            int boardPeaks,
            Integer grassPeaks,
            String submanifoldState,
            Double pixelDiff,
            Integer clipStartFrame) {
    }


    record TimelinePoint(
            String sequenceId,
            String recordingId,
            int sequenceAxisIndex,
            double timeSecAxis,
            double timeSecLocal,
            double score,
            int boardPeaks,
            String eventId) {
    }


    record Catalog(
            String source,
            List<SequenceInfo> sequences,
            List<EventInfo> events,
            Map<String, EventInfo> eventsById,
            List<TimelinePoint> timeline,
            double tau,
            double minScore,
            double maxScore) {
    }

    private Catalog getCatalog() {
        Catalog c = catalog;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (catalog != null) {
                return catalog;
            }
            CatalogLoader.RawCatalogData raw = storageService != null && storageService.getDataDirectory() != null
                    ? CatalogLoader.loadFromCsvDirectory(storageService.getDataDirectory())
                    : null;
            if (raw == null) {
                raw = CatalogLoader.loadFromManifestDirectories(getCandidateSampleDirectories(), objectMapper);
            }
            Catalog loaded = raw != null
                    ? buildCatalog(raw.source(), raw.sequences(), raw.events(), raw.timeline())
                    : new Catalog("none", List.of(), List.of(), Map.of(), List.of(), Double.NaN, Double.NaN, Double.NaN);
            catalog = loaded;
            loadPersistedHotspots(loaded);
            startHotspotMeasurement(loaded);
            return loaded;
        }
    }


    private static Catalog buildCatalog(
            String source,
            List<SequenceInfo> sequences,
            List<EventInfo> events,
            List<TimelinePoint> timeline) {
        double[] scores = timeline.stream().mapToDouble(TimelinePoint::score).sorted().toArray();
        double tau = scores.length > 0 ? CatalogStatistics.percentile(scores, 0.99) : Double.NaN;
        double min = scores.length > 0 ? scores[0] : Double.NaN;
        double max = scores.length > 0 ? scores[scores.length - 1] : Double.NaN;
        for (EventInfo e : events) {
            max = Double.isNaN(max) ? e.score() : Math.max(max, e.score());
        }
        Map<String, EventInfo> byId = new LinkedHashMap<>();
        for (EventInfo e : events) {
            byId.put(e.eventId(), e);
        }
        return new Catalog(
                source,
                List.copyOf(sequences),
                List.copyOf(events),
                Collections.unmodifiableMap(byId),
                List.copyOf(timeline),
                CatalogStatistics.round(tau, 4),
                CatalogStatistics.round(min, 4),
                CatalogStatistics.round(max, 4));
    }

    static double percentile(double[] sorted, double q) {
        return CatalogStatistics.percentile(sorted, q);
    }

    private static String formatMmSs(double totalSeconds) {
        int totalSec = Math.max(0, (int) Math.floor(totalSeconds));
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    static final class CsvTable {
        final Map<String, Integer> header = new HashMap<>();
        final List<String[]> rows = new ArrayList<>();

        static CsvTable read(Path path) throws IOException {
            CsvTable t = new CsvTable();
            List<String> lines = Files.readAllLines(path);
                if (lines.isEmpty()) {
                    return t;
                }
            String[] head = lines.getFirst().trim().split(",", -1);
            for (int i = 0; i < head.length; i++) {
                t.header.put(head[i].trim(), i);
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    t.rows.add(line.split(",", -1));
                }
            }
            return t;
        }

        String get(String[] row, String column) {
            Integer idx = header.get(column);
            if (idx == null) {
                throw new IllegalStateException("Missing CSV column: " + column);
            }
            return idx < row.length ? row[idx].trim() : "";
        }
    }

    private void loadPersistedHotspots(Catalog c) {
            if (museDiskCache.getStateRepository() == null) {
                return;
            }
        for (EventInfo e : c.events()) {
            try {
                museDiskCache.getStateRepository().get(HOTSPOT_KEY_PREFIX + e.eventId()).ifPresent(s -> {
                    if (HOTSPOT_NONE.equals(s.value())) {
                        measuredHotspots.put(e.eventId(), Optional.empty());
                    } else {
                        HeatmapHotspotExtractor.MeasuredHotspot.deserialize(s.value())
                                .ifPresent(h -> measuredHotspots.put(e.eventId(), Optional.of(h)));
                    }
                });
            } catch (Exception ex) {
                LOG.debug("Could not load persisted hotspot for {}: {}", e.eventId(), ex.getMessage());
            }
        }
        if (!measuredHotspots.isEmpty()) {
            hotspotVersion.incrementAndGet();
        }
    }

    private void startHotspotMeasurement(Catalog c) {
        if (c.events().isEmpty() || !measurementStarted.compareAndSet(false, true)) {
            return;
        }
        measurementThread = Thread.ofVirtual().name("hotspot-measurement").start(() -> {
            int measured = 0;
            String ffmpegBin = VideoFrameExtractor.resolveFfmpegBinary();
            for (EventInfo e : c.events()) {
                if (measuredHotspots.containsKey(e.eventId())) {
                    continue;
                }
                Optional<File> video = getSequenceFile(e.sequenceClip());
                if (video.isEmpty()) {
                    continue;
                }
                Optional<HeatmapHotspotExtractor.MeasuredHotspot> h = HeatmapHotspotExtractor
                        .measure(video.get().toPath(), e.timeSecLocal(), ffmpegBin);
                measuredHotspots.put(e.eventId(), h);
                persistHotspot(e.eventId(), h);
                hotspotVersion.incrementAndGet();
                measured++;
            }
            LOG.info("Hotspot measurement finished: {} newly measured, {} total known", measured, measuredHotspots.size());
        });
    }

    private void persistHotspot(String eventId, Optional<HeatmapHotspotExtractor.MeasuredHotspot> h) {
            if (museDiskCache.getStateRepository() == null) {
                return;
            }
        try {
            museDiskCache.getStateRepository().set(
                    HOTSPOT_KEY_PREFIX + eventId,
                    h.map(HeatmapHotspotExtractor.MeasuredHotspot::serialize).orElse(HOTSPOT_NONE));
        } catch (Exception ex) {
            LOG.warn("Failed to persist hotspot for {}: {}", eventId, ex.getMessage());
        }
    }

    /**
     * Returns a monotonic version counter incremented whenever a hotspot
     * measurement completes.
     */
    public long getHotspotVersion() {
        getCatalog();
        return hotspotVersion.get();
    }

    /**
     * Waits up to {@code timeoutMs} for background hotspot extraction to complete.
     */
    public boolean awaitHotspotMeasurement(long timeoutMs) throws InterruptedException {
        getCatalog();
        Thread t = measurementThread;
            if (t == null) {
                return true;
            }
        return t.join(java.time.Duration.ofMillis(timeoutMs));
    }

    private AnomalyEvent.Hotspot toHotspot(HeatmapHotspotExtractor.MeasuredHotspot h) {
        return new AnomalyEvent.Hotspot(h.gridX(), h.gridY(), h.normCenterX(), h.normCenterY(), h.boxPx());
    }

    public double getDecisionThreshold() {
        return getCatalog().tau();
    }

    public String getCatalogSource() {
        return getCatalog().source();
    }

    public boolean isKnownManifestEvent(String eventId) {
        return isSafePathSegment(eventId) && getCatalog().eventsById().containsKey(eventId);
    }

    public List<Map<String, Object>> listEvents() {
        Catalog c = getCatalog();
        List<Map<String, Object>> result = new ArrayList<>(c.events().size());
        for (EventInfo e : c.events()) {
            result.add(toItem(e, c));
        }
        return result;
    }

    private Map<String, Object> toItem(EventInfo e, Catalog c) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", e.eventId());
        item.put("recordingId", e.recordingId());
        item.put("sequenceId", e.sequenceId());
        item.put("sequenceIndex", e.sequenceAxisIndex());
        item.put("sequenceClip", e.sequenceClip());
        item.put("timestampSec", CatalogStatistics.round(e.timeSecAxis(), 3));
        item.put("timeSecLocal", CatalogStatistics.round(e.timeSecLocal(), 3));
        item.put("recordingTimeSec", CatalogStatistics.round(e.recordingTimeSec(), 3));
        item.put("formattedTime", formatMmSs(e.recordingTimeSec()));
        item.put("frameIndex", e.peakFrame());
        item.put("tokenDeltaScore", e.score());
        item.put("surpriseScoreL2", e.score());
        item.put("decisionThreshold", c.tau());
        item.put("aboveThreshold", !Double.isNaN(c.tau()) && e.score() >= c.tau());
        item.put("severity", !Double.isNaN(c.tau()) && e.score() >= c.tau() ? "ABOVE_TAU" : "BELOW_TAU");
        item.put("boardPeaks", e.boardPeaks());
        if (e.grassPeaks() != null) {
            item.put("grassPeaks", e.grassPeaks());
        }
        if (e.submanifoldState() != null) {
            item.put("submanifoldState", e.submanifoldState());
        }
        if (e.pixelDiff() != null) {
            item.put("pixelDiffBaseline", e.pixelDiff());
        }
        item.put("horizonDeltaFrames", 60);

        Optional<HeatmapHotspotExtractor.MeasuredHotspot> measured = measuredHotspots.get(e.eventId());
        if (measured == null) {
            item.put("hotspot", null);
            item.put("hotspotStatus", "PENDING");
        } else if (measured.isEmpty()) {
            item.put("hotspot", null);
            item.put("hotspotStatus", "NO_BOARD_OVERLAY");
        } else {
            item.put("hotspot", toHotspot(measured.get()));
            item.put("hotspotStatus", "MEASURED");
            item.put("activeCells", measured.get().activeBoardCells());
        }

        Optional<SamGeometry.EventSpatialSummary> spatialOpt = museDiskCache.loadSpatialSummary(e.eventId());
        if (spatialOpt.isPresent()) {
            SamGeometry.EventSpatialSummary sp = spatialOpt.get();
            item.put("isCached", true);
            item.put("hasSamSpatial", true);
            item.put("boardBees", sp.landingBoardBeeCount());
            item.put("hotspotBeesCount", sp.beesInsideHotspotCount());
            Map<String, Object> samSpatialMap = new LinkedHashMap<>();
            samSpatialMap.put("landingBoardBeeCount", sp.landingBoardBeeCount());
            samSpatialMap.put("beesInsideHotspotCount", sp.beesInsideHotspotCount());
            samSpatialMap.put("geometricPreClassification", sp.geometricPreClassification());
            samSpatialMap.put("boardBees", sp.boardBees() != null ? sp.boardBees() : List.of());
            samSpatialMap.put("containedBees", sp.containedBees() != null ? sp.containedBees() : List.of());
            samSpatialMap.put("cropBase64", sp.cropBase64() != null ? sp.cropBase64() : "");
            item.put("samSpatial", samSpatialMap);
        } else {
            item.put("isCached", false);
            item.put("hasSamSpatial", false);
        }
        item.put("hasRawClip", findEventClipPath(e.eventId()) != null);
        item.put("hasComparisonClip", false);
        item.put("hasPeakFrame", true);
        return item;
    }

    public Optional<Map<String, Object>> getEvent(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return Optional.empty();
        }
        Catalog c = getCatalog();
        EventInfo e = c.eventsById().get(eventId);
        if (e == null) {
            return Optional.empty();
        }
        Map<String, Object> item = toItem(e, c);
        Map<String, Object> map = new HashMap<>(item);
        map.put("event", new AnomalyEvent(
                e.eventId(), e.sequenceId(), e.timeSecAxis(), e.peakFrame(), e.score(),
                e.pixelDiff() != null ? e.pixelDiff() : Double.NaN, 60,
                (AnomalyEvent.Hotspot) item.get("hotspot")));
        map.put("cached", item.get("isCached"));
        return Optional.of(map);
    }

    public Map<String, Object> getTimeline() {
        Catalog c = getCatalog();
        List<Map<String, Object>> points = new ArrayList<>(c.timeline().size());
        for (TimelinePoint p : c.timeline()) {
            Map<String, Object> m = new HashMap<>();
            m.put("timestampSec", CatalogStatistics.round(p.timeSecAxis(), 3));
            m.put("timeSecLocal", CatalogStatistics.round(p.timeSecLocal(), 3));
            m.put("sequenceId", p.sequenceId());
            m.put("recordingId", p.recordingId());
            m.put("sequenceIndex", p.sequenceAxisIndex());
            m.put("tokenDeltaScore", p.score());
            m.put("surpriseScoreL2", p.score());
            m.put("boardPeaks", p.boardPeaks());
            m.put("eventId", p.eventId());
            m.put("isEvent", p.eventId() != null);
            points.add(m);
        }
        List<Map<String, Object>> seqs = new ArrayList<>(c.sequences().size());
        for (SequenceInfo s : c.sequences()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.index());
            m.put("sequence_id", s.sequenceId());
            m.put("recording_id", s.recordingId());
            m.put("filename", s.filename());
            m.put("start_sec_global", s.startSecAxis());
            m.put("end_sec_global", s.startSecAxis() + CatalogLoader.SEQUENCE_SECONDS);
            m.put("recording_start_sec", s.sequenceIndexInRecording() * CatalogLoader.SEQUENCE_SECONDS);
            m.put("mean_surprise", s.meanScore());
            m.put("max_surprise", s.maxScore());
            seqs.add(m);
        }
        double total = c.sequences().size() * CatalogLoader.SEQUENCE_SECONDS;
        Map<String, Object> response = new HashMap<>();
        response.put("source", c.source());
        response.put("scoreName", "token_delta_score");
        response.put("timeline", points);
        response.put("sequences", seqs);
        response.put("totalDurationSec", total);
        response.put("formattedDuration", formatMmSs(total));
        response.put("decisionThreshold", c.tau());
        response.put("decisionThresholdMethod", "P99 of all timeline windows");
        response.put("minSurprise", c.minScore());
        response.put("maxSurprise", c.maxScore());
        response.put("summary", Map.of(
                "sequences_count", c.sequences().size(),
                "events_count", c.events().size(),
                "timeline_points_count", c.timeline().size(),
                "events_above_tau", c.events().stream().filter(e -> e.score() >= c.tau()).count()));
        return response;
    }

    private static boolean isSafePathSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        if (segment.contains("..") || segment.contains("/") || segment.contains("\\") || segment.indexOf('\0') >= 0) {
            return false;
        }
        return !segment.equals(".");
    }

    private List<Path> getCandidateSampleDirectories() {
        List<Path> candidates = new ArrayList<>(2);
        if (storageService != null && storageService.getDataDirectory() != null) {
            candidates.add(storageService.getDataDirectory().resolve("hackathon_sample").toAbsolutePath().normalize());
        }
        Path localData = Path.of("data", "hackathon_sample").toAbsolutePath().normalize();
        if (!candidates.contains(localData)) {
            candidates.add(localData);
        }
        return candidates;
    }

    public Optional<File> findExactEventFrameFile(String eventId) {
        return Optional.empty();
    }

    /** Extracts and caches the JPEG peak frame for an event. */
    public synchronized Optional<byte[]> getOrExtractEventFrameBytes(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return Optional.empty();
        }
        String cacheKey = eventId + FRAME_KEY_SUFFIX;
        Optional<byte[]> cached = museDiskCache.getFrameBytes(cacheKey);
        if (cached.isPresent() && cached.get().length > 0) {
            return cached;
        }
        EventInfo e = getCatalog().eventsById().get(eventId);
        if (e == null) {
            return Optional.empty();
        }
        Path video = null;
        double offset = 0.0;
        Optional<File> seq = getSequenceFile(e.sequenceClip());
        if (seq.isPresent()) {
            video = seq.get().toPath();
            offset = e.timeSecLocal();
        } else {
            Path clip = findEventClipPath(eventId);
            if (clip != null && e.clipStartFrame() != null) {
                video = clip;
                offset = Math.max(0, e.peakFrame() - e.clipStartFrame()) / CatalogLoader.FPS;
            }
        }
        if (video == null) {
            return Optional.empty();
        }
        Optional<byte[]> extracted = VideoFrameExtractor.extractJpegBytes(
                video, offset, VideoFrameExtractor.resolveFfmpegBinary());
        extracted.ifPresent(bytes -> museDiskCache.saveFrameBytes(cacheKey, bytes));
        return extracted;
    }

    private Path findEventClipPath(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return null;
        }
        if (storageService != null && storageService.getDataDirectory() != null) {
            Path root = storageService.getDataDirectory().toAbsolutePath().normalize();
            Path ssdPeak = root.resolve("events").resolve("peaks").resolve(eventId + ".mp4").normalize();
            if (ssdPeak.startsWith(root) && Files.isRegularFile(ssdPeak)) {
                return ssdPeak;
            }
        }
        for (Path base : getCandidateSampleDirectories()) {
            Path sampleClip = base.resolve("event_clips").resolve(eventId + ".mp4").normalize();
            if (sampleClip.startsWith(base) && Files.isRegularFile(sampleClip)) {
                return sampleClip;
            }
        }
        return null;
    }

    public Optional<File> getMediaFile(String eventId, String filename) {
        if (!isSafePathSegment(eventId) || !isSafePathSegment(filename)) {
            return Optional.empty();
        }
        if ("clip_raw.mp4".equals(filename) || filename.equals(eventId + ".mp4")) {
            Path eventClip = findEventClipPath(eventId);
            return eventClip != null ? Optional.of(eventClip.toFile()) : Optional.empty();
        }
        return Optional.empty();
    }

    public Optional<File> getSequenceFile(String filename) {
        if (!isSafePathSegment(filename)) {
            return Optional.empty();
        }
        String safeName = Path.of(filename).getFileName().toString();
        if (storageService != null && storageService.getDataDirectory() != null) {
            Path root = storageService.getDataDirectory().toAbsolutePath().normalize();
            Path stitched = root.resolve("stitched_sequences").resolve(safeName).normalize();
            if (stitched.startsWith(root) && Files.isRegularFile(stitched)) {
                return Optional.of(stitched.toFile());
            }
        }
        for (Path base : getCandidateSampleDirectories()) {
            Path seqClip = base.resolve("sequences").resolve(safeName).normalize();
            if (seqClip.startsWith(base) && Files.isRegularFile(seqClip)) {
                return Optional.of(seqClip.toFile());
            }
        }
        return Optional.empty();
    }

    /** Returns cached SAM 3.1 segmentation status and counts for an event. */
    public Map<String, Object> analyzeEvent(String eventId, boolean forceRefresh) {
        if (!isSafePathSegment(eventId)) {
            return Map.of("error", "Invalid eventId", "cached", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("analyzedAt", java.time.Instant.now().toString());
        Map<String, Object> samResult = new LinkedHashMap<>();
        samResult.put("model", "meta/sam-3.1");
        Optional<SamGeometry.EventSpatialSummary> sp = forceRefresh
                ? Optional.empty()
                : museDiskCache.loadSpatialSummary(eventId);
        if (sp.isPresent()) {
            result.put("cached", true);
            samResult.put("status", "SUCCESS");
            samResult.put("instanceCount", sp.get().landingBoardBeeCount());
            samResult.put("hotspotBeesCount", sp.get().beesInsideHotspotCount());
            samResult.put("geometricPreClassification", sp.get().geometricPreClassification());
        } else {
            result.put("cached", false);
            samResult.put("status", "NOT_CACHED");
        }
        result.put("sam", samResult);
        return result;
    }
}
