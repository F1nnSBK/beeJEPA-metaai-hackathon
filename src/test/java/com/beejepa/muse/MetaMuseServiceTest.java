package com.beejepa.muse;

import com.beejepa.config.BasetenConfig;
import com.beejepa.config.JacksonFactory;
import com.beejepa.config.MetaMuseConfig;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import com.beejepa.muse.dto.PrepareMuseQueryRequest;
import com.beejepa.muse.dto.StagedMuseRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

        PrepareMuseQueryRequest request = new PrepareMuseQueryRequest(
                "evt_seq015_f0720",
                "Did a collision happen here?",
                Map.of(),
                true
        );

        StagedMuseRequest staged = service.prepareMuseQuery(request);

        Assertions.assertTrue(staged.staged());
        Assertions.assertFalse(staged.sent());
        Assertions.assertEquals(0L, staged.creditsConsumed());
        Assertions.assertEquals("https://inference.baseten.co/v1/chat/completions", staged.targetUrl());
        Assertions.assertEquals("POST", staged.httpMethod());
        Assertions.assertTrue(staged.model().startsWith("meta-models/muse-spark-open"));

        String prompt = staged.prompt();
        Assertions.assertTrue(prompt.contains("Did a collision happen here?"));
        Assertions.assertTrue(prompt.contains("Event digest"));

        Assertions.assertEquals(staged.model(), staged.payload().get("model"));
        Assertions.assertTrue(staged.payload().containsKey("messages"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) staged.payload().get("messages");
        Assertions.assertEquals(2, messages.size());
        Assertions.assertNull(staged.responsesPayload());
        Assertions.assertEquals("system", messages.get(0).get("role"));
        Assertions.assertEquals("user", messages.get(1).get("role"));

        Object userContent = messages.get(1).get("content");
        Assertions.assertTrue(userContent instanceof List<?>);
        List<Map<String, Object>> contentParts = (List<Map<String, Object>>) userContent;
        Assertions.assertEquals(1, contentParts.size());
        Assertions.assertEquals("text", contentParts.get(0).get("type"));
        Assertions.assertEquals(prompt, contentParts.get(0).get("text"));

        Assertions.assertEquals(4096, staged.payload().get("max_completion_tokens"));
        Assertions.assertTrue(staged.payload().containsKey("tools"));

        String authHeader = staged.headers().get("Authorization");
        Assertions.assertNotNull(authHeader);
        Assertions.assertTrue(authHeader.contains("STAGED: NOT TRANSMITTED"));
    }

    @Test
    void testCallInferenceUsesCacheAndRejectsUnconfiguredWithoutMock(@TempDir Path tempDir) {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setApiKey("");

        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);
        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null, null, JacksonFactory.createTolerantMapper(), cache);

        // Unconfigured service without cache entry must throw IllegalStateException (no mock responses)
        Assertions.assertThrows(IllegalStateException.class, () ->
                service.callInference(new MuseInferenceRequest("Uncached prompt"))
        );

        // When cached in SQLite/Redis, callInference returns the cached response
        MuseInferenceRequest cachedReq = new MuseInferenceRequest("Diagnose collision");
        String promptKey = "infer:" + cachedReq.model() + ":" + cachedReq.prompt();
        cache.saveMuseResponse(promptKey, "Live cached diagnosis");
        MuseInferenceResponse response = service.callInference(cachedReq);
        Assertions.assertEquals("Live cached diagnosis", response.outputText());
        Assertions.assertEquals("cache_hit", response.metadata().get("status"));
    }

    @Test
    void testUnconfiguredHealthCheckReturnsUnconfiguredNotMock() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setApiKey("");

        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);
        var health = service.checkHealth();

        Assertions.assertFalse(health.configured());
        Assertions.assertFalse(health.liveConnection());
        Assertions.assertEquals("UNCONFIGURED", health.status());
        Assertions.assertTrue(health.availableModels().isEmpty());
    }

    @Test
    void testLiveBasetenHealthCheckIfKeyPresent() {
        String key = Sam31Client.getEnvOrDotEnv("BASETEN_API_KEY");
        if (key != null && !key.isBlank() && !key.contains("your_baseten") && !"offline".equalsIgnoreCase(key)) {
            MetaMuseConfig metaConfig = new MetaMuseConfig();
            BasetenConfig basetenConfig = new BasetenConfig();
            basetenConfig.setApiKey(key);
            basetenConfig.setBaseUrl("https://inference.baseten.co/v1");

            MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null);
            var health = service.checkHealth();

            Assertions.assertTrue(health.configured());
            Assertions.assertNotNull(health.status());
            if (health.liveConnection()) {
                Assertions.assertEquals(200, health.statusCode());
            }
        }
    }

    @Test
    void testPrepareSamQueryDynamicPrompt() {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        metaConfig.setApiKey("test_meta_api_key");
        metaConfig.setBaseUrl("https://api.meta.ai");

        MetaMuseService service = new MetaMuseService(null, metaConfig, null, null, null);

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
        Assertions.assertTrue(stagedSam.curlCommand().contains("sam-3.1"));
        Assertions.assertTrue(stagedSam.curlCommand().contains(userPrompt));
        Assertions.assertNull(stagedSam.videoPayload());

        @SuppressWarnings("unchecked")
        var inputList = (List<Map<String, Object>>) stagedSam.imagePayload().get("input");
        Assertions.assertEquals(1, inputList.size());
        @SuppressWarnings("unchecked")
        var contentList = (List<Map<String, Object>>) inputList.getFirst().get("content");
        Assertions.assertEquals(userPrompt, contentList.getFirst().get("text"));
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
    void testAnswerBeekeeperQuestionReturnsUnavailableWhenInferenceDisabledAndUncached(@TempDir Path tempDir) {
        MetaMuseConfig metaConfig = new MetaMuseConfig();
        BasetenConfig basetenConfig = new BasetenConfig();
        basetenConfig.setAllowInferenceCalls(false);

        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);
        MetaMuseService service = new MetaMuseService(null, metaConfig, basetenConfig, null, null, null, JacksonFactory.createTolerantMapper(), cache);

        MetaMuseService.MuseAnswerResult result = service.answerBeekeeperQuestionWithMeta("What happened at my hive today?", List.of(), 3, false);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("UNAVAILABLE", result.sourceMode());
        Assertions.assertTrue(result.degradedFallback());
        Assertions.assertEquals("", result.reportMarkdown());
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
