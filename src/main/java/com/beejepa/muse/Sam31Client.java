package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.BoundingBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class Sam31Client {

    private static final Pattern BOX_TOKEN_REGEX =
            Pattern.compile("<\\|box;x1=(\\d+);y1=(\\d+);x2=(\\d+);y2=(\\d+)[^|]*\\|>");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String endpointUrl;

    @jakarta.inject.Inject
    public Sam31Client(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        String key = getEnvOrDotEnv("META_MUSE_API_KEY");
        if (key.isBlank()) {
            key = getEnvOrDotEnv("MODEL_API_KEY");
        }
        this.apiKey = key;
        String baseUrl = getEnvOrDotEnv("META_MUSE_BASE_URL");
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.meta.ai";
        }
        this.endpointUrl = baseUrl.replaceAll("/+$", "") + "/v1/responses";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
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

    public Sam31Client() {
        this(new ObjectMapper());
    }

    public List<BoundingBox> queryConcept(String conceptPrompt, String imageBase64, boolean filterLandingBoard) {
        if (apiKey.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> payload = Map.of(
                    "model", "sam-3.1",
                    "input", List.of(Map.of(
                            "type", "message",
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "input_text", "text", conceptPrompt),
                                    Map.of("type", "input_image", "image_url", "data:image/jpeg;base64," + imageBase64)
                            )
                    )),
                    "metadata", Map.of("mask_encoding", "one_bit")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("SAM 3.1 HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseBoxes(response.body(), filterLandingBoard);
        } catch (Exception e) {
            throw new RuntimeException("SAM 3.1 call failed: " + e.getMessage(), e);
        }
    }

    public List<BoundingBox> parseBoxes(String rawJsonOrText, boolean filterLandingBoard) {
        List<BoundingBox> boxes = new ArrayList<>();
        Matcher matcher = BOX_TOKEN_REGEX.matcher(rawJsonOrText);
        while (matcher.find()) {
            BoundingBox box = new BoundingBox(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4))
            );
            if (!filterLandingBoard || box.isOnLandingBoard()) {
                boxes.add(box);
            }
        }
        return boxes;
    }
}
