package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Introspected
@Serdeable
public record PrepareSamQueryRequest(
        String eventId,
        String prompt,
        Map<String, Object> customBox,
        String mediaType // "image" or "video"
) {
}
