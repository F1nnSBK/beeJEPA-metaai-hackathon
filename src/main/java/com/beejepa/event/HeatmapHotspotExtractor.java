package com.beejepa.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Extracts spatial attention hotspots from side-by-side V-JEPA overlay frames
 * on a 43x43 token grid.
 */
public final class HeatmapHotspotExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(HeatmapHotspotExtractor.class);

    public static final int FRAME_WIDTH = 1080;
    public static final int FRAME_HEIGHT = 960;
    public static final int HEADER_HEIGHT_PX = 35;
    public static final int PANEL_WIDTH_PX = 540;
    public static final int GRID = 43;
    public static final int BOARD_ROW_START = 15;
    public static final int BOARD_ROW_END = 22;
    public static final int NOISE_ROW_END = 13;
    public static final int WINDOW_COLS = 5;
    public static final int WINDOW_ROWS = 3;

    private HeatmapHotspotExtractor() {
    }

    public record MeasuredHotspot(
            int gridX,
            int gridY,
            double normCenterX,
            double normCenterY,
            int[] boxPx,
            int activeBoardCells,
            double noiseFloor) {
        public String serialize() {
            return String.format(Locale.US, "%d,%d,%.5f,%.5f,%d,%d,%d,%d,%d,%.3f",
                    gridX, gridY, normCenterX, normCenterY,
                    boxPx[0], boxPx[1], boxPx[2], boxPx[3], activeBoardCells, noiseFloor);
        }

        public static Optional<MeasuredHotspot> deserialize(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String[] p = raw.split(",");
            if (p.length != 10) {
                return Optional.empty();
            }
            try {
                return Optional.of(new MeasuredHotspot(
                        Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        new int[] { Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                                Integer.parseInt(p[7]) },
                        Integer.parseInt(p[8]), Double.parseDouble(p[9])));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * Decodes a video frame at {@code seconds} and measures the landing-board
     * hotspot.
     */
    public static Optional<MeasuredHotspot> measure(Path video, double seconds, String ffmpegBinary) {
        try {
            return VideoFrameExtractor.extractImage(video, seconds, ffmpegBinary)
                    .flatMap(HeatmapHotspotExtractor::measure);
        } catch (Exception e) {
            LOG.warn("Hotspot measurement failed for {} @ {}s: {}", video, seconds, e.getMessage());
            return Optional.empty();
        }
    }

    /** Locates the 5x3 token window with maximal overlay energy across the landing-board band. */
    public static Optional<MeasuredHotspot> measure(BufferedImage img) {
        if (img == null || img.getWidth() != FRAME_WIDTH || img.getHeight() != FRAME_HEIGHT) {
            return Optional.empty();
        }
        double[][] grid = overlayEnergyGrid(img);

        double[] noiseCells = new double[(NOISE_ROW_END + 1) * GRID];
        int idx = 0;
        for (int r = 0; r <= NOISE_ROW_END; r++) {
            for (int c = 0; c < GRID; c++) {
                noiseCells[idx++] = grid[r][c];
            }
        }
        Arrays.sort(noiseCells);
        double noiseP99 = noiseCells[(int) Math.floor(0.99 * (noiseCells.length - 1))];
        double threshold = Math.max(15.0, 3.0 * noiseP99);

        int activeBoardCells = 0;
        for (int r = BOARD_ROW_START; r <= BOARD_ROW_END; r++) {
            for (int c = 0; c < GRID; c++) {
                if (grid[r][c] > threshold) {
                    activeBoardCells++;
                }
            }
        }
        if (activeBoardCells == 0) {
            return Optional.empty();
        }

        double best = -1.0;
        int bestRow = -1;
        int bestCol = -1;
        for (int r = BOARD_ROW_START; r <= BOARD_ROW_END - WINDOW_ROWS + 1; r++) {
            for (int c = 0; c <= GRID - WINDOW_COLS; c++) {
                double sum = 0.0;
                for (int dr = 0; dr < WINDOW_ROWS; dr++) {
                    for (int dc = 0; dc < WINDOW_COLS; dc++) {
                        double v = grid[r + dr][c + dc];
                        if (v > threshold) {
                            sum += v;
                        }
                    }
                }
                if (sum > best) {
                    best = sum;
                    bestRow = r;
                    bestCol = c;
                }
            }
        }
        if (bestRow < 0 || best <= 0.0) {
            return Optional.empty();
        }

        double cellW = PANEL_WIDTH_PX / (double) GRID;
        double cellH = (FRAME_HEIGHT - HEADER_HEIGHT_PX) / (double) GRID;
        int x1 = (int) Math.round(bestCol * cellW);
        int x2 = (int) Math.round((bestCol + WINDOW_COLS) * cellW);
        int y1 = (int) Math.round(HEADER_HEIGHT_PX + bestRow * cellH);
        int y2 = (int) Math.round(HEADER_HEIGHT_PX + (bestRow + WINDOW_ROWS) * cellH);
        int gridX = bestCol + WINDOW_COLS / 2;
        int gridY = bestRow + WINDOW_ROWS / 2;
        double normCx = ((x1 + x2) / 2.0) / PANEL_WIDTH_PX;
        double normCy = ((y1 + y2) / 2.0) / FRAME_HEIGHT;
        return Optional.of(new MeasuredHotspot(
                gridX, gridY,
                Math.round(normCx * 100000.0) / 100000.0,
                Math.round(normCy * 100000.0) / 100000.0,
                new int[] {y1, x1, y2, x2},
                activeBoardCells,
                Math.round(threshold * 1000.0) / 10000.0));
    }

    /**
     * Pools the 8-row x 43-column landing-board overlay energy band into a 64-float
     * feature vector.
     */
    public static float[] extractBoardEnergyLatent64(BufferedImage img) {
        float[] out = new float[64];
        if (img == null || img.getWidth() != FRAME_WIDTH || img.getHeight() != FRAME_HEIGHT) {
            return out;
        }
        double[][] grid = overlayEnergyGrid(img);
        int idx = 0;
        for (int r = BOARD_ROW_START; r <= BOARD_ROW_END && r < BOARD_ROW_START + 8; r++) {
            for (int bin = 0; bin < 8; bin++) {
                int c0 = (int) Math.floor(bin * GRID / 8.0);
                int c1 = Math.max(c0 + 1, (int) Math.floor((bin + 1) * GRID / 8.0));
                double sum = 0.0;
                for (int c = c0; c < c1 && c < GRID; c++) {
                    sum += grid[r][c];
                }
                out[idx++] = (float) (sum / (c1 - c0));
            }
        }
        return out;
    }

    /**
     * Computes mean absolute RGB difference between right and left panels per 43x43
     * token cell.
     */
    static double[][] overlayEnergyGrid(BufferedImage img) {
        int h = FRAME_HEIGHT - HEADER_HEIGHT_PX;
        int[] rowStarts = new int[GRID + 1];
        int[] colStarts = new int[GRID + 1];
        for (int i = 0; i <= GRID; i++) {
            rowStarts[i] = (int) Math.floor(i * h / (double) GRID);
            colStarts[i] = (int) Math.floor(i * PANEL_WIDTH_PX / (double) GRID);
        }
        int[] left = img.getRGB(0, HEADER_HEIGHT_PX, PANEL_WIDTH_PX, h, null, 0, PANEL_WIDTH_PX);
        int[] right = img.getRGB(PANEL_WIDTH_PX, HEADER_HEIGHT_PX, PANEL_WIDTH_PX, h, null, 0, PANEL_WIDTH_PX);
        double[][] grid = new double[GRID][GRID];
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                long sum = 0;
                int count = 0;
                for (int y = rowStarts[r]; y < rowStarts[r + 1]; y++) {
                    int base = y * PANEL_WIDTH_PX;
                    for (int x = colStarts[c]; x < colStarts[c + 1]; x++) {
                        int a = left[base + x];
                        int b = right[base + x];
                        sum += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                                + Math.abs((a & 0xFF) - (b & 0xFF));
                        count++;
                    }
                }
                grid[r][c] = count > 0 ? sum / (double) count : 0.0;
            }
        }
        return grid;
    }
}
