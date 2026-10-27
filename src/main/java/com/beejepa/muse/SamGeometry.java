package com.beejepa.muse;

import java.util.ArrayList;
import java.util.List;

public final class SamGeometry {

    public static final int FRAME_SIZE = 688;
    public static final int LEFT_PANEL_MAX_X = 535;
    public static final int BOARD_MIN_Y = 310;
    public static final int BOARD_MAX_Y = 440;

    private SamGeometry() {}

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

    public record ContainedBee(int beeIndex, List<Integer> box, double containmentRatio) {}

    public record EventSpatialSummary(
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
    ) {}

    public static BoundingBox defaultEntranceHotspot() {
        int cx = 315;
        int cy = 390;
        int halfW = 80;
        int halfH = 60;
        return new BoundingBox(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
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

    public static String classifyHotspot(List<ContainedBee> contained) {
        if (contained.size() >= 2) {
            return "multi_bee_interaction_cluster";
        }
        double maxIoa = contained.stream().mapToDouble(ContainedBee::containmentRatio).max().orElse(0.0);
        if (contained.size() == 1 && maxIoa >= 0.50) {
            return "single_anomalous_honeybee";
        }
        return "potential_non_bee_anomaly";
    }
}
