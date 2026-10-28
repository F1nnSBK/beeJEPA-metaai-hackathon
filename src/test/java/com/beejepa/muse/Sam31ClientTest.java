package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class Sam31ClientTest {

    @Test
    void testParseBoxesWithTokens() {
        Sam31Client client = new Sam31Client(new ObjectMapper());

        String rawResponse = "Here are the detections: <|box;x1=280;y1=390;x2=340;y2=450;label=bee|> "
                + "and another one: <|box;x1=600;y1=390;x2=650;y2=440;label=bee|>";

        // Without filter: should parse both boxes
        List<BoundingBox> allBoxes = client.parseBoxes(rawResponse, false);
        Assertions.assertEquals(2, allBoxes.size());
        Assertions.assertEquals(new BoundingBox(280, 390, 340, 450), allBoxes.get(0));
        Assertions.assertEquals(new BoundingBox(600, 390, 650, 440), allBoxes.get(1));

        // With landing board filter: box 2 (centerX=625 > 535) should be filtered out
        List<BoundingBox> boardBoxes = client.parseBoxes(rawResponse, true);
        Assertions.assertEquals(1, boardBoxes.size());
        Assertions.assertEquals(new BoundingBox(280, 390, 340, 450), boardBoxes.get(0));
    }

    @Test
    void testParseBoxesEmptyWhenNoTokens() {
        Sam31Client client = new Sam31Client(new ObjectMapper());
        String noTokens = "No objects found in this frame.";
        List<BoundingBox> boxes = client.parseBoxes(noTokens, false);
        Assertions.assertTrue(boxes.isEmpty());
    }
}
