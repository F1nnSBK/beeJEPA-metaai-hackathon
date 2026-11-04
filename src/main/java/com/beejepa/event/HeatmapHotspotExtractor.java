package com.beejepa.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Measures the V-JEPA hotspot of an event directly from the rendered dataset video.
 *
 * <p>The stitched dataset videos are 1080x960 side-by-side frames: the left panel shows the
 * original camera, the right panel shows the identical camera image with the V-JEPA 43x43 token
 * overlay drawn on top. Subtracting the left panel from the right panel therefore isolates the
 * overlay itself. The overlay energy is aggregated on the native 43x43 token grid.
 *
 * <p>The hotspot is the 5x3 token window with the highest overlay energy inside the landing-board
 * band (grid rows 15..22). This band was measured on the dataset frames: rows 0..14 never carry
 * overlay (mean |R-L| of about 5), the board starts at row 15 and ends at row 22, rows 23..42 are
 * grass. If no token on the board exceeds the per-frame noise floor, no hotspot is reported.
 */
public final class HeatmapHotspotExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(HeatmapHotspotExtractor.class);

    public static final int FRAME_WIDTH = 1080;
    public static final int FRAME_HEIGHT = 960;
    public static final int HEADER_HEIGHT_PX = 35;
    public static final int PANEL_WIDTH_PX = 540;
    public static final int GRID = 43;
    public static final int BOARD_ROW_START = 15;
    public static final int BOARD_ROW_END = 22; // inclusive
    /** Rows that never carry overlay in the dataset; used to estimate the per-frame noise floor. */
    public static final int NOISE_ROW_END = 13; // inclusive
    public static final int WINDOW_COLS = 5;
    public static final int WINDOW_ROWS = 3;

    private HeatmapHotspotExtractor() {}

    public record MeasuredHotspot(
            int gridX,
            int gridY,
            double normCenterX,
            double normCenterY,
            int[] boxPx, // [y1, x1, y2, x2] in 1080x960 frame coordinates (left camera panel)
            int activeBoardCells,
            double noiseFloor
    ) {
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
                        new int[] {Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7])},
                        Integer.parseInt(p[8]), Double.parseDouble(p[9])));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /** Extracts the frame at {@code seconds} from {@code video} and measures the board hotspot. */
    public static Optional<MeasuredHotspot> measure(Path video, double seconds, String ffmpegBinary) {
        if (video == null || !Files.isRegularFile(video)) {
            return Optional.empty();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegBinary, "-nostdin", "-loglevel", "error",
                    "-ss", String.format(Locale.US, "%.3f", Math.max(0.0, seconds)),
                    "-i", video.toString(),
                    "-vframes", "1", "-f", "image2pipe", "-vcodec", "png", "-");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            byte[] png = process.getInputStream().readAllBytes();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || png.length == 0) {
                return Optional.empty();
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            return measure(img);
        } catch (Exception e) {
            LOG.warn("Hotspot measurement failed for {} @ {}s: {}", video, seconds, e.getMessage());
            return Optional.empty();
        }
    }

    /** Measures the board hotspot from an already decoded 1080x960 side-by-side frame. */
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
                Math.round(threshold * 1000.0) / 1000.0));
    }

    /** Mean |right - left| (summed over RGB) per token cell of the 43x43 grid. */
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
