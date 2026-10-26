package com.beejepa.state.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Introspected
@Serdeable
public record ProcessingRun(
        String id,
        String taskType,
        String status,
        String details,
        Instant createdAt,
        Instant updatedAt
) {
}
