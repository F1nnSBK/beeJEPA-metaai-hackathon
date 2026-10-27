package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.ContainedBee;
import com.beejepa.muse.VisualCropRenderer.RenderedVisuals;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

class VisualCropRendererTest {

    @Test
    void testEncodeJpegBase64() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.YELLOW);
        g.fillRect(0, 0, 100, 100);
        g.dispose();

        String b64 = VisualCropRenderer.encodeJpegBase64(img, 0.9f);
        Assertions.assertNotNull(b64);
        Assertions.assertFalse(b64.isBlank());
    }

    @Test
    void testRenderOverlaysAndCrop() throws IOException {
        BufferedImage canonical = new BufferedImage(688, 688, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canonical.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, 688, 688);
        g.dispose();

        BoundingBox hotspot = SamGeometry.defaultEntranceHotspot();
        BoundingBox bee = new BoundingBox(310, 380, 350, 420);
        ContainedBee containedBee = new ContainedBee(0, bee.toList(), 1.0);

        RenderedVisuals visuals = VisualCropRenderer.renderOverlaysAndCrop(
                canonical,
                hotspot,
                List.of(bee),
                List.of(containedBee),
                35
        );

        Assertions.assertNotNull(visuals);
        Assertions.assertNotNull(visuals.canonicalFrameB64());
        Assertions.assertNotNull(visuals.annotatedOverviewB64());
        Assertions.assertNotNull(visuals.upscaledCropB64());
        Assertions.assertFalse(visuals.canonicalFrameB64().isBlank());
        Assertions.assertFalse(visuals.annotatedOverviewB64().isBlank());
        Assertions.assertFalse(visuals.upscaledCropB64().isBlank());
    }
}
