package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.beejepa.muse.TolerantPayloadParser.ParsedFovealArgs;
import com.beejepa.muse.TolerantPayloadParser.ParsedSamConceptArgs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class TolerantPayloadParserTest {

    private static List<EventSpatialSummary> sampleEvents() {
        return List.of(
                new EventSpatialSummary("evt_rec2125_seq000_f0000", "00:00", 16.05, "CRITICAL", 38, 15,
                        "multi_bee_interaction_cluster", List.of(), "f", "c"),
                new EventSpatialSummary("evt_rec2125_seq000_f1217", "00:20", 13.72, "CRITICAL", 35, 12,
                        "multi_bee_interaction_cluster", List.of(), "f", "c"),
                new EventSpatialSummary("evt_rec2125_seq001_f0320", "00:35", 12.60, "HIGH", 26, 6,
                        "multi_bee_interaction_cluster", List.of(), "f", "c"));
    }

    @Test
    void testParseFovealHotspotArgsSnakeAndCamelCaseAndFuzzyResolution() {
        List<EventSpatialSummary> events = sampleEvents();

        // Snake case with stringified numbers and shorthand event_id ("f1217")
        String rawSnake = "{\"event_id\": \"f1217\", \"target_x\": \"250\", \"target_y\": \"420\", \"fovea_radius_px\": \"85\", \"extra_field\": 123}";
        ParsedFovealArgs args1 = TolerantPayloadParser.parseFovealHotspotArgs(rawSnake, events);
        Assertions.assertEquals("evt_rec2125_seq000_f1217", args1.resolvedEventId());
        Assertions.assertEquals(250, args1.targetX());
        Assertions.assertEquals(420, args1.targetY());
        Assertions.assertEquals(85, args1.foveaRadiusPx());

        // CamelCase with timestamp event lookup ("00:35") and radius clamping
        String rawCamel = "{\"eventId\": \"00:35\", \"targetX\": 210, \"targetY\": 395, \"foveaRadiusPx\": 900}";
        ParsedFovealArgs args2 = TolerantPayloadParser.parseFovealHotspotArgs(rawCamel, events);
        Assertions.assertEquals("evt_rec2125_seq001_f0320", args2.resolvedEventId());
        Assertions.assertEquals(210, args2.targetX());
        Assertions.assertEquals(395, args2.targetY());
        Assertions.assertEquals(340, args2.foveaRadiusPx()); // Clamped to max 340

        // Malformed JSON falls back safely to first event
        ParsedFovealArgs args3 = TolerantPayloadParser.parseFovealHotspotArgs("{corrupted_json", events);
        Assertions.assertEquals("evt_rec2125_seq000_f0000", args3.resolvedEventId());
        Assertions.assertEquals(60, args3.foveaRadiusPx());
    }

    @Test
    void testParseSamConceptArgs() {
        List<EventSpatialSummary> events = sampleEvents();
        String raw = "{\"id\": \"f1217\", \"prompt\": \"wasp\", \"include_baseline_bee_count\": \"false\"}";
        ParsedSamConceptArgs args = TolerantPayloadParser.parseSamConceptArgs(raw, events);
        Assertions.assertEquals("evt_rec2125_seq000_f1217", args.resolvedEventId());
        Assertions.assertEquals("wasp", args.conceptPrompt());
        Assertions.assertFalse(args.includeBaselineBeeCount());
    }

    @Test
    void testParseSamBoxesTokenAndJsonAndCoordinateScaling() {
        // 1. Standard <|box;x1=...;y1=...;x2=...;y2=...| > token format in [0..1000]
        // scaled to 688x688
        String tokenOutput = "<|box;x1=500;y1=500;x2=1000;y2=1000|><|object;0|><|mask;rle|>";
        List<BoundingBox> scaledTokens = TolerantPayloadParser.parseSamBoxes(tokenOutput, 688, 688, true, false);
        Assertions.assertEquals(1, scaledTokens.size());
        Assertions.assertEquals(new BoundingBox(344, 344, 688, 688), scaledTokens.get(0));

        // 2. Live SAM 3.1 token with explicit ;w=688;h=688 preserves pixel coordinates
        // and passes landing-board filter
        String liveSamToken = "<0f>0<|box;x1=290;y1=385;x2=318;y2=415;w=688;h=688|><|mask;x=0;y=0;data=31,29|>";
        List<BoundingBox> liveBoxes = TolerantPayloadParser.parseSamBoxes(liveSamToken, 688, 688, true, true);
        Assertions.assertEquals(1, liveBoxes.size());
        Assertions.assertEquals(new BoundingBox(290, 385, 318, 415), liveBoxes.get(0));

        // 3. JSON array of boxes already in pixel coordinates (<= 688)
        String jsonArray = "{\"boxes\": [[180, 355, 220, 395], {\"x1\": 240, \"y1\": 380, \"x2\": 275, \"y2\": 420}]}";
        List<BoundingBox> jsonBoxes = TolerantPayloadParser.parseSamBoxes(jsonArray, 688, 688, false, false);
        Assertions.assertEquals(2, jsonBoxes.size());
        Assertions.assertEquals(new BoundingBox(180, 355, 220, 395), jsonBoxes.get(0));
        Assertions.assertEquals(new BoundingBox(240, 380, 275, 420), jsonBoxes.get(1));
    }

    @Test

    void testManifestBoxPxCoordinateFix() {
        // Manifest box_px is [ymin, xmin, ymax, xmax] = [351, 178, 487, 314]
        BoundingBox fromManifest = SamGeometry.fromManifestBoxPx(new int[] { 351, 178, 487, 314 }, 688, 688);
        Assertions.assertEquals(new BoundingBox(178, 351, 314, 487), fromManifest);
    }

    @Test
    void testParseDualMuseOutputAndNaturalSpokenSanitization() {
        String dualRaw = "<REPORT>\n"
                + "### Flight Traffic Overview\n"
                + "- **00:00 (`16.05σ`):** 38 bees on board, 15 inside hotspot.\n"
                + "- **00:20 (`13.72σ`):** 35 bees on board, 12 inside hotspot.\n"
                + "</REPORT>\n\n"
                + "<SPOKEN>\n"
                + "At the beginning of the recording at second zero, thirty-eight bees were on the landing board with fifteen inside the hotspot. At second twenty, thirty-five bees were on the board with twelve inside the hotspot.\n"
                + "</SPOKEN>";

        TolerantPayloadParser.DualMuseOutput parsed = TolerantPayloadParser.parseDualMuseOutput(dualRaw);
        Assertions.assertTrue(parsed.displayReport().contains("### Flight Traffic Overview"));
        Assertions.assertFalse(parsed.displayReport().contains("<SPOKEN>"));
        Assertions.assertFalse(parsed.displayReport().contains("<REPORT>"));

        Assertions.assertTrue(parsed.spokenSummary().contains("thirty-eight bees"));
        Assertions.assertTrue(parsed.spokenSummary().contains("second twenty"));
        Assertions.assertFalse(parsed.spokenSummary().contains("###"));
        Assertions.assertFalse(parsed.spokenSummary().contains("00:00"));

        // Fallback when raw Markdown without <SPOKEN> is passed: converts 00:00 ->
        // second zero and strips headings
        String legacyMd = "### Summary\n- Peak at 00:00 (evt_rec2125_seq000_f0000) with 38 bees and 00:20 with 35 bees.";
        TolerantPayloadParser.DualMuseOutput fallback = TolerantPayloadParser.parseDualMuseOutput(legacyMd);
        Assertions.assertFalse(fallback.spokenSummary().contains("00:00"));
        Assertions.assertFalse(fallback.spokenSummary().contains("00:20"));
        Assertions.assertFalse(fallback.spokenSummary().contains("evt_rec2125"));
        Assertions.assertTrue(fallback.spokenSummary().contains("second zero"));
        Assertions.assertTrue(
                fallback.spokenSummary().contains("second 20") || fallback.spokenSummary().contains("second twenty"));
    }
}
