package com.beejepa.event;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Extracts single video frames at a target timestamp via ffmpeg.
 */
public final class VideoFrameExtractor {

    private static final int TIMEOUT_SECONDS = 15;
    private static final int MIN_JPEG_BYTES = 512;

    private VideoFrameExtractor() {}

    public static Optional<byte[]> extractJpegBytes(Path video, double offsetSeconds, String ffmpegBinary) {
        byte[] bytes = runFfmpegPipe(video, offsetSeconds, ffmpegBinary, "mjpeg", new String[] {"-q:v", "2"});
        return bytes.length > MIN_JPEG_BYTES ? Optional.of(bytes) : Optional.empty();
    }

    public static Optional<BufferedImage> extractImage(Path video, double offsetSeconds, String ffmpegBinary) {
        byte[] png = runFfmpegPipe(video, offsetSeconds, ffmpegBinary, "png", new String[0]);
        if (png.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(png)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String resolveFfmpegBinary() {
        String envBin = System.getenv("FFMPEG_BIN");
        if (envBin != null && !envBin.isBlank()) {
            return envBin.trim();
        }
        for (String candidate : List.of("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "ffmpeg";
    }

    private static byte[] runFfmpegPipe(
            Path video,
            double offsetSeconds,
            String ffmpegBinary,
            String codec,
            String[] extraArgs
    ) {
        if (video == null || !Files.isRegularFile(video)) {
            return new byte[0];
        }
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(List.of(
                    ffmpegBinary, "-nostdin", "-loglevel", "error",
                    "-ss", String.format(Locale.US, "%.3f", Math.max(0.0, offsetSeconds)),
                    "-i", video.toString(),
                    "-vframes", "1", "-f", "image2pipe", "-vcodec", codec
            ));
            cmd.addAll(List.of(extraArgs));
            cmd.add("-");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new byte[0];
            }
            return process.exitValue() == 0 ? output : new byte[0];
        } catch (Exception e) {
            return new byte[0];
        }
    }
}

