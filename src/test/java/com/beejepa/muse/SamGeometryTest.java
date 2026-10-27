package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.ContainedBee;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class SamGeometryTest {

    @Test
    void testBoundingBoxProperties() {
        BoundingBox box = new BoundingBox(100, 200, 150, 300);
        Assertions.assertEquals(50, box.width());
        Assertions.assertEquals(100, box.height());
        Assertions.assertEquals(5000, box.area());
        Assertions.assertEquals(125.0, box.centerX());
        Assertions.assertEquals(250.0, box.centerY());
        Assertions.assertEquals(List.of(100, 200, 150, 300), box.toList());
    }

    @Test
    void testLandingBoardFilter() {
        // Landing board ROI: cx <= 535, 310 <= cy <= 440
        BoundingBox onBoard = new BoundingBox(300, 350, 360, 410);
        Assertions.assertTrue(onBoard.isOnLandingBoard());

        BoundingBox offBoardRight = new BoundingBox(550, 350, 600, 410);
        Assertions.assertFalse(offBoardRight.isOnLandingBoard());

        BoundingBox offBoardTop = new BoundingBox(300, 100, 350, 150);
        Assertions.assertFalse(offBoardTop.isOnLandingBoard());

        BoundingBox offBoardBottom = new BoundingBox(300, 500, 350, 550);
        Assertions.assertFalse(offBoardBottom.isOnLandingBoard());
    }

    @Test
    void testComputeBeeContainmentIoA() {
        BoundingBox hotspot = new BoundingBox(200, 300, 400, 450); // w=200, h=150

        // Bee completely inside hotspot
        BoundingBox insideBee = new BoundingBox(250, 320, 290, 360); // 40x40 = 1600
        double ratioInside = SamGeometry.computeBeeContainment(hotspot, insideBee);
        Assertions.assertEquals(1.0, ratioInside);

        // Bee half inside hotspot
        BoundingBox halfBee = new BoundingBox(180, 320, 220, 360); // w=40, h=40, intersection with x in [200, 220] -> w=20, h=40 -> 800/1600 = 0.5
        double ratioHalf = SamGeometry.computeBeeContainment(hotspot, halfBee);
        Assertions.assertEquals(0.5, ratioHalf);

        // Bee completely outside
        BoundingBox outsideBee = new BoundingBox(50, 50, 90, 90);
        double ratioOutside = SamGeometry.computeBeeContainment(hotspot, outsideBee);
        Assertions.assertEquals(0.0, ratioOutside);
    }

    @Test
    void testFindContainedBeesAndClassification() {
        BoundingBox hotspot = SamGeometry.defaultEntranceHotspot(); // [235, 330, 395, 450]

        BoundingBox bee1 = new BoundingBox(250, 350, 290, 390); // inside
        BoundingBox bee2 = new BoundingBox(310, 370, 350, 410); // inside
        BoundingBox bee3 = new BoundingBox(10, 10, 40, 40);     // outside

        List<ContainedBee> contained = SamGeometry.findContainedBees(hotspot, List.of(bee1, bee2, bee3));
        Assertions.assertEquals(2, contained.size());
        Assertions.assertEquals(0, contained.get(0).beeIndex());
        Assertions.assertEquals(1, contained.get(1).beeIndex());

        // Multi-bee interaction classification
        String classMulti = SamGeometry.classifyHotspot(contained);
        Assertions.assertEquals("multi_bee_interaction_cluster", classMulti);

        // Single bee classification
        String classSingle = SamGeometry.classifyHotspot(List.of(contained.get(0)));
        Assertions.assertEquals("single_anomalous_honeybee", classSingle);

        // Potential non-bee anomaly (empty)
        String classNone = SamGeometry.classifyHotspot(List.of());
        Assertions.assertEquals("potential_non_bee_anomaly", classNone);
    }
}
