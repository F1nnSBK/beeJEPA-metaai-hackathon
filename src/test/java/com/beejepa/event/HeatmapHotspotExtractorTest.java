package com.beejepa.event;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Optional;

class HeatmapHotspotExtractorTest {

    @Test
    void testIdenticalLeftAndRightPanelsReturnEmpty() {
        BufferedImage img = new BufferedImage(1080, 960, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(80, 75, 70));
        g.fillRect(0, 0, 1080, 960);
        g.dispose();

        Optional<HeatmapHotspotExtractor.MeasuredHotspot> res = HeatmapHotspotExtractor.measure(img);
        Assertions.assertTrue(res.isEmpty(), "Identical panels have zero overlay energy and must return empty");
    }

    @Test
    void testBoardOverlayBlockIsMeasuredAtMatchingGridPosition() {
        BufferedImage img = new BufferedImage(1080, 960, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(80, 75, 70));
        g.fillRect(0, 0, 1080, 960);

        // Draw an orange overlay block on the right panel inside board row 18, column 20..22
        int h = 960 - HeatmapHotspotExtractor.HEADER_HEIGHT_PX;
        int cellX = (int) Math.round(20 * (540.0 / 43.0));
        int cellY = HeatmapHotspotExtractor.HEADER_HEIGHT_PX + (int) Math.round(18 * (h / 43.0));
        g.setColor(new Color(240, 110, 40));
        g.fillRect(540 + cellX, cellY, 36, 40);
        g.dispose();

        Optional<HeatmapHotspotExtractor.MeasuredHotspot> res = HeatmapHotspotExtractor.measure(img);
        Assertions.assertTrue(res.isPresent(), "Overlay on landing-board row 18 must be detected");
        HeatmapHotspotExtractor.MeasuredHotspot hs = res.get();
        Assertions.assertTrue(hs.gridY() >= 15 && hs.gridY() <= 22, "GridY must lie inside the landing-board band: " + hs.gridY());
        Assertions.assertTrue(hs.gridX() >= 18 && hs.gridX() <= 24, "GridX must match the injected column: " + hs.gridX());
        Assertions.assertTrue(hs.activeBoardCells() > 0);

        Optional<HeatmapHotspotExtractor.MeasuredHotspot> roundTrip =
                HeatmapHotspotExtractor.MeasuredHotspot.deserialize(hs.serialize());
        Assertions.assertEquals(hs.gridX(), roundTrip.orElseThrow().gridX());
        Assertions.assertEquals(hs.gridY(), roundTrip.orElseThrow().gridY());
    }
}

