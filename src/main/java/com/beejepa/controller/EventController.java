package com.beejepa.controller;

import com.beejepa.event.EventService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.server.types.files.SystemFile;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller("/api/v1")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @Get("/timeline")
    public HttpResponse<Map<String, Object>> getTimeline() {
        return HttpResponse.ok(eventService.getTimeline());
    }

    @Get("/events")
    public HttpResponse<List<Map<String, Object>>> listEvents() {
        return HttpResponse.ok(eventService.listEvents());
    }

    @Get("/events/{eventId}")
    public HttpResponse<?> getEvent(@PathVariable String eventId) {
        Optional<Map<String, Object>> event = eventService.getEvent(eventId);
        return event.map(HttpResponse::ok)
                    .orElseGet(HttpResponse::notFound);
    }

    private static final String HF_DATASET_BASE =
            "https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies/resolve/main";
    private static final java.util.regex.Pattern EVENT_ID_PATTERN =
            java.util.regex.Pattern.compile("^evt_rec\\d+_seq\\d+_f\\d+$");
    private static final java.util.regex.Pattern SEQ_ID_PATTERN =
            java.util.regex.Pattern.compile("(rec\\d+_seq\\d+)");

    @Get(value = "/events/{eventId}/media/{filename}")
    public HttpResponse<?> getMedia(@PathVariable String eventId, @PathVariable String filename) {
        if ("peak_frame.jpg".equals(filename) || "vjepa_heatmap.jpg".equals(filename)) {
            Optional<byte[]> frameBytes = eventService.getOrExtractEventFrameBytes(eventId);
            if (frameBytes.isPresent()) {
                return HttpResponse.ok(frameBytes.get())
                        .contentType(MediaType.IMAGE_JPEG_TYPE)
                        .header("Cache-Control", "public, max-age=3600");
            }
        }

        Optional<File> file = eventService.getMediaFile(eventId, filename);
        if (file.isEmpty()) {
            if (filename != null && !filename.contains("..") && !filename.contains("/") && !filename.contains("\\")
                    && ("clip_raw.mp4".equals(filename) || filename.endsWith(".mp4"))
                    && eventId != null && EVENT_ID_PATTERN.matcher(eventId).matches()) {
                return HttpResponse.temporaryRedirect(
                        java.net.URI.create(HF_DATASET_BASE + "/events/peaks/" + eventId + ".mp4")
                );
            }
            return HttpResponse.notFound();
        }

        File target = file.get();
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        if (filename.endsWith(".mp4")) {
            mediaType = MediaType.of("video/mp4");
        } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG_TYPE;
        } else if (filename.endsWith(".png")) {
            mediaType = MediaType.IMAGE_PNG_TYPE;
        } else if (filename.endsWith(".json")) {
            mediaType = MediaType.APPLICATION_JSON_TYPE;
        }

        return HttpResponse.ok(new SystemFile(target, mediaType));
    }

    @Get(value = "/sequences/{filename}")
    public HttpResponse<?> getSequenceMedia(@PathVariable String filename) {
        Optional<File> file = eventService.getSequenceFile(filename);
        if (file.isEmpty()) {
            if (filename != null && !filename.contains("..") && !filename.contains("/") && !filename.contains("\\")) {
                java.util.regex.Matcher m = SEQ_ID_PATTERN.matcher(filename);
                if (m.find()) {
                    String seqId = m.group(1);
                    return HttpResponse.temporaryRedirect(
                            java.net.URI.create(HF_DATASET_BASE + "/stitched_sequences/full_" + seqId + "_9x9_stitched_30s.mp4")
                    );
                }
            }
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(new SystemFile(file.get(), MediaType.of("video/mp4")));
    }

    @Post("/events/{eventId}/analyze")
    public HttpResponse<Map<String, Object>> analyzeEvent(
        @PathVariable String eventId,
        @QueryValue(defaultValue = "false") boolean forceRefresh
    ) {
        return HttpResponse.ok(eventService.analyzeEvent(eventId, forceRefresh));
    }
}
