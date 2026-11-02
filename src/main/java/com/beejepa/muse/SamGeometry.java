package com.beejepa.muse;

import io.micronaut.serde.annotation.Serdeable;
import java.util.ArrayList;
import java.util.List;

public final class SamGeometry {

    public static final int FRAME_SIZE = 688;
    public static final int HEADER_HEIGHT_PX = 35;
    public static final int PANEL_WIDTH_PX = 540;
    public static final int FULL_FRAME_HEIGHT_PX = 960;
    public static final int LEFT_PANEL_MAX_X = 535;
    public static final int BOARD_MIN_Y = 372;
    public static final int BOARD_MAX_Y = 518;

    private SamGeometry() {}

    @Serdeable
    public record BoundingBox(int x1, int y1, int x2, int y2) {
        public int width() { return Math.max(1, x2 - x1); }
        public int height() { return Math.max(1, y2 - y1); }
        public int area() { return width() * height(); }
        public double centerX() { return 0.5 * (x1 + x2); }
        public double centerY() { return 0.5 * (y1 + y2); }

        public boolean isOnLandingBoard() {
            return centerX() <= LEFT_PANEL_MAX_X
                    && centerY() >= BOARD_MIN_Y
                    && centerY() <= BOARD_MAX_Y;
        }

        public List<Integer> toList() {
            return List.of(x1, y1, x2, y2);
        }
    }

    @Serdeable
    public record ContainedBee(int beeIndex, List<Integer> box, double containmentRatio) {}

    @Serdeable
    public record EventSpatialSummary(
            String eventId,
            String timestamp,
            double surpriseScoreSigma,
            String severity,
            int landingBoardBeeCount,
            int beesInsideHotspotCount,
            String geometricPreClassification,
            List<ContainedBee> containedBees,
            List<List<Integer>> boardBees,
            String frameBase64,
            String cropBase64
    ) {
        public EventSpatialSummary(
                String eventId,
                String timestamp,
                double surpriseScoreSigma,
                String severity,
                int landingBoardBeeCount,
                int beesInsideHotspotCount,
                String geometricPreClassification,
                List<ContainedBee> containedBees,
                String frameBase64,
                String cropBase64
        ) {
            this(
                    eventId,
                    timestamp,
                    surpriseScoreSigma,
                    severity,
                    landingBoardBeeCount,
                    beesInsideHotspotCount,
                    geometricPreClassification,
                    containedBees,
                    containedBees != null ? containedBees.stream().map(ContainedBee::box).toList() : List.of(),
                    frameBase64,
                    cropBase64
            );
        }
    }

    public static BoundingBox defaultEntranceHotspot() {
        int cx = 315;
        int cy = 426;
        int halfW = 80;
        int halfH = 54;
        return new BoundingBox(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
    }

    public static BoundingBox hotspotFromNormCenter(double normCenterX, double normCenterY) {
        return hotspotFromNormCenter(normCenterX, normCenterY, FRAME_SIZE, FRAME_SIZE);
    }

    /**
     * Converts normalized V-JEPA center coordinates (normCenterX in [0..1] of PANEL_WIDTH_PX=540,
     * normCenterY in [0..1] of FULL_FRAME_HEIGHT_PX=960) into pixel coordinates on the top-left camera panel.
     */
    public static BoundingBox hotspotFromNormCenter(double normCenterX, double normCenterY, int imageWidth, int imageHeight) {
        int maxW = Math.min(PANEL_WIDTH_PX, imageWidth > 0 ? imageWidth : FRAME_SIZE);
        int maxH = imageHeight > 0 ? imageHeight : FRAME_SIZE;
        int cx = (normCenterX > 0.01)
                ? Math.clamp((int) Math.round(normCenterX * PANEL_WIDTH_PX), 60, maxW - 60)
                : 315;
        int cy = (normCenterY > 0.01)
                ? Math.clamp((int) Math.round(normCenterY * FULL_FRAME_HEIGHT_PX), HEADER_HEIGHT_PX + 54, maxH - 54)
                : 426;
        int halfW = 68;
        int halfH = 68;
        int x1 = Math.max(0, cx - halfW);
        int y1 = Math.max(HEADER_HEIGHT_PX, cy - halfH);
        int x2 = Math.min(maxW, cx + halfW);
        int y2 = Math.min(maxH, cy + halfH);
        return new BoundingBox(x1, y1, x2, y2);
    }

    /**
     * Converts manifest.json box_px [ymin, xmin, ymax, xmax] into BoundingBox(x1, y1, x2, y2).
     */
    public static BoundingBox fromManifestBoxPx(int[] boxPx, int imageWidth, int imageHeight) {
        if (boxPx == null || boxPx.length < 4) {
            return defaultEntranceHotspot();
        }
        int maxW = Math.min(PANEL_WIDTH_PX, imageWidth > 0 ? imageWidth : FRAME_SIZE);
        int maxH = imageHeight > 0 ? imageHeight : FRAME_SIZE;
        int y1 = Math.clamp(boxPx[0], HEADER_HEIGHT_PX, maxH - 10);
        int x1 = Math.clamp(boxPx[1], 0, maxW - 10);
        int y2 = Math.clamp(boxPx[2], y1 + 10, maxH);
        int x2 = Math.clamp(boxPx[3], x1 + 10, maxW);
        return new BoundingBox(x1, y1, x2, y2);
    }

    public static double computeBeeContainment(BoundingBox hotspot, BoundingBox bee) {
        int ix1 = Math.max(hotspot.x1(), bee.x1());
        int iy1 = Math.max(hotspot.y1(), bee.y1());
        int ix2 = Math.min(hotspot.x2(), bee.x2());
        int iy2 = Math.min(hotspot.y2(), bee.y2());
        int intersection = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
        double ratio = intersection / (double) bee.area();
        return Math.round(ratio * 10000.0) / 10000.0;
    }

    public static List<ContainedBee> findContainedBees(BoundingBox hotspot, List<BoundingBox> boardBees) {
        List<ContainedBee> contained = new ArrayList<>();
        for (int i = 0; i < boardBees.size(); i++) {
            BoundingBox bee = boardBees.get(i);
            double ioa = computeBeeContainment(hotspot, bee);
            if (ioa >= 0.25) {
                contained.add(new ContainedBee(i, bee.toList(), ioa));
            }
        }
        return contained;
    }

    public static double computeIntersectionOverUnion(BoundingBox a, BoundingBox b) {
        if (a == null || b == null) {
            return 0.0;
        }
        int ix1 = Math.max(a.x1(), b.x1());
        int iy1 = Math.max(a.y1(), b.y1());
        int ix2 = Math.min(a.x2(), b.x2());
        int iy2 = Math.min(a.y2(), b.y2());
        int inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
        int union = a.area() + b.area() - inter;
        if (union <= 0) {
            return 0.0;
        }
        return Math.round((inter / (double) union) * 10000.0) / 10000.0;
    }

    public static double computeCenterErrorPx(BoundingBox a, BoundingBox b) {
        if (a == null || b == null) {
            return 0.0;
        }
        double dist = Math.hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY());
        return Math.round(dist * 10.0) / 10.0;
    }

    /**
     * Computes the fraction of the hotspot bounding box area explained by segmented
     * honeybee instances. A low ratio (< 0.22) during a high-surprise event with >= 2
     * guard bees indicates a large unsegmented mass (e.g., a hornet or foreign intruder
     * being grappled in defensive bee-balling).
     */
    public static double computeHotspotBeeCoverageRatio(BoundingBox hotspot, List<ContainedBee> contained) {
        if (hotspot == null || contained == null || contained.isEmpty()) {
            return 0.0;
        }
        double beeIntersectionSum = 0.0;
        for (ContainedBee cb : contained) {
            List<Integer> b = cb.box();
            if (b != null && b.size() >= 4) {
                int bw = Math.max(1, b.get(2) - b.get(0));
                int bh = Math.max(1, b.get(3) - b.get(1));
                double ioaArea = bw * bh * Math.clamp(cb.containmentRatio(), 0.0, 1.0);
                int ix1 = Math.max(hotspot.x1(), b.get(0));
                int iy1 = Math.max(hotspot.y1(), b.get(1));
                int ix2 = Math.min(hotspot.x2(), b.get(2));
                int iy2 = Math.min(hotspot.y2(), b.get(3));
                int coordInter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
                beeIntersectionSum += Math.max(coordInter, ioaArea);
            }
        }
        double ratio = Math.min(1.0, beeIntersectionSum / (double) hotspot.area());
        return Math.round(ratio * 10000.0) / 10000.0;
    }

    public static String classifyHotspot(List<ContainedBee> contained) {
        return classifyHotspot(contained, null, 0.0);
    }

    public static String classifyHotspot(List<ContainedBee> contained, BoundingBox hotspot, double surpriseSigma) {
        if (contained.size() >= 2) {
            if (hotspot != null && surpriseSigma >= 12.5
                    && computeHotspotBeeCoverageRatio(hotspot, contained) < 0.22) {
                return "defensive_bee_balling_or_intruder_candidate";
            }
            return "multi_bee_interaction_cluster";
        }
        double maxIoa = contained.stream().mapToDouble(ContainedBee::containmentRatio).max().orElse(0.0);
        if (contained.size() == 1 && maxIoa >= 0.50) {
            return "single_anomalous_honeybee";
        }
        return "potential_non_bee_anomaly";
    }
}
