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
        List<Map<String, Object>> messages = (List<Map<String, Object>>) staged.payload().get("messages");
        Assertions.assertEquals(2, messages.size());

        // Verify Meta Responses API payload format
        Assertions.assertNotNull(staged.responsesPayload());
        Assertions.assertEquals("meta-models/muse-spark-open", staged.responsesPayload().get("model"));
        Assertions.assertEquals(prompt, staged.responsesPayload().get("input"));
        Assertions.assertEquals("system", messages.get(0).get("role"));
        Assertions.assertEquals("user", messages.get(1).get("role"));

        // Multimodal user content: text, overview image with scale reference, and anomaly crop
        Object userContent = messages.get(1).get("content");
        Assertions.assertTrue(userContent instanceof List<?>);
        List<Map<String, Object>> contentParts = (List<Map<String, Object>>) userContent;
        Assertions.assertEquals(3, contentParts.size());
        Assertions.assertEquals("text", contentParts.get(0).get("type"));
        Assertions.assertEquals(prompt, contentParts.get(0).get("text"));
        Assertions.assertEquals("image_url", contentParts.get(1).get("type"));
        Assertions.assertEquals("image_url", contentParts.get(2).get("type"));

        // Verify tool calling and reasoning configuration
        Assertions.assertEquals(4096, staged.payload().get("max_tokens"));
        Assertions.assertEquals(0.8, staged.payload().get("temperature"));
        Assertions.assertEquals("high", staged.payload().get("reasoning_effort"));
        Assertions.assertTrue(staged.payload().containsKey("tools"));

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

        Assertions.assertEquals(basetenConfig.getModel(), response.model());
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

    @Test
    void testPrepareSamQueryDynamicPrompt() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        metaConfig.setApiKey("test_meta_api_key");
        metaConfig.setBaseUrl("https://api.meta.ai");

        MetaMuseService service = new MetaMuseService(null, metaConfig, null, null, null);

        // 1. Dynamic prompt from user (e.g. from Web App Text Input or Voice Transcript)
        String userPrompt = "Was ist das für eine Wespe am Flugloch?";
        com.beejepa.muse.dto.PrepareSamQueryRequest req = new com.beejepa.muse.dto.PrepareSamQueryRequest(
                "evt_test_123",
                userPrompt,
                Map.of("x", 0.35, "y", 0.40, "width", 0.15, "height", 0.15),
                "image"
        );

        var stagedSam = service.prepareSamQuery(req);

        Assertions.assertTrue(stagedSam.staged());
        Assertions.assertFalse(stagedSam.sent());
        Assertions.assertEquals("sam-3.1", stagedSam.model());
        Assertions.assertEquals("https://api.meta.ai/v1/responses", stagedSam.targetUrl());
        Assertions.assertEquals(userPrompt, stagedSam.prompt(), "Prompt must match the user's dynamic query exactly");
        Assertions.assertEquals("/api/v1/events/evt_test_123/media/peak_frame.jpg", stagedSam.peakFrameUrl());
        Assertions.assertNotNull(stagedSam.curlCommand());
        Assertions.assertTrue(stagedSam.curlCommand().contains("crop_region_xywh"));
        Assertions.assertTrue(stagedSam.curlCommand().contains("frame_idx"));
        Assertions.assertTrue(stagedSam.curlCommand().contains("baseline_bees"));
        Assertions.assertTrue(stagedSam.curlCommand().contains("vjepa_anomaly_target"));

        // Verify Kanal 1 (PCS) & Kanal 2 (PVS) in videoPayload
        var videoPayload = stagedSam.videoPayload();
        Assertions.assertNotNull(videoPayload);
        Assertions.assertEquals(Boolean.TRUE, videoPayload.get("multiplex_tracking"));
        Assertions.assertEquals(List.of(0, 0, 688, 688), videoPayload.get("crop_region_xywh"));
        @SuppressWarnings("unchecked")
        var prompts = (List<Map<String, Object>>) videoPayload.get("prompts");
        Assertions.assertEquals(2, prompts.size());
        Assertions.assertEquals("baseline_bees", prompts.get(0).get("prompt_id"));
        Assertions.assertEquals("honey bee", prompts.get(0).get("text"));
        Assertions.assertEquals("vjepa_anomaly_target", prompts.get(1).get("prompt_id"));
        Assertions.assertEquals("visual_geometry", prompts.get(1).get("mode"));
        Assertions.assertEquals(64, prompts.get(1).get("frame_idx"));

        // Verify Kanal 3 (Agentic SAM Tool) in videoPayload
        @SuppressWarnings("unchecked")
        var tools = (List<Map<String, Object>>) videoPayload.get("agentic_tools");
        Assertions.assertEquals(1, tools.size());
        @SuppressWarnings("unchecked")
        var fn = (Map<String, Object>) tools.get(0).get("function");
        Assertions.assertEquals("query_sam31_concept", fn.get("name"));

        // 2. Dynamic Audio Transcribe (Zero Credits Safe)
        var trans = service.transcribeVoice(new byte[]{1, 2, 3, 4});
        Assertions.assertEquals("SUCCESS", trans.get("status"));
        Assertions.assertEquals(0, ((Number) trans.get("creditsConsumed")).intValue());
        Assertions.assertNotNull(trans.get("transcript"));
    }

    @Test
    void testCleanMuseOutput() {
        String rawOutput = "<|start|>assistant to=commentary<|message|>Here is internal commentary.<|eom|>"
                + "Normal beekeeper report: Hive traffic is calm."
                + "<|start|>assistant to=thought<|message|>hidden thought<|eot|>";

        String cleaned = MetaMuseService.cleanMuseOutput(rawOutput);
        Assertions.assertTrue(cleaned.contains("Here is internal commentary."));
        Assertions.assertTrue(cleaned.contains("Normal beekeeper report: Hive traffic is calm."));
        Assertions.assertFalse(cleaned.contains("assistant to="));
        Assertions.assertFalse(cleaned.contains("hidden thought"));
    }

    @Test
    void testAnswerBeekeeperQuestionOfflineFallback() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setAllowInferenceCalls(false); // Locked offline mode

        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);

        // When offline, answerBeekeeperQuestion should immediately return fallback summary
        String response = service.answerBeekeeperQuestion("What happened at my hive today?", List.of());
        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.contains("Cached Demo Hive Summary"));
        Assertions.assertTrue(response.contains("Traffic Evolution"));
    }

    @Test
    void testExtractSpokenSummary() {
        String markdown = "### 🐝 Hive Summary\n"
                + "**Status:** High traffic detected at evt_rec2125_seq000_f0000 with box [235, 330, 395, 450].\n"
                + "Guard bees are active at the hive entrance slot.";

        String spoken = MetaMuseService.extractSpokenSummary(markdown);
        Assertions.assertNotNull(spoken);
        Assertions.assertFalse(spoken.contains("###"));
        Assertions.assertFalse(spoken.contains("**"));
        Assertions.assertFalse(spoken.contains("evt_rec2125_seq000_f0000"));
        Assertions.assertFalse(spoken.contains("[235, 330, 395, 450]"));
        Assertions.assertTrue(spoken.contains("Guard bees are active"));
    }
}
