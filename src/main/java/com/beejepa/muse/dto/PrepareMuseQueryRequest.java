package com.beejepa.muse.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Introspected
@Serdeable
public record PrepareMuseQueryRequest(
        String eventId,
        String question,
        Map<String, Object> eventData,
        Boolean dryRun
) {}
