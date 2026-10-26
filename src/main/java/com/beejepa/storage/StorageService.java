package com.beejepa.storage;

import com.beejepa.config.StorageConfig;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class StorageService {

    private static final Logger LOG = LoggerFactory.getLogger(StorageService.class);

    private final StorageConfig storageConfig;
    private Path dataDirectory;
    private boolean isSymlink;
    private Path realTargetPath;

    public StorageService(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    @PostConstruct
    public void init() {
        this.dataDirectory = storageConfig.getData().getResolvedPath();
        this.isSymlink = Files.isSymbolicLink(dataDirectory);

        try {
            if (Files.exists(dataDirectory)) {
                this.realTargetPath = dataDirectory.toRealPath();
            } else {
                LOG.info("Data directory {} does not exist yet. Creating directory...", dataDirectory);
                Files.createDirectories(dataDirectory);
                this.realTargetPath = dataDirectory.toRealPath();
            }
        } catch (IOException e) {
            LOG.error("Failed to resolve or create data directory at {}: {}", dataDirectory, e.getMessage());
            this.realTargetPath = dataDirectory;
        }

        if (isSymlink) {
            LOG.info("Data repository is SYMLINKED to SSD: {} -> {}", dataDirectory, realTargetPath);
        } else {
            if (storageConfig.getData().isRequireSymlink()) {
                LOG.warn("Data repository {} is NOT a symlink! SSD symlink is expected.", dataDirectory);
            } else {
                LOG.info("Data repository is a local directory at {} (not symlinked yet).", realTargetPath);
            }
        }

        // Initialize standard subdirectories on the SSD
        List<String> subdirs = List.of("models", "embeddings", "captures", "runs");
        for (String subdir : subdirs) {
            Path subPath = dataDirectory.resolve(subdir);
            try {
                if (!Files.exists(subPath)) {
                    Files.createDirectories(subPath);
                    LOG.debug("Created storage subdirectory: {}", subPath);
                }
            } catch (IOException e) {
                LOG.error("Failed to create subdirectory {}: {}", subPath, e.getMessage());
            }
        }
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Path getDatabasePath() {
        return dataDirectory.resolve(storageConfig.getSqlite().getFilename());
    }

    public boolean isSymlink() {
        return isSymlink;
    }

    public Path getRealTargetPath() {
        return realTargetPath != null ? realTargetPath : dataDirectory;
    }

    public Map<String, Object> getStorageStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("configuredPath", storageConfig.getData().getPath());
        status.put("absolutePath", dataDirectory != null ? dataDirectory.toString() : null);
        status.put("isSymlink", isSymlink);
        status.put("targetRealPath", realTargetPath != null ? realTargetPath.toString() : null);

        File file = realTargetPath != null ? realTargetPath.toFile() : (dataDirectory != null ? dataDirectory.toFile() : null);
        if (file != null && file.exists()) {
            long totalSpace = file.getTotalSpace();
            long freeSpace = file.getFreeSpace();
            long usableSpace = file.getUsableSpace();

            status.put("totalSpaceBytes", totalSpace);
            status.put("freeSpaceBytes", freeSpace);
            status.put("usableSpaceBytes", usableSpace);
            status.put("freeSpaceFormatted", formatBytes(usableSpace));
            status.put("totalSpaceFormatted", formatBytes(totalSpace));
            status.put("isWritable", file.canWrite());
        }

        Path dbPath = getDatabasePath();
        status.put("sqliteDatabasePath", dbPath.toString());
        status.put("sqliteExists", Files.exists(dbPath));

        return status;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %siB", bytes / Math.pow(1024, exp), pre);
    }
}
