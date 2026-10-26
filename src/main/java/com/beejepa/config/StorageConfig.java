package com.beejepa.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

import java.nio.file.Path;

@Introspected
@ConfigurationProperties("beejepa")
public class StorageConfig {

    private Data data = new Data();
    private Sqlite sqlite = new Sqlite();

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public Sqlite getSqlite() {
        return sqlite;
    }

    public void setSqlite(Sqlite sqlite) {
        this.sqlite = sqlite;
    }

    @Introspected
    public static class Data {
        private String path = "./data";
        private boolean requireSymlink = false;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isRequireSymlink() {
            return requireSymlink;
        }

        public void setRequireSymlink(boolean requireSymlink) {
            this.requireSymlink = requireSymlink;
        }

        public Path getResolvedPath() {
            return Path.of(path).toAbsolutePath().normalize();
        }
    }

    @Introspected
    public static class Sqlite {
        private String filename = "state.db";
        private int busyTimeoutMs = 5000;

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public int getBusyTimeoutMs() {
            return busyTimeoutMs;
        }

        public void setBusyTimeoutMs(int busyTimeoutMs) {
            this.busyTimeoutMs = busyTimeoutMs;
        }
    }
}
