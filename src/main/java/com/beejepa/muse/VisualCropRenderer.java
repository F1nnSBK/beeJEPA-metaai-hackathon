package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.ContainedBee;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class VisualCropRenderer {

    private static final Color BOARD_BEE_COLOR = new Color(0x2E, 0xA0, 0x43);
    private static final Color CONTAINED_BEE_COLOR = new Color(0x00, 0xFF, 0x66);
    private static final Color HOTSPOT_COLOR = new Color(0xFF, 0x2A, 0x2A);

    private VisualCropRenderer() {}

    public record RenderedVisuals(String canonicalFrameB64, String annotatedOverviewB64, String upscaledCropB64) {}

    public static BufferedImage loadCanonicalView(Path imagePath) throws IOException {
        BufferedImage raw = ImageIO.read(imagePath.toFile());
        int w = Math.min(SamGeometry.FRAME_SIZE, raw.getWidth());
        int h = Math.min(SamGeometry.FRAME_SIZE, raw.getHeight());
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(raw, 0, 0, w, h, 0, 0, w, h, null);
        g.dispose();
        return copy;
    }

    public static RenderedVisuals renderOverlaysAndCrop(
            BufferedImage canonicalView,
            BoundingBox hotspot,
            List<BoundingBox> allBees,
            List<ContainedBee> containedBees,
            int padPx
    ) throws IOException {
        int w = canonicalView.getWidth();
        int h = canonicalView.getHeight();

        BufferedImage annotated = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = annotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(canonicalView, 0, 0, null);

        Set<List<Integer>> containedSet = new HashSet<>();
        for (ContainedBee cb : containedBees) {
            containedSet.add(cb.box());
        }

        g.setStroke(new BasicStroke(2.0f));
        g.setColor(BOARD_BEE_COLOR);
        for (BoundingBox b : allBees) {
            if (!containedSet.contains(b.toList())) {
                g.drawRect(b.x1(), b.y1(), b.width(), b.height());
            }
        }

        g.setStroke(new BasicStroke(3.0f));
        g.setColor(CONTAINED_BEE_COLOR);
        for (ContainedBee cb : containedBees) {
            List<Integer> b = cb.box();
            g.drawRect(b.get(0), b.get(1), b.get(2) - b.get(0), b.get(3) - b.get(1));
        }

        g.setColor(HOTSPOT_COLOR);
        g.drawRect(hotspot.x1(), hotspot.y1(), hotspot.width(), hotspot.height());
        int cx = (int) hotspot.centerX();
        int cy = (int) hotspot.centerY();
        g.drawLine(cx - 8, cy, cx + 8, cy);
        g.drawLine(cx, cy - 8, cx, cy + 8);
        g.dispose();

        int px1 = Math.max(0, hotspot.x1() - padPx);
        int py1 = Math.max(0, hotspot.y1() - padPx);
        int px2 = Math.min(w, hotspot.x2() + padPx);
        int py2 = Math.min(h, hotspot.y2() + padPx);

        BufferedImage subCrop = annotated.getSubimage(px1, py1, px2 - px1, py2 - py1);
        BufferedImage upscaledCrop = new BufferedImage(384, 384, BufferedImage.TYPE_INT_RGB);
        Graphics2D gCrop = upscaledCrop.createGraphics();
        gCrop.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gCrop.drawImage(subCrop, 0, 0, 384, 384, null);
        gCrop.dispose();

        return new RenderedVisuals(
                encodeJpegBase64(canonicalView, 0.92f),
                encodeJpegBase64(annotated, 0.92f),
                encodeJpegBase64(upscaledCrop, 0.92f)
        );
    }

    public static String encodeJpegBase64(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
