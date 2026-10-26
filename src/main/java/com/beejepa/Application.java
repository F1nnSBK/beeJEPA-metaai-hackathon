package com.beejepa;

import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        loadDotEnvIfPresent();
        LOG.info("Starting beeJEPA Server [JVM: {}, Virtual Threads supported: {}]",
                Runtime.version(),
                isVirtualThreadsSupported());
        Micronaut.run(Application.class, args);
    }

    private static void loadDotEnvIfPresent() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            LOG.info("No .env file found at {}, relying on system environment variables.", envPath.toAbsolutePath());
            return;
        }

        LOG.info("Loading environment variables from: {}", envPath.toAbsolutePath());
        try {
            List<String> lines = Files.readAllLines(envPath);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    if (System.getProperty(key) == null && System.getenv(key) == null) {
                        System.setProperty(key, val);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read .env file: {}", e.getMessage());
        }
    }

    public static boolean isVirtualThreadsSupported() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
