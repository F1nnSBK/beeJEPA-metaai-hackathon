package com.beejepa.muse;

import com.beejepa.config.MetaMuseConfig;
import com.beejepa.event.EventService;
import com.beejepa.muse.dto.PrepareMuseQueryRequest;
import com.beejepa.muse.dto.StagedMuseRequest;
import com.beejepa.state.StateRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

class MetaMuseServiceTest {

    @Test
    void testPrepareMuseQueryStagedWithoutSending() {
        MetaMuseClient mockClient = Mockito.mock(MetaMuseClient.class);
        MetaMuseConfig config = new MetaMuseConfig();
        config.setBaseUrl("https://api.meta.ai");
        config.setApiKey("LLM_1653819056081840_ey3eWQNRqdn7H0Lg9FsAM9xVxs0");

        StateRepository mockRepo = Mockito.mock(StateRepository.class);
        EventService mockEventService = Mockito.mock(EventService.class);

        MetaMuseService service = new MetaMuseService(mockClient, config, mockRepo, mockEventService);

        Map<String, Object> eventData = Map.of(
                "eventId", "evt_seq015_f0720",
                "surpriseScoreL2", 4.82,
                "pixelDiffBaseline", 0.0142,
                "frameIndex", 720,
                "timestampSec", 24.0,
                "hotspot", Map.of("tubeletGridX", 2, "tubeletGridY", 3)
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
        Assertions.assertEquals("https://api.meta.ai/v1/responses", staged.targetUrl());
        Assertions.assertEquals("POST", staged.httpMethod());
        Assertions.assertEquals("muse-wonder-1.0", staged.model());

        // Verify Grounded Prompt contains event telemetry
        Assertions.assertTrue(staged.prompt().contains("evt_seq015_f0720"));
        Assertions.assertTrue(staged.prompt().contains("4.82 sigma"));
        Assertions.assertTrue(staged.prompt().contains("tubeletGridX=2"));
        Assertions.assertTrue(staged.prompt().contains("Did a collision happen here?"));

        // Verify payload format for Meta Model API
        Assertions.assertEquals("muse-wonder-1.0", staged.payload().get("model"));
        Assertions.assertEquals(staged.prompt(), staged.payload().get("input"));

        // Verify client was NOT called (zero outgoing requests)
        Mockito.verifyNoInteractions(mockClient);
    }
}
