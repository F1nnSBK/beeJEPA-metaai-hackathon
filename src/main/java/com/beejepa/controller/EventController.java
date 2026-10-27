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

    @Get(value = "/events/{eventId}/media/{filename}")
    public HttpResponse<?> getMedia(@PathVariable String eventId, @PathVariable String filename) {
        Optional<File> file = eventService.getMediaFile(eventId, filename);
        if (file.isEmpty()) {
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
