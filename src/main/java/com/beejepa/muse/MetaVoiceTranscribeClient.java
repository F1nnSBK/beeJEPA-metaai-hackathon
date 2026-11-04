package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * HTTP client for speech-to-text transcription via Meta
 * {@code muse-voice-transcribe-1.0}.
 */
@Singleton
public class MetaVoiceTranscribeClient {

    public static final String MODEL = "muse-voice-transcribe-1.0";

    public record Transcription(String transcript, long audioDurationMs, String sessionId) {
    }

    private final ObjectMapper objectMapper = JacksonFactory.createTolerantMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String endpointUrl;

    public MetaVoiceTranscribeClient() {
        this.apiKey = Sam31Client.getEnvOrDotEnv("META_MUSE_API_KEY");
        String baseUrl = Sam31Client.getEnvOrDotEnv("META_MUSE_BASE_URL");
        if (baseUrl.isBlank()) {
            baseUrl = "https://api.meta.ai";
        }
        String trimmed = baseUrl.replaceAll("/+$", "");
        this.endpointUrl = (trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1") + "/asr/transcribe";
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public Transcription transcribe(byte[] wavBytes) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("META_MUSE_API_KEY is not configured");
        }
        validateWav(wavBytes);

        String boundary = "beejepa-" + UUID.randomUUID();
        String requestJson = "{\"model\":\"" + MODEL + "\",\"mode\":\"PUSH_TO_TALK\",\"audioEncoding\":\"WAV\"}";
        ByteArrayOutputStream body = new ByteArrayOutputStream(wavBytes.length + 512);
        writePart(body, boundary, "request", null, "application/json", requestJson.getBytes(StandardCharsets.UTF_8));
        writePart(body, boundary, "audio", "speech.wav", "audio/wav", wavBytes);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = response.body() != null && !response.body().isBlank() ? objectMapper.readTree(response.body())
                : null;
        if (response.statusCode() >= 400 || root == null || root.has("error")) {
            String msg = root != null && root.path("error").has("message") ? root.path("error").path("message").asText()
                    : response.body();
            throw new IllegalStateException("Meta transcription HTTP " + response.statusCode() + ": " + msg);
        }
        return new Transcription(
                root.path("transcript").asText(""),
                root.path("audioDurationMs").asLong(0),
                root.path("sessionId").asText(""));
    }

    /**
     * Checks RIFF/WAVE header, PCM 16-bit mono and 16/24 kHz as required by the
     * API.
     */
    static void validateWav(byte[] wav) {
        if (wav == null || wav.length < 44) {
            throw new IllegalArgumentException("Audio is not a WAV file");
        }
        String riff = new String(wav, 0, 4, StandardCharsets.US_ASCII);
        String wave = new String(wav, 8, 4, StandardCharsets.US_ASCII);
        if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
            throw new IllegalArgumentException("Audio is not a RIFF/WAVE file");
        }
        int channels = (wav[22] & 0xFF) | ((wav[23] & 0xFF) << 8);
        int sampleRate = (wav[24] & 0xFF) | ((wav[25] & 0xFF) << 8) | ((wav[26] & 0xFF) << 16)
                | ((wav[27] & 0xFF) << 24);
        int bits = (wav[34] & 0xFF) | ((wav[35] & 0xFF) << 8);
        if (channels != 1 || bits != 16 || (sampleRate != 16000 && sampleRate != 24000)) {
            throw new IllegalArgumentException("WAV must be mono 16-bit at 16 or 24 kHz (got "
                    + channels + " ch, " + bits + " bit, " + sampleRate + " Hz)");
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String filename,
            String contentType, byte[] data) throws java.io.IOException {
        StringBuilder head = new StringBuilder();
        head.append("--").append(boundary).append("\r\n");
        head.append("Content-Disposition: form-data; name=\"").append(name).append('"');
        if (filename != null) {
            head.append("; filename=\"").append(filename).append('"');
        }
        head.append("\r\n").append("Content-Type: ").append(contentType).append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
