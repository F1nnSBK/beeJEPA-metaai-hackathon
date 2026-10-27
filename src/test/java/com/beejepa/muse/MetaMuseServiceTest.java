package com.beejepa.muse;

import com.beejepa.config.BasetenConfig;
import com.beejepa.config.MetaMuseConfig;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import com.beejepa.muse.dto.PrepareMuseQueryRequest;
import com.beejepa.muse.dto.StagedMuseRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class MetaMuseServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void testPrepareMuseQueryStagedForBasetenMuseSparkOpen() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setApiKey("b10_test_dummy_key");
        basetenConfig.setBaseUrl("https://inference.baseten.co/v1");
        basetenConfig.setModel("meta-models/muse-spark-open");

        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);

        Map<String, Object> eventData = Map.of(
                "eventId", "evt_seq015_f0720",
                "surpriseScoreL2", 4.82,
                "pixelDiffBaseline", 0.0142,
                "frameIndex", 720,
                "timestampSec", 24.0,
                "hotspot", Map.of("tubeletGridX", 2, "tubeletGridY", 3, "boundingBoxPx", List.of(120, 180, 240, 310)),
                "sam", Map.of(
                        "model", "meta/sam-3.1",
                        "status", "SUCCESS",
                        "instanceCount", 2,
                        "segmentationMaskRle", "compact_rle_instance_0",
                        "segmentedObject", "Bee Collision Pair"
                )
        );

        PrepareMuseQueryRequest request = new PrepareMuseQueryRequest(
                "evt_seq015_f0720",
                "Did a collision happen here?",
                eventData,
                true
        );

        StagedMuseRequest staged = service.prepareMuseQuery(request);

        // Verification: MUST be staged and NOT sent
        Assertions.assertTrue(staged.staged());
        Assertions.assertFalse(staged.sent());
        Assertions.assertEquals(0L, staged.creditsConsumed());
        Assertions.assertEquals("https://inference.baseten.co/v1/chat/completions", staged.targetUrl());
        Assertions.assertEquals("POST", staged.httpMethod());
        Assertions.assertEquals("meta-models/muse-spark-open", staged.model());

        // Verify Grounded Prompt contains V-JEPA latents, SAM inputs, and SAM mask output
        String prompt = staged.prompt();
        Assertions.assertTrue(prompt.contains("evt_seq015_f0720"));
        Assertions.assertTrue(prompt.contains("4.82 sigma"));
        Assertions.assertTrue(prompt.contains("tubeletGridX=2"));
        Assertions.assertTrue(prompt.contains("Pipeline Stage 2: Inputs Routed to SAM 3.1"));
        Assertions.assertTrue(prompt.contains("Pipeline Stage 3: SAM 3.1 Visual Segmentation Mask"));
        Assertions.assertTrue(prompt.contains("Bee Collision Pair"));
        Assertions.assertTrue(prompt.contains("compact_rle_instance_0"));
        Assertions.assertTrue(prompt.contains("Did a collision happen here?"));

        // Verify OpenAI-compatible payload format for Baseten Model API
        Assertions.assertEquals("meta-models/muse-spark-open", staged.payload().get("model"));
        Assertions.assertTrue(staged.payload().containsKey("messages"));
        List<Map<String, String>> messages = (List<Map<String, String>>) staged.payload().get("messages");
        Assertions.assertEquals(2, messages.size());
        Assertions.assertEquals("system", messages.get(0).get("role"));
        Assertions.assertEquals("user", messages.get(1).get("role"));
        Assertions.assertEquals(prompt, messages.get(1).get("content"));

        // Verify masked authorization header
        String authHeader = staged.headers().get("Authorization");
        Assertions.assertNotNull(authHeader);
        Assertions.assertTrue(authHeader.contains("STAGED: NOT TRANSMITTED"));
        Assertions.assertTrue(authHeader.contains("b10_test_dum"));
    }

    @Test
    void testInferenceCallsHeldInStandby() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setApiKey("b10_test_key");
        basetenConfig.setAllowInferenceCalls(false); // Safety policy

        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);

        MuseInferenceResponse response = service.callInference(new MuseInferenceRequest("Diagnose collision"));

        Assertions.assertNotNull(response);
        Assertions.assertEquals("meta-models/muse-spark-open", response.model());
        Assertions.assertEquals("standby_zero_credits", response.metadata().get("status"));
        Assertions.assertEquals("baseten", response.metadata().get("provider"));
    }

    @Test
    void testMockHealthCheckWhenUnconfigured() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setApiKey("");

        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);
        var health = service.checkHealth();

        Assertions.assertFalse(health.configured());
        Assertions.assertFalse(health.liveConnection());
        Assertions.assertEquals("MOCK_OFFLINE", health.status());
    }

    @Test
    void testLiveBasetenHealthCheckIfKeyPresent() {
        String key = System.getProperty("BASETEN_API_KEY", System.getenv("BASETEN_API_KEY"));
        if (key == null || key.isBlank()) {
            try {
                java.nio.file.Path envFile = java.nio.file.Path.of(".env");
                if (java.nio.file.Files.exists(envFile)) {
                    for (String line : java.nio.file.Files.readAllLines(envFile)) {
                        if (line.startsWith("BASETEN_API_KEY=")) {
                            key = line.substring("BASETEN_API_KEY=".length()).trim();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (key != null && !key.isBlank() && !key.contains("your_baseten")) {
            MetaMuseConfig metaConfig = new MetaMuseConfig();
            BasetenConfig basetenConfig = new BasetenConfig();
            basetenConfig.setApiKey(key);
            basetenConfig.setBaseUrl("https://inference.baseten.co/v1");

            MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);
            var health = service.checkHealth();

            Assertions.assertTrue(health.configured());
            Assertions.assertTrue(health.liveConnection(), "Should connect to Baseten models endpoint: " + health.message());
            Assertions.assertEquals(200, health.statusCode());
            Assertions.assertTrue(health.availableModels().contains("meta-models/muse-spark-open") || health.availableModels().contains("meta-models/muse-spark-open-1026"));
        }
    }
}
