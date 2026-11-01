package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema-tolerant JSON and token payload parser for external LLM tool calls,
 * Meta SAM 3.1 segmentation responses, and model completions.
 */
public final class TolerantPayloadParser {

    private static final ObjectMapper MAPPER = JacksonFactory.createTolerantMapper();

    private static final Pattern BOX_TOKEN_REGEX =
            Pattern.compile("<\\|box;x1=(-?\\d+(?:\\.\\d+)?);y1=(-?\\d+(?:\\.\\d+)?);x2=(-?\\d+(?:\\.\\d+)?);y2=(-?\\d+(?:\\.\\d+)?)[^|]*\\|>");

    private static final Pattern REPORT_TAG_REGEX =
            Pattern.compile("(?is)<report>\\s*(.*?)\\s*</report>");
    private static final Pattern SPOKEN_TAG_REGEX =
            Pattern.compile("(?is)<spoken(?:_summary)?>\\s*(.*?)\\s*(?:</spoken(?:_summary)?>|$)");
    private static final Pattern SPOKEN_HEADING_SPLIT_REGEX =
            Pattern.compile("(?im)^(?:#+\\s*(?:spoken\\s+summary|natural\\s+spoken\\s+summary|voice\\s+briefing|audio\\s+narration)|---\\s*spoken\\s*---)\\s*:?\\s*$");
    private static final Pattern MM_SS_TIMESTAMP_REGEX =
            Pattern.compile("\\b(\\d{2}):(\\d{2})\\b");

    private TolerantPayloadParser() {}

    public record DualMuseOutput(
            String displayReport,
            String spokenSummary
    ) {}

    public record ParsedFovealArgs(
            String resolvedEventId,
            int targetX,
            int targetY,
            int foveaRadiusPx
    ) {}

    public record ParsedSamConceptArgs(
            String resolvedEventId,
            String conceptPrompt,
            boolean includeBaselineBeeCount
    ) {}

    /**
     * Parses QueryFovealHotspot arguments tolerantly across snake_case, camelCase,
     * stringified numbers, and partial/timestamp event references.
     */
    public static ParsedFovealArgs parseFovealHotspotArgs(String rawJson, List<EventSpatialSummary> availableEvents) {
        String fallbackEventId = (availableEvents != null && !availableEvents.isEmpty())
                ? availableEvents.getFirst().eventId()
                : "evt_rec2125_seq000_f1217";
        int defaultX = 246;
        int defaultY = 419;
        int defaultRadius = 60;

        if (rawJson == null || rawJson.isBlank()) {
            return new ParsedFovealArgs(fallbackEventId, defaultX, defaultY, defaultRadius);
        }

        try {
            JsonNode root = MAPPER.readTree(rawJson);
            if (root == null || !root.isObject()) {
                return new ParsedFovealArgs(fallbackEventId, defaultX, defaultY, defaultRadius);
            }

            String rawEvt = readString(root, "event_id", "eventId", "id", "timestamp", "event");
            String resolvedId = resolveEventId(rawEvt, availableEvents, fallbackEventId);

            int tx = readInt(root, defaultX, "target_x", "targetX", "x", "center_x", "centerX", "focus_x");
            int ty = readInt(root, defaultY, "target_y", "targetY", "y", "center_y", "centerY", "focus_y");
            int radius = readInt(root, defaultRadius, "fovea_radius_px", "foveaRadiusPx", "radius", "r", "fovea_radius");

            tx = Math.clamp(tx, 0, SamGeometry.FRAME_SIZE);
            ty = Math.clamp(ty, 0, SamGeometry.FULL_FRAME_HEIGHT_PX);
            radius = Math.clamp(radius <= 0 ? defaultRadius : radius, 20, 340);

            return new ParsedFovealArgs(resolvedId, tx, ty, radius);
        } catch (Exception e) {
            return new ParsedFovealArgs(fallbackEventId, defaultX, defaultY, defaultRadius);
        }
    }

    /**
     * Parses QuerySam31Concept arguments tolerantly across snake_case, camelCase,
     * boolean strings, and missing optional fields.
     */
    public static ParsedSamConceptArgs parseSamConceptArgs(String rawJson, List<EventSpatialSummary> availableEvents) {
        String fallbackEventId = (availableEvents != null && !availableEvents.isEmpty())
                ? availableEvents.getFirst().eventId()
                : "evt_rec2125_seq000_f1217";

        if (rawJson == null || rawJson.isBlank()) {
            return new ParsedSamConceptArgs(fallbackEventId, "bee", true);
        }

        try {
            JsonNode root = MAPPER.readTree(rawJson);
            if (root == null || !root.isObject()) {
                return new ParsedSamConceptArgs(fallbackEventId, "bee", true);
            }

            String rawEvt = readString(root, "event_id", "eventId", "id", "timestamp", "event");
            String resolvedId = resolveEventId(rawEvt, availableEvents, fallbackEventId);

            String concept = readString(root, "concept_prompt", "conceptPrompt", "concept", "prompt", "query", "label", "target");
            if (concept == null || concept.isBlank()) {
                concept = "bee";
            } else {
                concept = concept.trim();
            }

            boolean includeBaseline = readBoolean(root, true,
                    "include_baseline_bee_count", "includeBaselineBeeCount", "include_baseline", "baseline");

            return new ParsedSamConceptArgs(resolvedId, concept, includeBaseline);
        } catch (Exception e) {
            return new ParsedSamConceptArgs(fallbackEventId, "bee", true);
        }
    }

    /**
     * Resolves an LLM-supplied event identifier (exact ID, frame suffix like "f1217",
     * or timestamp like "00:20") against the available events.
     */
    public static String resolveEventId(String candidate, List<EventSpatialSummary> availableEvents, String fallbackId) {
        if (availableEvents == null || availableEvents.isEmpty()) {
            return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallbackId;
        }
        if (candidate == null || candidate.isBlank()) {
            return fallbackId;
        }
        String clean = candidate.trim();

        // 1. Exact match
        for (EventSpatialSummary s : availableEvents) {
            if (s.eventId().equalsIgnoreCase(clean)) {
                return s.eventId();
            }
        }
        // 2. Timestamp match (e.g. "00:20")
        for (EventSpatialSummary s : availableEvents) {
            if (s.timestamp() != null && clean.contains(s.timestamp())) {
                return s.eventId();
            }
        }
        // 3. Substring / frame suffix match (e.g. "f1217", "1217", "seq000_f1217")
        for (EventSpatialSummary s : availableEvents) {
            String idLower = s.eventId().toLowerCase();
            String cleanLower = clean.toLowerCase();
            if (idLower.contains(cleanLower) || cleanLower.contains(idLower)) {
                return s.eventId();
            }
        }
        return fallbackId;
    }

    /**
     * Extracts SAM 3.1 bounding boxes from token strings (<|box;x1=...|>), JSON arrays,
     * or nested response objects, optionally scaling from [0..1000] normalized space to pixel space.
     */
    public static List<BoundingBox> parseSamBoxes(
            String rawJsonOrText,
            int imageWidth,
            int imageHeight,
            boolean scaleFrom1000,
            boolean filterLandingBoard
    ) {
        List<BoundingBox> boxes = new ArrayList<>();
        if (rawJsonOrText == null || rawJsonOrText.isBlank()) {
            return boxes;
        }

        List<double[]> rawCoords = new ArrayList<>();

        // 1. Extract <|box;x1=...;y1=...;x2=...;y2=...| > tokens
        Matcher matcher = BOX_TOKEN_REGEX.matcher(rawJsonOrText);
        while (matcher.find()) {
            try {
                double x1 = Double.parseDouble(matcher.group(1));
                double y1 = Double.parseDouble(matcher.group(2));
                double x2 = Double.parseDouble(matcher.group(3));
                double y2 = Double.parseDouble(matcher.group(4));
                rawCoords.add(new double[]{x1, y1, x2, y2});
            } catch (NumberFormatException ignored) {
            }
        }

        // 2. If no tokens found, inspect JSON tree for structured boxes or nested text
        if (rawCoords.isEmpty() && rawJsonOrText.trim().startsWith("{")) {
            try {
                JsonNode root = MAPPER.readTree(rawJsonOrText);
                extractBoxesFromJsonNode(root, rawCoords);
            } catch (Exception ignored) {
            }
        }

        int safeW = imageWidth > 0 ? imageWidth : SamGeometry.FRAME_SIZE;
        int safeH = imageHeight > 0 ? imageHeight : SamGeometry.FRAME_SIZE;

        for (double[] c : rawCoords) {
            double rx1 = c[0];
            double ry1 = c[1];
            double rx2 = c[2];
            double ry2 = c[3];

            int x1, y1, x2, y2;
            if (scaleFrom1000) {
                // Normalized [0..1] vs [0..1000] detection
                boolean isUnitNormalized = rx2 <= 1.0 && ry2 <= 1.0 && (rx2 > 0.0 || ry2 > 0.0);
                double scaleX = isUnitNormalized ? safeW : (safeW / 1000.0);
                double scaleY = isUnitNormalized ? safeH : (safeH / 1000.0);
                x1 = (int) Math.round(rx1 * scaleX);
                y1 = (int) Math.round(ry1 * scaleY);
                x2 = (int) Math.round(rx2 * scaleX);
                y2 = (int) Math.round(ry2 * scaleY);
            } else {
                x1 = (int) Math.round(rx1);
                y1 = (int) Math.round(ry1);
                x2 = (int) Math.round(rx2);
                y2 = (int) Math.round(ry2);
            }

            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);

            BoundingBox box = new BoundingBox(minX, minY, maxX, maxY);
            if (!filterLandingBoard || box.isOnLandingBoard()) {
                boxes.add(box);
            }
        }

        return boxes;
    }

    private static void extractBoxesFromJsonNode(JsonNode node, List<double[]> collector) {
        if (node == null) return;
        if (node.isTextual()) {
            Matcher m = BOX_TOKEN_REGEX.matcher(node.asText());
            while (m.find()) {
                try {
                    collector.add(new double[]{
                            Double.parseDouble(m.group(1)),
                            Double.parseDouble(m.group(2)),
                            Double.parseDouble(m.group(3)),
                            Double.parseDouble(m.group(4))
                    });
                } catch (NumberFormatException ignored) {}
            }
            return;
        }
        if (node.isArray()) {
            // Check if this array is a 4-number bounding box [x1, y1, x2, y2]
            if (node.size() == 4 && node.get(0).isNumber() && node.get(1).isNumber()
                    && node.get(2).isNumber() && node.get(3).isNumber()) {
                collector.add(new double[]{
                        node.get(0).asDouble(),
                        node.get(1).asDouble(),
                        node.get(2).asDouble(),
                        node.get(3).asDouble()
                });
                return;
            }
            for (JsonNode child : node) {
                extractBoxesFromJsonNode(child, collector);
            }
            return;
        }
        if (node.isObject()) {
            if (node.has("x1") && node.has("y1") && node.has("x2") && node.has("y2")) {
                collector.add(new double[]{
                        node.get("x1").asDouble(),
                        node.get("y1").asDouble(),
                        node.get("x2").asDouble(),
                        node.get("y2").asDouble()
                });
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                extractBoxesFromJsonNode(fields.next().getValue(), collector);
            }
        }
    }

    public static String readString(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull()) {
                if (v.isTextual() || v.isNumber() || v.isBoolean()) {
                    String s = v.asText().trim();
                    if (!s.isEmpty()) return s;
                }
            }
        }
        // Case-insensitive / normalized underscore fallback
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String fieldNorm = entry.getKey().replace("_", "").toLowerCase();
            for (String key : keys) {
                if (fieldNorm.equals(key.replace("_", "").toLowerCase())) {
                    JsonNode v = entry.getValue();
                    if (v != null && !v.isNull()) {
                        String s = v.asText().trim();
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        }
        return null;
    }

    public static int readInt(JsonNode node, int defaultValue, String... keys) {
        String raw = readString(node, keys);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            if (raw.contains(".")) {
                return (int) Math.round(Double.parseDouble(raw));
            }
            return Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean readBoolean(JsonNode node, boolean defaultValue, String... keys) {
        String raw = readString(node, keys);
        if (raw == null || raw.isBlank()) return defaultValue;
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) {
            return false;
        }
        return defaultValue;
    }

    /**
     * Parses a dual-output response from Meta Muse Spark Open into:
     * 1) displayReport: concise, visually formatted Markdown for the dashboard UI
     * 2) spokenSummary: complete, natural-language narration for TTS with zero missing facts,
     *    no headings/markdown, and natural time expressions ("at the beginning / second zero" instead of "00:00").
     */
    public static DualMuseOutput parseDualMuseOutput(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return new DualMuseOutput(
                    "",
                    "No anomalies requiring intervention were detected at the hive today."
            );
        }

        String trimmed = rawOutput.trim();

        // 1. Check if the response is a JSON object {"report": "...", "spokenSummary": "..."}
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode root = MAPPER.readTree(trimmed);
                if (root != null && root.isObject()) {
                    String rep = readString(root, "report", "displayReport", "display_report", "markdown", "text");
                    String spk = readString(root, "spokenSummary", "spoken_summary", "spoken", "voice", "narration");
                    if (rep != null && !rep.isBlank()) {
                        String cleanSpoken = (spk != null && !spk.isBlank())
                                ? sanitizeForNaturalSpeech(spk)
                                : sanitizeForNaturalSpeech(rep);
                        return new DualMuseOutput(rep.trim(), cleanSpoken);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Check for explicit <REPORT>...</REPORT> and <SPOKEN>...</SPOKEN> tags
        Matcher reportMatcher = REPORT_TAG_REGEX.matcher(trimmed);
        Matcher spokenMatcher = SPOKEN_TAG_REGEX.matcher(trimmed);

        boolean hasSpokenTag = spokenMatcher.find();
        if (hasSpokenTag) {
            String spokenRaw = spokenMatcher.group(1).trim();
            String reportRaw;
            if (reportMatcher.find()) {
                reportRaw = reportMatcher.group(1).trim();
            } else {
                // Everything before <SPOKEN> is the display report
                int spokenStart = spokenMatcher.start();
                reportRaw = trimmed.substring(0, spokenStart)
                        .replaceAll("(?is)</?report>", "")
                        .trim();
            }
            if (reportRaw.isBlank()) {
                reportRaw = spokenRaw;
            }
            return new DualMuseOutput(reportRaw, sanitizeForNaturalSpeech(spokenRaw));
        }

        if (reportMatcher.find()) {
            String reportOnly = reportMatcher.group(1).trim();
            return new DualMuseOutput(reportOnly, sanitizeForNaturalSpeech(reportOnly));
        }

        // 3. Check for a Markdown heading split (e.g. "### Spoken Summary" or "--- SPOKEN ---")
        Matcher splitMatcher = SPOKEN_HEADING_SPLIT_REGEX.matcher(trimmed);
        if (splitMatcher.find()) {
            String reportPart = trimmed.substring(0, splitMatcher.start()).trim();
            String spokenPart = trimmed.substring(splitMatcher.end()).trim();
            if (!reportPart.isBlank() && !spokenPart.isBlank()) {
                return new DualMuseOutput(reportPart, sanitizeForNaturalSpeech(spokenPart));
            }
        }

        // 4. Fallback: single Markdown report -> derive complete natural spoken summary without truncation
        return new DualMuseOutput(trimmed, sanitizeForNaturalSpeech(trimmed));
    }

    /**
     * Converts any text or spoken block into clean, natural spoken prose suitable for TTS:
     * - Replaces MM:SS timestamps (e.g. 00:00 -> second zero, 00:20 -> second twenty)
     * - Strips Markdown headings, bullets, backticks, raw event IDs, and pixel brackets
     * - Preserves all factual sentences without arbitrary character truncation.
     */
    public static String sanitizeForNaturalSpeech(String input) {
        if (input == null || input.isBlank()) {
            return "No anomalies requiring intervention were detected at the hive today.";
        }

        String text = input
                .replaceAll("(?is)</?(?:report|spoken|spoken_summary)>", " ")
                // Convert MM:SS timestamps into natural spoken phrases
                .replaceAll("(?i)\\bat\\s+00:00\\b", "at the beginning of the recording")
                .replaceAll("\\b00:00\\b", "second zero")
                .replaceAll("(?i)\\bat\\s+00:20\\b", "at second twenty")
                .replaceAll("\\b00:20\\b", "second twenty")
                .replaceAll("(?i)\\bat\\s+00:35\\b", "at second thirty-five")
                .replaceAll("\\b00:35\\b", "second thirty-five")
                .replaceAll("(?i)\\bat\\s+00:39\\b", "at second thirty-nine")
                .replaceAll("\\b00:39\\b", "second thirty-nine");

        // Convert any remaining MM:SS timestamps into natural spoken words
        Matcher tsMatcher = MM_SS_TIMESTAMP_REGEX.matcher(text);
        StringBuilder tsBuffer = new StringBuilder();
        while (tsMatcher.find()) {
            int mins = Integer.parseInt(tsMatcher.group(1));
            int secs = Integer.parseInt(tsMatcher.group(2));
            String replacement;
            if (mins == 0 && secs == 0) {
                replacement = "second zero";
            } else if (mins == 0) {
                replacement = "second " + secs;
            } else if (secs == 0) {
                replacement = mins + (mins == 1 ? " minute" : " minutes");
            } else {
                replacement = mins + (mins == 1 ? " minute " : " minutes ") + secs + " seconds";
            }
            tsMatcher.appendReplacement(tsBuffer, replacement);
        }
        tsMatcher.appendTail(tsBuffer);
        text = tsBuffer.toString();

        // Replace raw event IDs, pixel coordinate arrays, and technical symbols with natural speech
        text = text
                .replaceAll("\\(\\s*`?evt_[a-zA-Z0-9_]+`?\\s*\\)", "")
                .replaceAll("\\b`?evt_[a-zA-Z0-9_]+`?\\b", "")
                .replaceAll("\\[\\d+(?:,\\s*\\d+)+\\]", "")
                .replaceAll("(?i)\\bmulti_bee_interaction_cluster\\b", "multi-bee interaction cluster")
                .replaceAll("(?i)\\bsingle_anomalous_honeybee\\b", "single honeybee activity")
                .replaceAll("(?i)\\bpotential_non_bee_anomaly\\b", "potential non-bee anomaly")
                .replaceAll("(\\d+(?:\\.\\d+)?)\\s*σ", "$1 sigma")
                .replaceAll("(?<=\\d)\\s*[–-]\\s*(?=\\d)", " to ")
                .replaceAll("->", ", ")
                // Strip Markdown headings and turn list lines into clean sentences
                .replaceAll("(?m)^\\s*#+\\s*(.*)$", "$1.")
                .replaceAll("(?m)^\\s*[-*•]+\\s*", "")
                .replaceAll("(?m)^\\s*---+$", "")
                .replace('_', ' ')
                .replaceAll("[*`~()\\[\\]\"]", " ")
                .replaceAll("\\.\\s*\\.", ".")
                .replaceAll("\\s+", " ")
                .trim();

        return text;
    }
}

