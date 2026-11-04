package com.beejepa.event;

import io.micronaut.serde.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses sequence, timeline, and event records from CSV tables or manifest JSON files.
 */
final class CatalogLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogLoader.class);

    static final double FPS = 59.94;
    static final double SEQUENCE_SECONDS = 30.0;

    record RawCatalogData(
            String source,
            List<EventService.SequenceInfo> sequences,
            List<EventService.EventInfo> events,
            List<EventService.TimelinePoint> timeline
    ) {}

    private CatalogLoader() {}

    static RawCatalogData loadFromCsvDirectory(Path root) {
        if (root == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path metaDir = normalizedRoot.resolve("metadata");
        Path seqCsv = metaDir.resolve("sequence_runs.csv");
        Path evtCsv = metaDir.resolve("event_catalog.csv");
        Path tlCsv = metaDir.resolve("timeline_points.csv");
        if (!Files.isRegularFile(seqCsv)
                || !Files.isRegularFile(evtCsv)
                || !Files.isRegularFile(tlCsv)
                || !Files.isDirectory(normalizedRoot.resolve("stitched_sequences"))) {
            return null;
        }
        try {
            EventService.CsvTable seqT = EventService.CsvTable.read(seqCsv);
            EventService.CsvTable evtT = EventService.CsvTable.read(evtCsv);
            EventService.CsvTable tlT = EventService.CsvTable.read(tlCsv);

            List<String[]> seqRows = new ArrayList<>(seqT.rows);
            seqRows.sort(Comparator.comparing((String[] r) -> seqT.get(r, "recording_id"))
                    .thenComparingInt(r -> Integer.parseInt(seqT.get(r, "sequence_index"))));

            List<EventService.SequenceInfo> sequences = new ArrayList<>(seqRows.size());
            Map<String, EventService.SequenceInfo> seqById = new HashMap<>();
            for (String[] r : seqRows) {
                int axisIdx = sequences.size();
                String seqId = seqT.get(r, "sequence_id");
                String filename = Path.of(seqT.get(r, "stitched_video_path")).getFileName().toString();
                EventService.SequenceInfo s = new EventService.SequenceInfo(
                        axisIdx,
                        seqId,
                        seqT.get(r, "recording_id"),
                        Integer.parseInt(seqT.get(r, "sequence_index")),
                        filename,
                        axisIdx * SEQUENCE_SECONDS,
                        Double.parseDouble(seqT.get(r, "mean_surprise")),
                        Double.parseDouble(seqT.get(r, "max_surprise"))
                );
                sequences.add(s);
                seqById.put(seqId, s);
            }

            List<EventService.TimelinePoint> timeline = new ArrayList<>(tlT.rows.size());
            Map<String, Double> eventWindowScore = new HashMap<>();
            for (String[] r : tlT.rows) {
                EventService.SequenceInfo s = seqById.get(tlT.get(r, "sequence_id"));
                if (s == null) {
                    continue;
                }
                double local = Double.parseDouble(tlT.get(r, "timestamp_sec"));
                double score = Double.parseDouble(tlT.get(r, "token_delta_score"));
                String evId = tlT.get(r, "event_id");
                timeline.add(new EventService.TimelinePoint(
                        s.sequenceId(),
                        s.recordingId(),
                        s.index(),
                        s.startSecAxis() + local,
                        local,
                        score,
                        Integer.parseInt(tlT.get(r, "board_peaks_9x9")),
                        evId.isBlank() ? null : evId
                ));
                if (!evId.isBlank()) {
                    eventWindowScore.put(evId, score);
                }
            }
            timeline.sort(Comparator.comparingDouble(EventService.TimelinePoint::timeSecAxis));

            List<EventService.EventInfo> events = new ArrayList<>(evtT.rows.size());
            for (String[] r : evtT.rows) {
                String evId = evtT.get(r, "event_id");
                EventService.SequenceInfo s = seqById.get(evtT.get(r, "sequence_id"));
                if (s == null) {
                    continue;
                }
                int peak = Integer.parseInt(evtT.get(r, "peak_frame"));
                double local = peak / FPS;
                double score = eventWindowScore.getOrDefault(evId, Double.parseDouble(evtT.get(r, "surprise_score_l2")));
                events.add(new EventService.EventInfo(
                        evId,
                        s.recordingId(),
                        s.sequenceId(),
                        s.index(),
                        s.filename(),
                        s.startSecAxis() + local,
                        local,
                        s.sequenceIndexInRecording() * SEQUENCE_SECONDS + local,
                        peak,
                        score,
                        Integer.parseInt(evtT.get(r, "board_peaks_16x16")),
                        Integer.parseInt(evtT.get(r, "grass_peaks")),
                        evtT.get(r, "submanifold_state"),
                        Double.parseDouble(evtT.get(r, "pixel_diff_baseline")),
                        Integer.parseInt(evtT.get(r, "start_frame"))
                ));
            }
            events.sort(Comparator.comparingDouble(EventService.EventInfo::timeSecAxis));
            LOG.info("Loaded dataset catalog: {} sequences, {} events, {} timeline windows",
                    sequences.size(), events.size(), timeline.size());
            return new RawCatalogData("ssd_metadata_csv", sequences, events, timeline);
        } catch (Exception e) {
            LOG.error("Failed to parse metadata catalog in {}: {}", metaDir, e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static RawCatalogData loadFromManifestDirectories(List<Path> candidateDirs, ObjectMapper objectMapper) {
        for (Path dir : candidateDirs) {
            Path manifestFile = dir.resolve("manifest.json");
            if (!Files.isRegularFile(manifestFile)) {
                continue;
            }
            try {
                Map<String, Object> m = objectMapper.readValue(Files.readAllBytes(manifestFile), Map.class);
                List<Map<String, Object>> rawSeqs = (List<Map<String, Object>>) m.getOrDefault("sequences", List.of());
                List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) m.getOrDefault("events", List.of());
                List<Map<String, Object>> rawTimeline = (List<Map<String, Object>>) m.getOrDefault("timeline", List.of());

                List<EventService.SequenceInfo> sequences = new ArrayList<>();
                Map<String, EventService.SequenceInfo> seqById = new HashMap<>();
                for (Map<String, Object> rs : rawSeqs) {
                    String seqId = String.valueOf(rs.get("sequence_id"));
                    int axisIdx = num(rs.get("index")).intValue();
                    String rec = seqId.contains("_") ? seqId.substring(0, seqId.indexOf('_')) : seqId;
                    int seqIdxInRec = Integer.parseInt(seqId.replaceAll(".*_seq", ""));
                    EventService.SequenceInfo s = new EventService.SequenceInfo(
                            axisIdx,
                            seqId,
                            rec,
                            seqIdxInRec,
                            String.valueOf(rs.get("filename")),
                            num(rs.get("start_sec_global")).doubleValue(),
                            num(rs.get("mean_surprise")).doubleValue(),
                            num(rs.get("max_surprise")).doubleValue()
                    );
                    sequences.add(s);
                    seqById.put(seqId, s);
                }
                sequences.sort(Comparator.comparingInt(EventService.SequenceInfo::index));

                List<EventService.TimelinePoint> timeline = new ArrayList<>();
                for (Map<String, Object> p : rawTimeline) {
                    double t = num(p.get("time_sec")).doubleValue();
                    int axisIdx = (int) Math.floor(t / SEQUENCE_SECONDS);
                    EventService.SequenceInfo s = axisIdx >= 0 && axisIdx < sequences.size() ? sequences.get(axisIdx) : null;
                    Object ev = p.get("event_id");
                    timeline.add(new EventService.TimelinePoint(
                            s != null ? s.sequenceId() : null,
                            s != null ? s.recordingId() : null,
                            axisIdx,
                            t,
                            t - axisIdx * SEQUENCE_SECONDS,
                            num(p.get("surprise_score")).doubleValue(),
                            num(p.get("board_bees")).intValue(),
                            ev == null || String.valueOf(ev).isBlank() ? null : String.valueOf(ev)
                    ));
                }
                timeline.sort(Comparator.comparingDouble(EventService.TimelinePoint::timeSecAxis));

                List<EventService.EventInfo> events = new ArrayList<>();
                for (Map<String, Object> e : rawEvents) {
                    EventService.SequenceInfo s = seqById.get(String.valueOf(e.get("sequence_id")));
                    if (s == null) {
                        continue;
                    }
                    double local = num(e.get("time_sec_local")).doubleValue();
                    events.add(new EventService.EventInfo(
                            String.valueOf(e.get("event_id")),
                            s.recordingId(),
                            s.sequenceId(),
                            s.index(),
                            s.filename(),
                            num(e.get("time_sec_global")).doubleValue(),
                            local,
                            s.sequenceIndexInRecording() * SEQUENCE_SECONDS + local,
                            num(e.get("frame_approx")).intValue(),
                            num(e.get("surprise_score")).doubleValue(),
                            num(e.get("board_bees")).intValue(),
                            null,
                            null,
                            null,
                            null
                    ));
                }
                events.sort(Comparator.comparingDouble(EventService.EventInfo::timeSecAxis));
                LOG.info("Loaded manifest catalog from {}: {} sequences, {} events",
                        manifestFile, sequences.size(), events.size());
                return new RawCatalogData("hackathon_sample_manifest", sequences, events, timeline);
            } catch (IOException e) {
                LOG.error("Failed to read manifest {}: {}", manifestFile, e.toString());
            }
        }
        return null;
    }

    private static Number num(Object o) {
        if (o instanceof Number n) {
            return n;
        }
        if (o == null) {
            return 0;
        }
        return Double.parseDouble(String.valueOf(o));
    }
}

