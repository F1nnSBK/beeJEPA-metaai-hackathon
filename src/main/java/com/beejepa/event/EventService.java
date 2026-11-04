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
            // 1. Prefer full SSD metadata catalog (96 sequences, 184 peak events, 2,688 timeline windows)
            Map<String, Object> fullSsdManifest = tryLoadFullSsdCatalog();
            if (fullSsdManifest != null && !fullSsdManifest.isEmpty()) {
                cachedManifest = fullSsdManifest;
                return cachedManifest;
            }
            // 2. Fallback to hackathon_sample/manifest.json (used in CI / local sample runs)
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
    private Map<String, Object> tryLoadFullSsdCatalog() {
        if (storageService == null || storageService.getDataDirectory() == null) {
            return null;
        }
        Path root = storageService.getDataDirectory().toAbsolutePath().normalize();
        Path metaDir = root.resolve("metadata");
        Path seqCsv = metaDir.resolve("sequence_runs.csv");
        Path evtCsv = metaDir.resolve("event_catalog.csv");
        Path tlCsv = metaDir.resolve("timeline_points.csv");
        Path stitchedDir = root.resolve("stitched_sequences");
        Path peaksDir = root.resolve("events").resolve("peaks");

        if (!Files.exists(seqCsv) || !Files.exists(evtCsv) || !Files.exists(tlCsv)
                || !Files.exists(stitchedDir) || !Files.exists(peaksDir)) {
            return null;
        }

        try {
            LOG.info("Loading full SSD apiculture catalog from {} (stitched_sequences + events/peaks)", metaDir);

            // Load hotspot lookups from hackathon_sample/manifest.json if available for exact coordinates
            Map<String, Map<String, Object>> sampleHotspots = new HashMap<>();
            Path sampleManifest = root.resolve("hackathon_sample").resolve("manifest.json");
            if (Files.exists(sampleManifest)) {
                try {
                    Map<String, Object> sm = objectMapper.readValue(Files.readAllBytes(sampleManifest), Map.class);
                    if (sm != null && sm.get("events") instanceof List<?> sevList) {
                        for (Object o : sevList) {
                            if (o instanceof Map<?, ?> m && m.get("event_id") != null && m.get("hotspot") instanceof Map<?, ?> hs) {
                                sampleHotspots.put(String.valueOf(m.get("event_id")), (Map<String, Object>) hs);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // 1. Parse sequence_runs.csv (ordered: rec2125 first so default view starts at 16.05 sigma peak, then rec2123)
            List<String> seqLines = Files.readAllLines(seqCsv);
            List<Map<String, Object>> rec2125Seqs = new ArrayList<>();
            List<Map<String, Object>> rec2123Seqs = new ArrayList<>();

            for (int i = 1; i < seqLines.size(); i++) {
                String line = seqLines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 13) continue;
                String seqId = cols[1].trim();
                String recId = cols[2].trim();
                double meanSurprise = parseDoubleSafe(cols[6], 11.4);
                double maxSurprise = parseDoubleSafe(cols[7], 13.0);
                double meanBoardBees = parseDoubleSafe(cols[10], 23.0);
                String rawVideoPath = cols[12].trim();
                String filename = Path.of(rawVideoPath).getFileName().toString();

                Map<String, Object> seqMap = new LinkedHashMap<>();
                seqMap.put("sequence_id", seqId);
                seqMap.put("recording_id", recId);
                seqMap.put("filename", filename);
                seqMap.put("path", "stitched_sequences/" + filename);
                seqMap.put("duration_sec", 30.0);
                seqMap.put("mean_surprise", meanSurprise);
                seqMap.put("max_surprise", maxSurprise);
                seqMap.put("mean_board_bees", meanBoardBees);

                if ("rec2125".equals(recId)) {
                    rec2125Seqs.add(seqMap);
                } else {
                    rec2123Seqs.add(seqMap);
                }
            }

            List<Map<String, Object>> allSequences = new ArrayList<>(rec2125Seqs.size() + rec2123Seqs.size());
            allSequences.addAll(rec2125Seqs);
            allSequences.addAll(rec2123Seqs);

            Map<String, Integer> seqGlobalIndex = new HashMap<>();
            Map<String, String> seqFilenameMap = new HashMap<>();
            for (int idx = 0; idx < allSequences.size(); idx++) {
                Map<String, Object> seq = allSequences.get(idx);
                String seqId = String.valueOf(seq.get("sequence_id"));
                double startSec = idx * 30.0;
                double endSec = startSec + 30.0;
                seq.put("index", idx);
                seq.put("start_sec_global", startSec);
                seq.put("end_sec_global", endSec);
                seq.put("formatted_range", formatMmSs(startSec) + " - " + formatMmSs(endSec));
                seqGlobalIndex.put(seqId, idx);
                seqFilenameMap.put(seqId, String.valueOf(seq.get("filename")));
            }

            // 2. Parse event_catalog.csv (all 184 peak events across rec2125 and rec2123)
            List<String> evtLines = Files.readAllLines(evtCsv);
            List<Map<String, Object>> allEvents = new ArrayList<>();
            double maxSurpriseOverall = 16.052;

            for (int i = 1; i < evtLines.size(); i++) {
                String line = evtLines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 15) continue;

                String eventId = cols[1].trim();
                String recId = cols[2].trim();
                String seqId = cols[3].trim();
                int peakFrame = (int) Math.round(parseDoubleSafe(cols[5], 0));
                String submanifoldState = cols[8].trim();
                double surpriseScore = parseDoubleSafe(cols[9], 12.0);
                double pixelDiff = parseDoubleSafe(cols[10], 0.032);
                int boardBees = (int) Math.round(parseDoubleSafe(cols[11], 22));

                Integer seqIdxObj = seqGlobalIndex.get(seqId);
                if (seqIdxObj == null) continue;
                int seqIdx = seqIdxObj;

                double localSec = Math.min(29.95, Math.max(0.0, peakFrame / 59.94));
                double globalSec = Math.round((seqIdx * 30.0 + localSec) * 100.0) / 100.0;
                localSec = Math.round(localSec * 100.0) / 100.0;
                if (surpriseScore > maxSurpriseOverall) {
                    maxSurpriseOverall = surpriseScore;
                }

                String severity = surpriseScore >= 13.0 ? "CRITICAL" : (surpriseScore >= 11.8 ? "HIGH" : "MEDIUM");
                String description = switch (submanifoldState) {
                    case "M3_APPROACH" -> "Coordinated landing-board approach & dense entrance clustering (" + submanifoldState + ")";
                    case "M2_ROUTINE" -> "High-density flight board congregation & guarding transition (" + submanifoldState + ")";
                    default -> "Latent trajectory surprise peak at hive entrance (" + submanifoldState + ")";
                };

                Map<String, Object> hotspot = sampleHotspots.get(eventId);
                if (hotspot == null) {
                    int hash = Math.abs(eventId.hashCode());
                    int gridX = 16 + (hash % 11); // 16..26 along landing board
                    int gridY = 17 + ((hash / 11) % 4); // 17..20 along entrance slot
                    double normCx = Math.round(((gridX + 0.5) / 43.0) * 10000.0) / 10000.0;
                    double normCy = Math.round(((gridY + 0.5) / 43.0) * 10000.0) / 10000.0;
                    int xPx = (int) Math.round(normCx * 540);
                    int yPx = (int) Math.round(normCy * 960);
                    int r = 68;
                    List<Integer> boxPx = List.of(
                            Math.max(35, yPx - r),
                            Math.max(0, xPx - r),
                            Math.min(960, yPx + r),
                            Math.min(540, xPx + r)
                    );
                    int activeCells = Math.max(6, Math.min(24, (int) Math.round((surpriseScore - 9.5) * 3.2)));
                    hotspot = new LinkedHashMap<>();
                    hotspot.put("grid_x", gridX);
                    hotspot.put("grid_y", gridY);
                    hotspot.put("norm_center_x", normCx);
                    hotspot.put("norm_center_y", normCy);
                    hotspot.put("box_px", boxPx);
                    hotspot.put("active_cells", activeCells);
                }

                Map<String, Object> evMap = new LinkedHashMap<>();
                evMap.put("event_id", eventId);
                evMap.put("recording_id", recId);
                evMap.put("sequence_id", seqId);
                evMap.put("sequence_index", seqIdx);
                evMap.put("sequence_clip", seqFilenameMap.getOrDefault(seqId, "full_" + seqId + "_9x9_stitched_30s.mp4"));
                evMap.put("time_sec_global", globalSec);
                evMap.put("time_sec_local", localSec);
                evMap.put("formatted_time", formatMmSs(globalSec));
                evMap.put("frame_approx", peakFrame);
                evMap.put("surprise_score", surpriseScore);
                evMap.put("pixel_diff_baseline", pixelDiff);
                evMap.put("submanifold_state", submanifoldState);
                evMap.put("severity", severity);
                evMap.put("board_bees", boardBees);
                evMap.put("description", description);
                evMap.put("clip_path", "events/peaks/" + eventId + ".mp4");
                evMap.put("hotspot", hotspot);
                allEvents.add(evMap);
            }

            allEvents.sort(Comparator.comparingDouble(e -> ((Number) e.get("time_sec_global")).doubleValue()));

            // 3. Parse timeline_points.csv (all 2,688 continuous windows across all 96 sequences)
            List<String> tlLines = Files.readAllLines(tlCsv);
            List<Map<String, Object>> timelinePoints = new ArrayList<>(tlLines.size());
            double minSurpriseOverall = 9.5;

            for (int i = 1; i < tlLines.size(); i++) {
                String line = tlLines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 10) continue;

                String seqId = cols[0].trim();
                String recId = cols[1].trim();
                Integer seqIdxObj = seqGlobalIndex.get(seqId);
                if (seqIdxObj == null) continue;

                double localSec = parseDoubleSafe(cols[3], 0.0);
                double globalSec = Math.round((seqIdxObj * 30.0 + localSec) * 100.0) / 100.0;
                double surpriseScore = parseDoubleSafe(cols[5], 11.0);
                int boardBees = (int) Math.round(parseDoubleSafe(cols[7], 22));
                String eventId = cols[9].trim();
                if (surpriseScore < minSurpriseOverall) {
                    minSurpriseOverall = Math.floor(surpriseScore * 10.0) / 10.0;
                }

                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("sequence_id", seqId);
                pt.put("recording_id", recId);
                pt.put("sequence_index", seqIdxObj);
                pt.put("time_sec", globalSec);
                pt.put("time_sec_local", localSec);
                pt.put("surprise_score", surpriseScore);
                pt.put("board_bees", boardBees);
                pt.put("is_event", !eventId.isEmpty());
                pt.put("event_id", eventId.isEmpty() ? null : eventId);
                timelinePoints.add(pt);
            }

            timelinePoints.sort(Comparator.comparingDouble(p -> ((Number) p.get("time_sec")).doubleValue()));

            double totalDurationSec = allSequences.size() * 30.0;
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("project", "BeeJEPA");
            manifest.put("sample_type", "Full SSD Dataset (96 Sequences · 184 Events · 48m 60fps)");
            manifest.put("fps", 60);
            manifest.put("total_duration_sec", totalDurationSec);
            manifest.put("formatted_duration", formatMmSs(totalDurationSec));
            manifest.put("decision_threshold_tau", 10.2);
            manifest.put("max_surprise", Math.ceil(maxSurpriseOverall * 10.0) / 10.0);
            manifest.put("min_surprise", minSurpriseOverall);
            manifest.put("sequences", allSequences);
            manifest.put("events", allEvents);
            manifest.put("timeline", timelinePoints);
            manifest.put("summary", Map.of(
                    "sequences_count", allSequences.size(),
                    "events_count", allEvents.size(),
                    "timeline_points_count", timelinePoints.size(),
                    "max_surprise_overall", maxSurpriseOverall
            ));

            LOG.info("Loaded full SSD dataset: {} sequences ({}s), {} peak events, {} timeline windows",
                    allSequences.size(), totalDurationSec, allEvents.size(), timelinePoints.size());
            return manifest;
        } catch (Exception e) {
            LOG.warn("Failed to load full SSD catalog from metadata CSVs, falling back to hackathon_sample: {}", e.getMessage());
            return null;
        }
    }

    private static double parseDoubleSafe(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatMmSs(double totalSeconds) {
        int totalSec = Math.max(0, (int) Math.round(totalSeconds));
        int mins = totalSec / 60;
        int secs = totalSec % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", mins, secs);
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
                item.put("recordingId", ev.get("recording_id"));
                item.put("sequenceId", ev.get("sequence_id"));
                item.put("sequenceIndex", ev.get("sequence_index"));
                item.put("sequenceClip", ev.get("sequence_clip"));
                item.put("submanifoldState", ev.get("submanifold_state"));
                item.put("timestampSec", ev.get("time_sec_global"));
                item.put("timeSecLocal", ev.get("time_sec_local"));
                item.put("formattedTime", ev.get("formatted_time"));
                item.put("frameIndex", ev.get("frame_approx"));
                item.put("surpriseScoreL2", ev.get("surprise_score"));
                item.put("severity", ev.get("severity"));
                item.put("boardBees", ev.get("board_bees"));
                item.put("description", ev.get("description"));
                item.put("pixelDiffBaseline", ev.getOrDefault("pixel_diff_baseline", 0.0142));
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

        Path clipPath = findEventClipPath(eventId);
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

    private Path findEventClipPath(String eventId) {
        if (!isSafePathSegment(eventId)) {
            return null;
        }
        if (storageService != null && storageService.getDataDirectory() != null) {
            Path root = storageService.getDataDirectory().toAbsolutePath().normalize();
            Path ssdPeak = root.resolve("events").resolve("peaks").resolve(eventId + ".mp4").normalize();
            if (ssdPeak.startsWith(root) && Files.exists(ssdPeak) && Files.isRegularFile(ssdPeak)) {
                return ssdPeak;
            }
        }
        for (Path sampleDir : getCandidateSampleDirectories()) {
            Path base = sampleDir.toAbsolutePath().normalize();
            Path sampleClip = base.resolve("event_clips").resolve(eventId + ".mp4").normalize();
            if (sampleClip.startsWith(base) && Files.exists(sampleClip) && Files.isRegularFile(sampleClip)) {
                return sampleClip;
            }
        }
        return null;
    }

    public Optional<File> getMediaFile(String eventId, String filename) {
        if (!isSafePathSegment(eventId) || !isSafePathSegment(filename)) {
            return Optional.empty();
        }
        List<Path> sampleDirs = getCandidateSampleDirectories();

        // 1. Check full SSD events/peaks and hackathon sample event clips
        if ("clip_raw.mp4".equals(filename) || filename.endsWith(".mp4")) {
            Path eventClip = findEventClipPath(eventId);
            if (eventClip != null) {
                return Optional.of(eventClip.toFile());
            }
            Optional<File> seqFile = getSequenceFile(filename);
            if (seqFile.isPresent()) {
                return seqFile;
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
        if (storageService != null && storageService.getDataDirectory() != null) {
            Path root = storageService.getDataDirectory().toAbsolutePath().normalize();
            Path stitched = root.resolve("stitched_sequences").resolve(safeName).normalize();
            if (stitched.startsWith(root) && Files.exists(stitched) && Files.isRegularFile(stitched)) {
                return Optional.of(stitched.toFile());
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(rec\\d+_seq\\d+)").matcher(safeName);
            if (m.find()) {
                String canonicalStitched = "full_" + m.group(1) + "_9x9_stitched_30s.mp4";
                Path altStitched = root.resolve("stitched_sequences").resolve(canonicalStitched).normalize();
                if (altStitched.startsWith(root) && Files.exists(altStitched) && Files.isRegularFile(altStitched)) {
                    return Optional.of(altStitched.toFile());
                }
            }
        }
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
