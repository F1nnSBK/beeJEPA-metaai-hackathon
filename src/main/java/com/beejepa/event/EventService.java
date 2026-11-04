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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads the BeeJEPA event catalog strictly from measured dataset files.
 *
 * <p>
 * Primary source (Raspberry Pi SSD): {@code metadata/sequence_runs.csv},
 * {@code metadata/event_catalog.csv}, {@code metadata/timeline_points.csv},
 * {@code stitched_sequences/*.mp4} and {@code events/peaks/*.mp4}.
 * Fallback (CI / local sample): {@code hackathon_sample/manifest.json}, of
 * which only the raw
 * measurements (ids, times, frames, token-delta scores, board token peaks) are
 * used.
 *
 * <p>
 * Nothing in this class is invented: the score is the dataset's token-delta
 * score, the decision
 * threshold tau is the 99th percentile of all timeline windows, and hotspots
 * are measured from the
 * rendered V-JEPA overlay of the peak frame ({@link HeatmapHotspotExtractor}).
 * Events whose hotspot
 * has not been measured (yet) carry {@code hotspot = null}.
 */
@Singleton
public class EventService {

    private static final Logger LOG = LoggerFactory.getLogger(EventService.class);
    private static final double FPS = 59.94;
    private static final double SEQUENCE_SECONDS = 30.0;
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

    // ------------------------------------------------------------------ catalog
    // model

    record SequenceInfo(int index, String sequenceId, String recordingId, int sequenceIndexInRecording,
            String filename, double startSecAxis, double meanScore, double maxScore) {
    }

    record EventInfo(String eventId, String recordingId, String sequenceId, int sequenceAxisIndex,
            String sequenceClip, double timeSecAxis, double timeSecLocal, double recordingTimeSec,
            int peakFrame, double score, int boardPeaks, Integer grassPeaks, String submanifoldState,
            Double pixelDiff, Integer clipStartFrame) {
    }

    record TimelinePoint(String sequenceId, String recordingId, int sequenceAxisIndex, double timeSecAxis,
            double timeSecLocal, double score, int boardPeaks, String eventId) {
    }

    record Catalog(String source, List<SequenceInfo> sequences, List<EventInfo> events,
            Map<String, EventInfo> eventsById, List<TimelinePoint> timeline,
            double tau, double minScore, double maxScore) {
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
            Catalog loaded = loadFullSsdCatalog();
            if (loaded == null) {
                loaded = loadSampleManifestCatalog();
            }
            if (loaded == null) {
                loaded = new Catalog("none", List.of(), List.of(), Map.of(), List.of(), Double.NaN, Double.NaN,
                        Double.NaN);
            }
            catalog = loaded;
            loadPersistedHotspots(loaded);
            startHotspotMeasurement(loaded);
            return loaded;
        }
    }

    private Catalog loadFullSsdCatalog() {
        if (storageService == null || storageService.getDataDirectory() == null) {
            return null;
        }
        Path root = storageService.getDataDirectory().toAbsolutePath().normalize();
        Path metaDir = root.resolve("metadata");
        Path seqCsv = metaDir.resolve("sequence_runs.csv");
        Path evtCsv = metaDir.resolve("event_catalog.csv");
        Path tlCsv = metaDir.resolve("timeline_points.csv");
        if (!Files.isRegularFile(seqCsv) || !Files.isRegularFile(evtCsv) || !Files.isRegularFile(tlCsv)
                || !Files.isDirectory(root.resolve("stitched_sequences"))) {
            return null;
        }
        try {
            CsvTable seqT = CsvTable.read(seqCsv);
            CsvTable evtT = CsvTable.read(evtCsv);
            CsvTable tlT = CsvTable.read(tlCsv);

            // Sequences in chronological dataset order: recording id, then sequence index.
            List<String[]> seqRows = new ArrayList<>(seqT.rows);
            seqRows.sort(Comparator.comparing((String[] r) -> seqT.get(r, "recording_id"))
                    .thenComparingInt(r -> Integer.parseInt(seqT.get(r, "sequence_index"))));
            List<SequenceInfo> sequences = new ArrayList<>(seqRows.size());
            Map<String, SequenceInfo> seqById = new HashMap<>();
            for (String[] r : seqRows) {
                int axisIdx = sequences.size();
                String seqId = seqT.get(r, "sequence_id");
                String filename = Path.of(seqT.get(r, "stitched_video_path")).getFileName().toString();
                SequenceInfo s = new SequenceInfo(axisIdx, seqId, seqT.get(r, "recording_id"),
                        Integer.parseInt(seqT.get(r, "sequence_index")), filename, axisIdx * SEQUENCE_SECONDS,
                        Double.parseDouble(seqT.get(r, "mean_surprise")),
                        Double.parseDouble(seqT.get(r, "max_surprise")));
                sequences.add(s);
                seqById.put(seqId, s);
            }

            // Timeline windows: always the token-delta score column (surprise_score_l2
            // mixes in predictor scores).
            List<TimelinePoint> timeline = new ArrayList<>(tlT.rows.size());
            Map<String, Double> eventWindowScore = new HashMap<>();
            for (String[] r : tlT.rows) {
                SequenceInfo s = seqById.get(tlT.get(r, "sequence_id"));
                if (s == null)
                    continue;
                double local = Double.parseDouble(tlT.get(r, "timestamp_sec"));
                double score = Double.parseDouble(tlT.get(r, "token_delta_score"));
                String evId = tlT.get(r, "event_id");
                timeline.add(new TimelinePoint(s.sequenceId(), s.recordingId(), s.index(), s.startSecAxis() + local,
                        local, score, Integer.parseInt(tlT.get(r, "board_peaks_9x9")), evId.isBlank() ? null : evId));
                if (!evId.isBlank()) {
                    eventWindowScore.put(evId, score);
                }
            }
            timeline.sort(Comparator.comparingDouble(TimelinePoint::timeSecAxis));

            List<EventInfo> events = new ArrayList<>(evtT.rows.size());
            for (String[] r : evtT.rows) {
                String evId = evtT.get(r, "event_id");
                SequenceInfo s = seqById.get(evtT.get(r, "sequence_id"));
                if (s == null)
                    continue;
                int peak = Integer.parseInt(evtT.get(r, "peak_frame"));
                double local = peak / FPS;
                double score = eventWindowScore.getOrDefault(evId,
                        Double.parseDouble(evtT.get(r, "surprise_score_l2")));
                events.add(new EventInfo(evId, s.recordingId(), s.sequenceId(), s.index(), s.filename(),
                        s.startSecAxis() + local, local, s.sequenceIndexInRecording() * SEQUENCE_SECONDS + local,
                        peak, score, Integer.parseInt(evtT.get(r, "board_peaks_16x16")),
                        Integer.parseInt(evtT.get(r, "grass_peaks")), evtT.get(r, "submanifold_state"),
                        Double.parseDouble(evtT.get(r, "pixel_diff_baseline")),
                        Integer.parseInt(evtT.get(r, "start_frame"))));
            }
            events.sort(Comparator.comparingDouble(EventInfo::timeSecAxis));
            LOG.info("Loaded SSD dataset catalog: {} sequences, {} events, {} timeline windows",
                    sequences.size(), events.size(), timeline.size());
            return buildCatalog("ssd_metadata_csv", sequences, events, timeline);
        } catch (Exception e) {
            LOG.error("Failed to parse SSD metadata catalog in {}: {}", metaDir, e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Catalog loadSampleManifestCatalog() {
        for (Path dir : getCandidateSampleDirectories()) {
            Path manifestFile = dir.resolve("manifest.json");
            if (!Files.isRegularFile(manifestFile))
                continue;
            try {
                Map<String, Object> m = objectMapper.readValue(Files.readAllBytes(manifestFile), Map.class);
                List<Map<String, Object>> rawSeqs = (List<Map<String, Object>>) m.getOrDefault("sequences", List.of());
                List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) m.getOrDefault("events", List.of());
                List<Map<String, Object>> rawTimeline = (List<Map<String, Object>>) m.getOrDefault("timeline",
                        List.of());

                List<SequenceInfo> sequences = new ArrayList<>();
                Map<String, SequenceInfo> seqById = new HashMap<>();
                for (Map<String, Object> rs : rawSeqs) {
                    String seqId = String.valueOf(rs.get("sequence_id"));
                    int axisIdx = num(rs.get("index")).intValue();
                    String rec = seqId.contains("_") ? seqId.substring(0, seqId.indexOf('_')) : seqId;
                    int seqIdxInRec = Integer.parseInt(seqId.replaceAll(".*_seq", ""));
                    SequenceInfo s = new SequenceInfo(axisIdx, seqId, rec, seqIdxInRec,
                            String.valueOf(rs.get("filename")),
                            num(rs.get("start_sec_global")).doubleValue(),
                            num(rs.get("mean_surprise")).doubleValue(), num(rs.get("max_surprise")).doubleValue());
                    sequences.add(s);
                    seqById.put(seqId, s);
                }
                sequences.sort(Comparator.comparingInt(SequenceInfo::index));

                List<TimelinePoint> timeline = new ArrayList<>();
                for (Map<String, Object> p : rawTimeline) {
                    double t = num(p.get("time_sec")).doubleValue();
                    int axisIdx = (int) Math.floor(t / SEQUENCE_SECONDS);
                    SequenceInfo s = axisIdx >= 0 && axisIdx < sequences.size() ? sequences.get(axisIdx) : null;
                    Object ev = p.get("event_id");
                    timeline.add(new TimelinePoint(s != null ? s.sequenceId() : null,
                            s != null ? s.recordingId() : null,
                            axisIdx, t, t - axisIdx * SEQUENCE_SECONDS, num(p.get("surprise_score")).doubleValue(),
                            num(p.get("board_bees")).intValue(),
                            ev == null || String.valueOf(ev).isBlank() ? null : String.valueOf(ev)));
                }
                timeline.sort(Comparator.comparingDouble(TimelinePoint::timeSecAxis));

                List<EventInfo> events = new ArrayList<>();
                for (Map<String, Object> e : rawEvents) {
                    SequenceInfo s = seqById.get(String.valueOf(e.get("sequence_id")));
                    if (s == null)
                        continue;
                    double local = num(e.get("time_sec_local")).doubleValue();
                    events.add(
                            new EventInfo(String.valueOf(e.get("event_id")), s.recordingId(), s.sequenceId(), s.index(),
                                    s.filename(), num(e.get("time_sec_global")).doubleValue(), local,
                                    s.sequenceIndexInRecording() * SEQUENCE_SECONDS + local,
                                    num(e.get("frame_approx")).intValue(), num(e.get("surprise_score")).doubleValue(),
                                    num(e.get("board_bees")).intValue(), null, null, null, null));
                }
                events.sort(Comparator.comparingDouble(EventInfo::timeSecAxis));
                LOG.info("Loaded sample manifest catalog from {}: {} sequences, {} events", manifestFile,
                        sequences.size(), events.size());
                return buildCatalog("hackathon_sample_manifest", sequences, events, timeline);
            } catch (Exception e) {
                LOG.error("Failed to read sample manifest {}: {}", manifestFile, e.toString());
            }
        }
        return null;
    }

    private static Catalog buildCatalog(String source, List<SequenceInfo> sequences, List<EventInfo> events,
            List<TimelinePoint> timeline) {
        double[] scores = timeline.stream().mapToDouble(TimelinePoint::score).sorted().toArray();
        double tau = scores.length > 0 ? percentile(scores, 0.99) : Double.NaN;
        double min = scores.length > 0 ? scores[0] : Double.NaN;
        double max = scores.length > 0 ? scores[scores.length - 1] : Double.NaN;
        for (EventInfo e : events) {
            max = Double.isNaN(max) ? e.score() : Math.max(max, e.score());
        }
        Map<String, EventInfo> byId = new LinkedHashMap<>();
        for (EventInfo e : events) {
            byId.put(e.eventId(), e);
        }
        return new Catalog(source, List.copyOf(sequences), List.copyOf(events), Collections.unmodifiableMap(byId),
                List.copyOf(timeline), round(tau, 4), round(min, 4), round(max, 4));
    }

    /** Linear-interpolated percentile on a sorted array. */
    static double percentile(double[] sorted, double q) {
        if (sorted.length == 1)
            return sorted[0];
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    private static Number num(Object o) {
        if (o instanceof Number n)
            return n;
        if (o == null)
            return 0;
        return Double.parseDouble(String.valueOf(o));
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v))
            return v;
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static String formatMmSs(double totalSeconds) {
        int totalSec = Math.max(0, (int) Math.floor(totalSeconds));
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    /**
     * Minimal header-aware CSV reader for the dataset files (no quoted fields in
     * these files).
     */
    static final class CsvTable {
        final Map<String, Integer> header = new HashMap<>();
        final List<String[]> rows = new ArrayList<>();

        static CsvTable read(Path path) throws IOException {
            CsvTable t = new CsvTable();
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty())
                return t;
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

    // ------------------------------------------------------------------ hotspot
    // measurement

    private void loadPersistedHotspots(Catalog c) {
        if (museDiskCache.getStateRepository() == null)
            return;
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
        if (c.events().isEmpty() || !measurementStarted.compareAndSet(false, true))
            return;
        measurementThread = Thread.ofVirtual().name("hotspot-measurement").start(() -> {
            int measured = 0;
            for (EventInfo e : c.events()) {
                if (measuredHotspots.containsKey(e.eventId()))
                    continue;
                Optional<File> video = getSequenceFile(e.sequenceClip());
                if (video.isEmpty()) {
                    continue; // video not available: hotspot stays unmeasured (null), never guessed
                }
                Optional<HeatmapHotspotExtractor.MeasuredHotspot> h = HeatmapHotspotExtractor
                        .measure(video.get().toPath(), e.timeSecLocal(), resolveFfmpegBinary());
                measuredHotspots.put(e.eventId(), h);
                persistHotspot(e.eventId(), h);
                hotspotVersion.incrementAndGet();
                measured++;
            }
            LOG.info("Hotspot measurement finished: {} newly measured, {} total known", measured,
                    measuredHotspots.size());
        });
    }

    private void persistHotspot(String eventId, Optional<HeatmapHotspotExtractor.MeasuredHotspot> h) {
        if (museDiskCache.getStateRepository() == null)
            return;
        try {
            museDiskCache.getStateRepository().set(HOTSPOT_KEY_PREFIX + eventId,
                    h.map(HeatmapHotspotExtractor.MeasuredHotspot::serialize).orElse(HOTSPOT_NONE));
        } catch (Exception ex) {
            LOG.warn("Failed to persist hotspot for {}: {}", eventId, ex.getMessage());
        }
    }

    /**
     * Monotonic counter that changes whenever a hotspot measurement becomes
     * available.
     */
    public long getHotspotVersion() {
        getCatalog();
        return hotspotVersion.get();
    }

    /**
     * Blocks until the background hotspot measurement has finished (used by tests
     * and warm-up).
     */
    public boolean awaitHotspotMeasurement(long timeoutMs) throws InterruptedException {
        getCatalog();
        Thread t = measurementThread;
        if (t == null)
            return true;
        return t.join(java.time.Duration.ofMillis(timeoutMs));
    }

    private AnomalyEvent.Hotspot toHotspot(HeatmapHotspotExtractor.MeasuredHotspot h) {
        return new AnomalyEvent.Hotspot(h.gridX(), h.gridY(), h.normCenterX(), h.normCenterY(), h.boxPx());
    }

    // ------------------------------------------------------------------ public
    // catalog API

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
        item.put("timestampSec", round(e.timeSecAxis(), 3));
        item.put("timeSecLocal", round(e.timeSecLocal(), 3));
        item.put("recordingTimeSec", round(e.recordingTimeSec(), 3));
        item.put("formattedTime", formatMmSs(e.recordingTimeSec()));
        item.put("frameIndex", e.peakFrame());
        item.put("tokenDeltaScore", e.score());
        item.put("surpriseScoreL2", e.score()); // legacy key; value is the dataset token-delta score
        item.put("decisionThreshold", c.tau());
        item.put("aboveThreshold", !Double.isNaN(c.tau()) && e.score() >= c.tau());
        item.put("severity", !Double.isNaN(c.tau()) && e.score() >= c.tau() ? "ABOVE_TAU" : "BELOW_TAU");
        item.put("boardPeaks", e.boardPeaks());
        if (e.grassPeaks() != null)
            item.put("grassPeaks", e.grassPeaks());
        if (e.submanifoldState() != null)
            item.put("submanifoldState", e.submanifoldState());
        if (e.pixelDiff() != null)
            item.put("pixelDiffBaseline", e.pixelDiff());
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
            m.put("timestampSec", round(p.timeSecAxis(), 3));
            m.put("timeSecLocal", round(p.timeSecLocal(), 3));
            m.put("sequenceId", p.sequenceId());
            m.put("recordingId", p.recordingId());
            m.put("sequenceIndex", p.sequenceAxisIndex());
            m.put("tokenDeltaScore", p.score());
            m.put("surpriseScoreL2", p.score()); // legacy key; value is the dataset token-delta score
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
            m.put("end_sec_global", s.startSecAxis() + SEQUENCE_SECONDS);
            m.put("recording_start_sec", s.sequenceIndexInRecording() * SEQUENCE_SECONDS);
            m.put("mean_surprise", s.meanScore());
            m.put("max_surprise", s.maxScore());
            seqs.add(m);
        }
        double total = c.sequences().size() * SEQUENCE_SECONDS;
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

    // ------------------------------------------------------------------ media

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

    /**
     * Kept for API compatibility: there are no pre-rendered keyframe files; frames
     * come from the videos.
     */
    public Optional<File> findExactEventFrameFile(String eventId) {
        return Optional.empty();
    }

    /**
     * Extracts the exact peak frame of an event from its stitched sequence (or, if
     * absent, from the
     * 4-second event clip at the peak offset) and caches the JPEG bytes in Redis +
     * SQLite.
     */
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
                offset = Math.max(0, e.peakFrame() - e.clipStartFrame()) / FPS;
            }
        }
        if (video == null) {
            return Optional.empty();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    resolveFfmpegBinary(), "-nostdin", "-loglevel", "error",
                    "-ss", String.format(Locale.US, "%.3f", offset),
                    "-i", video.toString(), "-vframes", "1",
                    "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "2", "-");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            byte[] frameBytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() == 0 && frameBytes.length > 512) {
                museDiskCache.saveFrameBytes(cacheKey, frameBytes);
                return Optional.of(frameBytes);
            }
        } catch (Exception ex) {
            LOG.warn("Failed to extract peak frame for {}: {}", eventId, ex.getMessage());
        }
        return Optional.empty();
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

    static String resolveFfmpegBinary() {
        String envBin = System.getenv("FFMPEG_BIN");
        if (envBin != null && !envBin.isBlank()) {
            return envBin.trim();
        }
        for (String candidate : List.of("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "ffmpeg";
    }

    // ------------------------------------------------------------------ SAM status
    // per event

    /**
     * Returns the cached real SAM 3.1 segmentation of an event, or a NOT_CACHED
     * status. Nothing else.
     */
    public Map<String, Object> analyzeEvent(String eventId, boolean forceRefresh) {
        if (!isSafePathSegment(eventId)) {
            return Map.of("error", "Invalid eventId", "cached", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("analyzedAt", java.time.Instant.now().toString());
        Map<String, Object> samResult = new LinkedHashMap<>();
        samResult.put("model", "meta/sam-3.1");
        Optional<SamGeometry.EventSpatialSummary> sp = forceRefresh ? Optional.empty()
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

//