package com.beejepa.auth;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Introspected
@Serdeable
public record BeekeeperUser(
        String id,
        String username,
        String passwordHash,
        String salt,
        String role,
        Instant createdAt,
        Instant lastLoginAt
) {
}
