package com.beejepa.controller;

import com.beejepa.perception.ActivePerceptionService;
import com.beejepa.perception.OffHeapFrameRingBuffer;
import com.beejepa.perception.SaccadicTarget;
import com.beejepa.perception.SimdLatentEvaluator;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller exposing REST endpoints for the Active Perception System:
 * FFM Off-Heap Ring Buffer telemetry, saccadic fixation trajectories, and SIMD latent evaluations.
 */
@Controller("/api/v1/perception")
public class PerceptionController {

    private final ActivePerceptionService perceptionService;
    private final SimdLatentEvaluator simdEvaluator;

    public PerceptionController(
            ActivePerceptionService perceptionService,
            SimdLatentEvaluator simdEvaluator
    ) {
        this.perceptionService = perceptionService;
        this.simdEvaluator = simdEvaluator;
    }

    @Get("/ring-buffer")
    public HttpResponse<OffHeapFrameRingBuffer.BufferTelemetry> getRingBufferTelemetry() {
        return HttpResponse.ok(perceptionService.getRingBufferTelemetry());
    }

    @Get("/saccades")
    public HttpResponse<List<SaccadicTarget>> getSaccades() {
        return HttpResponse.ok(perceptionService.getSaccadicTrajectory());
    }

    @Get("/saccades/{eventId}")
    public HttpResponse<?> getSaccadeByEventId(@PathVariable String eventId) {
        Optional<SaccadicTarget> target = perceptionService.getTarget(eventId);
        return target.map(HttpResponse::ok)
                     .orElseGet(HttpResponse::notFound);
    }

    @Post(value = "/simd-eval", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public HttpResponse<Map<String, Object>> evaluateLatents(@Body Map<String, List<Number>> payload) {
        if (payload == null || !payload.containsKey("predicted") || !payload.containsKey("target")) {
            return HttpResponse.badRequest();
        }

        List<Number> predList = payload.get("predicted");
        List<Number> targetList = payload.get("target");

        if (predList == null || targetList == null || predList.size() != targetList.size() || predList.isEmpty()) {
            return HttpResponse.badRequest();
        }

        int len = predList.size();
        float[] pred = new float[len];
        float[] target = new float[len];
        for (int i = 0; i < len; i++) {
            pred[i] = predList.get(i).floatValue();
            target[i] = targetList.get(i).floatValue();
        }

        float dist = simdEvaluator.euclideanDistance(pred, target);
        float cos = simdEvaluator.cosineSimilarity(pred, target);

        return HttpResponse.ok(Map.of(
                "euclideanDistance", dist,
                "cosineSimilarity", cos,
                "vectorBitWidth", simdEvaluator.vectorBitWidth(),
                "vectorLaneCount", simdEvaluator.vectorLaneCount()
        ));
    }
}

