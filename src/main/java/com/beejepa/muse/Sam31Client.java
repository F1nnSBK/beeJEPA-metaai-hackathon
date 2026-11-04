package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.beejepa.muse.SamGeometry.BoundingBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Singleton
public class Sam31Client {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ResilientApiExecutor resilientExecutor;
    private final String apiKey;
    private final String fallbackApiKey;
    private final String endpointUrl;

    @jakarta.inject.Inject
    public Sam31Client(ObjectMapper objectMapper, @io.micronaut.core.annotation.Nullable ResilientApiExecutor resilientExecutor) {
        this.objectMapper = objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper();
        this.resilientExecutor = resilientExecutor != null ? resilientExecutor : new ResilientApiExecutor();

        String primary = getEnvOrDotEnv("META_MUSE_API_KEY");
        if (primary.isBlank()) {
            primary = getEnvOrDotEnv("MODEL_API_KEY");
        }
        if (primary.isBlank()) {
            primary = getEnvOrDotEnv("BASETEN_API_KEY1");
        }
        this.apiKey = primary;

        String secondary = getEnvOrDotEnv("MODEL_API_KEY");
        if (secondary.isBlank() || secondary.equals(primary)) {
            secondary = getEnvOrDotEnv("BASETEN_API_KEY2");
        }
        this.fallbackApiKey = secondary;

        String baseUrl = getEnvOrDotEnv("META_MUSE_BASE_URL");
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.meta.ai";
        }
        String trimmed = baseUrl.replaceAll("/+$", "");
        this.endpointUrl = trimmed.endsWith("/v1") ? trimmed + "/responses" : trimmed + "/v1/responses";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public Sam31Client(ObjectMapper objectMapper) {
        this(objectMapper, new ResilientApiExecutor());
    }

    public Sam31Client() {
        this(JacksonFactory.createTolerantMapper(), new ResilientApiExecutor());
    }

    public static String getEnvOrDotEnv(String key) {
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        java.nio.file.Path dotEnv = java.nio.file.Path.of(".env");
        if (java.nio.file.Files.exists(dotEnv)) {
            try {
                for (String raw : java.nio.file.Files.readAllLines(dotEnv)) {
                    String line = raw.trim();
                    if (!line.startsWith("#") && line.contains("=")) {
                        int eq = line.indexOf('=');
                        String k = line.substring(0, eq).trim();
                        if (k.equals(key)) {
                            String v = line.substring(eq + 1).trim();
                            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                                v = v.substring(1, v.length() - 1);
                            }
                            return v.trim();
                        }
                    }
                }
            } catch (java.io.IOException ignored) {}
        }
        return "";
    }

    public List<BoundingBox> queryConcept(String conceptPrompt, String imageBase64, boolean filterLandingBoard) {
        return queryConcept(conceptPrompt, imageBase64, SamGeometry.FRAME_SIZE, SamGeometry.FRAME_SIZE, filterLandingBoard);
    }

    public List<BoundingBox> queryConcept(
            String conceptPrompt,
            String imageBase64,
            int imageWidth,
            int imageHeight,
            boolean filterLandingBoard
    ) {
        if (apiKey.isBlank() && fallbackApiKey.isBlank()) {
            throw new IllegalStateException("SAM 3.1 unavailable: no META_MUSE_API_KEY configured");
        }

        String safePrompt = (conceptPrompt != null && !conceptPrompt.isBlank()) ? conceptPrompt.trim() : "bee";
        Map<String, Object> payload = Map.of(
                "model", "sam-3.1",
                "input", List.of(Map.of(
                        "type", "message",
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", safePrompt),
                                Map.of("type", "input_image", "image_url", "data:image/jpeg;base64," + imageBase64)
                        )
                )),
                "metadata", Map.of("mask_encoding", "one_bit")
        );

        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SAM 3.1 payload", e);
        }

        String responseBody = resilientExecutor.executeWithRetryAndFailover(
                "SAM-3.1[" + safePrompt + "]",
                apiKey,
                fallbackApiKey,
                (activeKey, attempt) -> {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpointUrl))
                            .header("Authorization", "Bearer " + activeKey)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(40))
                            .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();
                    if (status >= 400) {
                        throw new ResilientApiExecutor.HttpStatusException(
                                status,
                                "SAM 3.1 HTTP " + status + ": " + response.body(),
                                response.body()
                        );
                    }
                    return response.body();
                }
        );

        return parseBoxesScaled(responseBody, imageWidth, imageHeight, filterLandingBoard);
    }

    /**
     * Parses raw SAM 3.1 response tokens 1:1 without coordinate scaling (used by unit tests).
     */
    public List<BoundingBox> parseBoxes(String rawJsonOrText, boolean filterLandingBoard) {
        return TolerantPayloadParser.parseSamBoxes(
                rawJsonOrText,
                SamGeometry.FRAME_SIZE,
                SamGeometry.FRAME_SIZE,
                false,
                filterLandingBoard
        );
    }

    /**
     * Parses SAM 3.1 response tokens and scales [0..1000] normalized coordinates into the target image dimensions.
     */
    public List<BoundingBox> parseBoxesScaled(
            String rawJsonOrText,
            int imageWidth,
            int imageHeight,
            boolean filterLandingBoard
    ) {
        return TolerantPayloadParser.parseSamBoxes(
                rawJsonOrText,
                imageWidth,
                imageHeight,
                true,
                filterLandingBoard
        );
    }
}
