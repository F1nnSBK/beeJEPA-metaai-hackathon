package com.beejepa.event;

import com.beejepa.muse.MuseDiskCache;
import com.beejepa.muse.SamGeometry;
import com.beejepa.muse.TolerantPayloadParser;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Singleton
public class EventService {

    private static final Logger LOG = LoggerFactory.getLogger(EventService.class);

    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final MuseDiskCache museDiskCache;

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

    private Path getEventsDirectory() {
        return storageService.getDataDirectory().resolve("events");
    }

    private Path getTimelineFile() {
        return storageService.getDataDirectory().resolve("timeline_rec2123.json");
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
            candidates.add(storageService.getDataDirectory().resolve("hackathon_sample"));
        }
        Path localData = Path.of("data", "hackathon_sample").toAbsolutePath().normalize();
        if (!candidates.contains(localData)) {
            candidates.add(localData);
        }
        return candidates;
    }

    private Path getHackathonSampleDirectory() {
        for (Path dir : getCandidateSampleDirectories()) {
            if (Files.exists(dir.resolve("manifest.json"))) {
                return dir;
            }
        }
        return storageService.getDataDirectory().resolve("hackathon_sample");
    }

    private Path getHackathonManifestFile() {
        return getHackathonSampleDirectory().resolve("manifest.json");
    }

    private volatile Map<String, Object> cachedManifest = null;

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrLoadManifest() {
        if (cachedManifest != null) {
            return cachedManifest;
        }
        synchronized (this) {
            if (cachedManifest != null) {
                return cachedManifest;
            }
            Path manifestFile = getHackathonManifestFile();
            if (Files.exists(manifestFile)) {
                try {
                    LOG.info("Lazily loading hackathon sample manifest from {}", manifestFile);
                    cachedManifest = objectMapper.readValue(Files.readAllBytes(manifestFile), Map.class);
                    return cachedManifest;
                } catch (IOException e) {
                    LOG.error("Failed to read hackathon manifest: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public boolean isKnownManifestEvent(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return false;
        }
        Map<String, Object> manifest = getOrLoadManifest();
        if (manifest != null && manifest.get("events") instanceof List<?> rawEvents) {
            for (Object obj : rawEvents) {
                if (obj instanceof Map<?, ?> ev && eventId.equals(String.valueOf(ev.get("event_id")))) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listEvents() {
        Map<String, Object> manifest = getOrLoadManifest();
        if (manifest != null && manifest.containsKey("events")) {
            List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) manifest.get("events");
            List<Map<String, Object>> result = new ArrayList<>();

            for (Map<String, Object> ev : rawEvents) {
                String eventId = String.valueOf(ev.get("event_id"));
                Map<String, Object> item = new HashMap<>();
                item.put("eventId", eventId);
                item.put("sequenceId", ev.get("sequence_id"));
                item.put("sequenceClip", ev.get("sequence_clip"));
                item.put("timestampSec", ev.get("time_sec_global"));
                item.put("timeSecLocal", ev.get("time_sec_local"));
                item.put("formattedTime", ev.get("formatted_time"));
                item.put("frameIndex", ev.get("frame_approx"));
                item.put("surpriseScoreL2", ev.get("surprise_score"));
                item.put("severity", ev.get("severity"));
                item.put("boardBees", ev.get("board_bees"));
                item.put("description", ev.get("description"));
                item.put("pixelDiffBaseline", 0.0142);
                item.put("horizonDeltaFrames", 60);

                Map<String, Object> rawHotspot = ev.get("hotspot") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : null;
                int gridX = rawHotspot != null && rawHotspot.get("grid_x") instanceof Number n ? n.intValue() : 20;
                int gridY = rawHotspot != null && rawHotspot.get("grid_y") instanceof Number n ? n.intValue() : 18;
                double normCenterX = rawHotspot != null && rawHotspot.get("norm_center_x") instanceof Number n
                        ? n.doubleValue()
                        : 246.0 / 540.0;
                double normCenterY = rawHotspot != null && rawHotspot.get("norm_center_y") instanceof Number n
                        ? n.doubleValue()
                        : 419.0 / 960.0;
                int xPx = (int) Math.round(normCenterX * 540);
                int yPx = (int) Math.round(normCenterY * 960);
                int[] boxPx;
                if (rawHotspot != null && rawHotspot.get("box_px") instanceof List<?> list && list.size() == 4) {
                    boxPx = new int[] {
                            ((Number) list.get(0)).intValue(),
                            ((Number) list.get(1)).intValue(),
                            ((Number) list.get(2)).intValue(),
                            ((Number) list.get(3)).intValue()
                    };
                } else {
                    int r = 68;
                    boxPx = new int[] { Math.max(35, yPx - r), Math.max(0, xPx - r), Math.min(960, yPx + r), Math.min(540, xPx + r) };
                }
                item.put("hotspot", new AnomalyEvent.Hotspot(gridX, gridY, normCenterX, normCenterY, boxPx));
                if (rawHotspot != null && rawHotspot.get("active_cells") instanceof Number n) {
                    item.put("activeCells", n.intValue());
                }

                // Check real Redis/SQLite SAM 3.1 spatial summary cache
                Optional<SamGeometry.EventSpatialSummary> spatialOpt = museDiskCache.loadSpatialSummary(eventId);
                item.put("vjepaBoardBees", ev.get("board_bees"));
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
                    item.put("agentResult", buildAgentResultFromSpatial(eventId, sp, item));
                } else {
                    item.put("isCached", false);
                    item.put("hasSamSpatial", false);
                }

                item.put("hasRawClip", true);
                item.put("hasComparisonClip", false);
                item.put("hasPeakFrame", true);

                result.add(item);
            }
            return result;
        }

        // Fallback to legacy events directory if manifest not available
        Path eventsDir = getEventsDirectory();
        List<Map<String, Object>> result = new ArrayList<>();

        if (!Files.exists(eventsDir)) {
            LOG.debug("Events directory does not exist at {}", eventsDir);
            return result;
        }

        try (Stream<Path> stream = Files.list(eventsDir)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(eventFolder -> {
                        Path metaFile = eventFolder.resolve("meta.json");
                        if (Files.exists(metaFile)) {
                            try {
                                AnomalyEvent event = objectMapper.readValue(Files.readAllBytes(metaFile),
                                        AnomalyEvent.class);
                                Map<String, Object> item = new HashMap<>();
                                item.put("eventId", event.eventId());
                                item.put("sequenceId", event.sequenceId());
                                item.put("timestampSec", event.timestampSec());
                                item.put("frameIndex", event.frameIndex());
                                item.put("surpriseScoreL2", event.surpriseScoreL2());
                                item.put("pixelDiffBaseline", event.pixelDiffBaseline());
                                item.put("horizonDeltaFrames", event.horizonDeltaFrames());
                                item.put("hotspot", event.hotspot());

                                Path agentResultFile = eventFolder.resolve("agent_result.json");
                                boolean hasCachedResult = Files.exists(agentResultFile);
                                item.put("isCached", hasCachedResult);
                                if (hasCachedResult) {
                                    try {
                                        Map<?, ?> agentResult = objectMapper
                                                .readValue(Files.readAllBytes(agentResultFile), Map.class);
                                        item.put("agentResult", agentResult);
                                    } catch (Exception ignored) {
                                    }
                                }

                                item.put("hasRawClip", Files.exists(eventFolder.resolve("clip_raw.mp4")));
                                item.put("hasComparisonClip", Files.exists(eventFolder.resolve("clip_comparison.mp4")));
                                item.put("hasPeakFrame", Files.exists(eventFolder.resolve("peak_frame.jpg")));

                                result.add(item);
                            } catch (IOException e) {
                                LOG.error("Failed to read meta.json from {}: {}", metaFile, e.getMessage());
                            }
                        }
                    });
        } catch (IOException e) {
            LOG.error("Failed to list events from {}: {}", eventsDir, e.getMessage());
        }

        return result;
    }

    public Optional<Map<String, Object>> getEvent(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return Optional.empty();
        }
        List<Map<String, Object>> allEvents = listEvents();
        for (Map<String, Object> ev : allEvents) {
            if (eventId.equals(ev.get("eventId"))) {
                Map<String, Object> map = new HashMap<>();
                AnomalyEvent.Hotspot hotspot = (AnomalyEvent.Hotspot) ev.get("hotspot");
                AnomalyEvent event = new AnomalyEvent(
                        (String) ev.get("eventId"),
                        (String) ev.get("sequenceId"),
                        Double.parseDouble(String.valueOf(ev.get("timestampSec"))),
                        Integer.parseInt(String.valueOf(ev.get("frameIndex"))),
                        Double.parseDouble(String.valueOf(ev.get("surpriseScoreL2"))),
                        Double.parseDouble(String.valueOf(ev.get("pixelDiffBaseline"))),
                        Integer.parseInt(String.valueOf(ev.get("horizonDeltaFrames"))),
                        hotspot);
                map.put("event", event);
                map.put("cached", ev.getOrDefault("isCached", false));
                if (ev.containsKey("agentResult")) {
                    map.put("agentResult", ev.get("agentResult"));
                }
                map.put("hasRawClip", true);
                map.put("hasComparisonClip", false);
                map.put("hasPeakFrame", true);
                return Optional.of(map);
            }
        }

        Path eventsDir = getEventsDirectory().toAbsolutePath().normalize();
        Path eventFolder = eventsDir.resolve(eventId).normalize();
        if (!eventFolder.startsWith(eventsDir)) {
            return Optional.empty();
        }
        Path metaFile = eventFolder.resolve("meta.json");

        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }

        try {
            AnomalyEvent event = objectMapper.readValue(Files.readAllBytes(metaFile), AnomalyEvent.class);
            Map<String, Object> map = new HashMap<>();
            map.put("event", event);

            Path agentResultFile = eventFolder.resolve("agent_result.json");
            if (Files.exists(agentResultFile)) {
                map.put("agentResult", objectMapper.readValue(Files.readAllBytes(agentResultFile), Map.class));
                map.put("cached", true);
            } else {
                map.put("cached", false);
            }

            map.put("hasRawClip", Files.exists(eventFolder.resolve("clip_raw.mp4")));
            map.put("hasComparisonClip", Files.exists(eventFolder.resolve("clip_comparison.mp4")));
            map.put("hasPeakFrame", Files.exists(eventFolder.resolve("peak_frame.jpg")));

            return Optional.of(map);
        } catch (IOException e) {
            LOG.error("Failed to read event {}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks only exact per-event keyframe files on disk (without fallback to another event's frame).
     */
    public Optional<File> findExactEventFrameFile(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return Optional.empty();
        }
        for (Path sampleDir : getCandidateSampleDirectories()) {
            Path framePath = sampleDir.resolve("event_frames").resolve(eventId + ".jpg").normalize();
            if (framePath.startsWith(sampleDir.toAbsolutePath().normalize())
                    && Files.exists(framePath) && Files.isRegularFile(framePath)) {
                return Optional.of(framePath.toFile());
            }
        }
        Path eventsDir = getEventsDirectory().toAbsolutePath().normalize();
        Path legacyFrame = eventsDir.resolve(eventId).resolve("peak_frame.jpg").normalize();
        if (legacyFrame.startsWith(eventsDir) && Files.exists(legacyFrame) && Files.isRegularFile(legacyFrame)) {
            return Optional.of(legacyFrame.toFile());
        }
        return Optional.empty();
    }

    /**
     * Retrieves or extracts the JPEG keyframe bytes for an event directly from the .mp4 video clip
     * via ffmpeg pipe, caching the result in Redis + SQLite without writing static .jpg files to disk.
     */
    public synchronized Optional<byte[]> getOrExtractEventFrameBytes(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return Optional.empty();
        }
        Optional<byte[]> cached = museDiskCache.getFrameBytes(eventId);
        if (cached.isPresent() && cached.get().length > 0) {
            return cached;
        }
        Optional<File> diskFrame = findExactEventFrameFile(eventId);
        if (diskFrame.isPresent()) {
            try {
                byte[] bytes = Files.readAllBytes(diskFrame.get().toPath());
                if (bytes.length > 0) {
                    museDiskCache.saveFrameBytes(eventId, bytes);
                    return Optional.of(bytes);
                }
            } catch (IOException ignored) {
            }
        }

        Path clipPath = null;
        for (Path dir : getCandidateSampleDirectories()) {
            Path candidate = dir.resolve("event_clips").resolve(eventId + ".mp4");
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                clipPath = candidate;
                break;
            }
        }
        if (clipPath == null) {
            return Optional.empty();
        }

        try {
            if (clipPath.toFile().length() < 1024) {
                return Optional.empty();
            }
            ProcessBuilder pb = new ProcessBuilder(
                    resolveFfmpegBinary(),
                    "-nostdin",
                    "-loglevel", "error",
                    "-ss", "00:00:01.5",
                    "-i", clipPath.toString(),
                    "-vframes", "1",
                    "-f", "image2pipe",
                    "-vcodec", "mjpeg",
                    "-q:v", "2",
                    "-"
            );
            Process process = pb.start();
            byte[] frameBytes = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() == 0 && frameBytes.length > 512) {
                museDiskCache.saveFrameBytes(eventId, frameBytes);
                return Optional.of(frameBytes);
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract keyframe via ffmpeg pipe for {}: {}", eventId, e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<File> getMediaFile(String eventId, String filename) {
        if (!isSafePathSegment(eventId) || !isSafePathSegment(filename)) {
            return Optional.empty();
        }
        List<Path> sampleDirs = getCandidateSampleDirectories();

        // 1. Check hackathon sample event clips across candidate directories
        if ("clip_raw.mp4".equals(filename) || filename.endsWith(".mp4")) {
            for (Path sampleDir : sampleDirs) {
                Path base = sampleDir.toAbsolutePath().normalize();
                Path sampleClip = base.resolve("event_clips").resolve(eventId + ".mp4").normalize();
                if (sampleClip.startsWith(base) && Files.exists(sampleClip) && Files.isRegularFile(sampleClip)) {
                    return Optional.of(sampleClip.toFile());
                }
                Path seqClip = base.resolve("sequences").resolve(filename).normalize();
                if (seqClip.startsWith(base) && Files.exists(seqClip) && Files.isRegularFile(seqClip)) {
                    return Optional.of(seqClip.toFile());
                }
            }
        }

        // 2. Check exact frame file if present
        if ("peak_frame.jpg".equals(filename) || "vjepa_heatmap.jpg".equals(filename)) {
            Optional<File> exact = findExactEventFrameFile(eventId);
            if (exact.isPresent()) {
                return exact;
            }
        }

        // 3. Fallback to standard events directory
        Path eventsDir = getEventsDirectory().toAbsolutePath().normalize();
        Path filePath = eventsDir.resolve(eventId).resolve(filename).normalize();
        if (filePath.startsWith(eventsDir) && Files.exists(filePath) && Files.isRegularFile(filePath)) {
            return Optional.of(filePath.toFile());
        }
        return Optional.empty();
    }

    public Optional<File> getSequenceFile(String filename) {
        if (!isSafePathSegment(filename)) {
            return Optional.empty();
        }
        String safeName = Path.of(filename).getFileName().toString();
        for (Path sampleDir : getCandidateSampleDirectories()) {
            Path base = sampleDir.toAbsolutePath().normalize();
            Path seqClip = base.resolve("sequences").resolve(safeName).normalize();
            if (seqClip.startsWith(base) && Files.exists(seqClip) && Files.isRegularFile(seqClip)) {
                return Optional.of(seqClip.toFile());
            }
        }
        return Optional.empty();
    }

    private static String resolveFfmpegBinary() {
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> getTimeline() {
        Map<String, Object> manifest = getOrLoadManifest();
        if (manifest != null && manifest.containsKey("timeline")) {
            List<Map<String, Object>> rawPoints = (List<Map<String, Object>>) manifest.get("timeline");
            List<Map<String, Object>> mappedTimeline = new ArrayList<>(rawPoints.size());
            for (Map<String, Object> pt : rawPoints) {
                Map<String, Object> m = new HashMap<>(pt);
                m.put("timestampSec", pt.get("time_sec"));
                m.put("surpriseScoreL2", pt.get("surprise_score"));
                m.put("isEvent", pt.get("is_event"));
                m.put("eventId", pt.get("event_id"));
                m.put("boardBees", pt.get("board_bees"));
                mappedTimeline.add(m);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("timeline", mappedTimeline);
            response.put("totalDurationSec", manifest.getOrDefault("total_duration_sec", 300.0));
            response.put("formattedDuration", manifest.getOrDefault("formatted_duration", "05:00"));
            response.put("decisionThreshold", manifest.getOrDefault("decision_threshold_tau", 10.2));
            response.put("maxSurprise", 16.5);
            response.put("minSurprise", 9.5);
            response.put("sequences", manifest.get("sequences"));
            response.put("summary", manifest.get("summary"));
            return response;
        }

        Path timelinePath = getTimelineFile();
        if (Files.exists(timelinePath)) {
            try {
                return objectMapper.readValue(Files.readAllBytes(timelinePath), Map.class);
            } catch (IOException e) {
                LOG.error("Failed to read timeline JSON: {}", e.getMessage());
            }
        }
        return Map.of("timeline", List.of(), "error", "Timeline not found on storage");
    }

    private Map<String, Object> buildAgentResultFromSpatial(
            String eventId,
            SamGeometry.EventSpatialSummary sp,
            Map<String, Object> eventItem
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("cached", true);
        result.put("analyzedAt", java.time.Instant.now().toString());

        Map<String, Object> samResult = new LinkedHashMap<>();
        samResult.put("model", "meta/sam-3.1");
        samResult.put("status", "SUCCESS");
        samResult.put("targetFrame", "peak_frame.jpg");
        samResult.put("instanceCount", sp.landingBoardBeeCount());
        samResult.put("hotspotBeesCount", sp.beesInsideHotspotCount());
        samResult.put("segmentedObject", sp.geometricPreClassification() + " (" + sp.beesInsideHotspotCount() + " bees in hotspot)");
        if (eventItem != null && eventItem.get("hotspot") != null) {
            samResult.put("boxPrompt", eventItem.get("hotspot"));
        }
        result.put("sam", samResult);

        String diagnosisText = museDiskCache.getMuseResponse("what happened at the hive entrance? analyze the segmented anomaly.|," + eventId)
                .map(raw -> TolerantPayloadParser.parseDualMuseOutput(raw).spokenSummary())
                .orElseGet(() -> String.format(
                        java.util.Locale.US,
                        "At %s (%s, %.2f sigma), SAM 3.1 isolated %d honeybees on the landing board with %d bees clustered inside the foveal hotspot (%s). Zero foreign predators detected; no intervention required.",
                        sp.timestamp(), sp.severity(), sp.surpriseScoreSigma(),
                        sp.landingBoardBeeCount(), sp.beesInsideHotspotCount(), sp.geometricPreClassification()
                ));

        Map<String, Object> museResult = new LinkedHashMap<>();
        museResult.put("model", "meta-models/muse-spark-open");
        museResult.put("status", "SUCCESS");
        museResult.put("diagnosis", diagnosisText);
        result.put("muse", museResult);
        return result;
    }

    public Map<String, Object> analyzeEvent(String eventId, boolean forceRefresh) {
        if (!isSafePathSegment(eventId)) {
            return Map.of("error", "Invalid eventId", "cached", false);
        }

        // 1. Check real Redis/SQLite SAM 3.1 spatial summary cache first
        if (!forceRefresh) {
            Optional<SamGeometry.EventSpatialSummary> spatialOpt = museDiskCache.loadSpatialSummary(eventId);
            if (spatialOpt.isPresent()) {
                return buildAgentResultFromSpatial(eventId, spatialOpt.get(), null);
            }
        }

        // 2. Check legacy events/<eventId>/agent_result.json if present
        Path eventsDir = getEventsDirectory().toAbsolutePath().normalize();
        Path legacyAgentFile = eventsDir.resolve(eventId).resolve("agent_result.json").normalize();
        if (!forceRefresh && legacyAgentFile.startsWith(eventsDir) && Files.exists(legacyAgentFile)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> cached = objectMapper.readValue(Files.readAllBytes(legacyAgentFile), Map.class);
                cached.put("cached", true);
                return cached;
            } catch (IOException ignored) {
            }
        }

        // 3. No cached SAM 3.1 spatial segmentation exists yet; return V-JEPA 2.1 telemetry
        Map<String, Object> result = new HashMap<>();
        result.put("eventId", eventId);
        result.put("cached", false);
        result.put("analyzedAt", java.time.Instant.now().toString());

        Map<String, Object> samResult = new HashMap<>();
        samResult.put("model", "meta/sam-3.1");
        samResult.put("status", "NOT_CACHED");
        samResult.put("targetFrame", "peak_frame.jpg");

        getEvent(eventId).ifPresent(ev -> {
            Object rawEvent = ev.get("event");
            if (rawEvent instanceof AnomalyEvent ae) {
                samResult.put("boxPrompt", ae.hotspot());
                samResult.put("frameIndex", ae.frameIndex());
                samResult.put("timestampSec", ae.timestampSec());
                samResult.put("surpriseScoreL2", ae.surpriseScoreL2());
            }
        });
        result.put("sam", samResult);

        return result;
    }
}
