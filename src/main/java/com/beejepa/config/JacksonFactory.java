package com.beejepa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * Factory providing Jackson ObjectMapper bean for Micronaut dependency injection.
 */
@Factory
public class JacksonFactory {

    @Singleton
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
