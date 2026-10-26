package com.beejepa.state;

import com.beejepa.config.StorageConfig;
import com.beejepa.state.model.MuseLogEntry;
import com.beejepa.state.model.ProcessingRun;
import com.beejepa.state.model.SystemState;
import com.beejepa.storage.StorageService;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Singleton
public class StateRepository {

    private static final Logger LOG = LoggerFactory.getLogger(StateRepository.class);

    private final StorageService storageService;
    private final StorageConfig storageConfig;
    private String jdbcUrl;

    public StateRepository(StorageService storageService, StorageConfig storageConfig) {
        this.storageService = storageService;
        this.storageConfig = storageConfig;
    }

    @PostConstruct
    public void init() {
        Path dbPath = storageService.getDatabasePath();
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        LOG.info("Initializing SQLite state database at: {}", dbPath.toAbsolutePath());

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Enable Write-Ahead Logging for high concurrency with Virtual Threads
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA busy_timeout=" + storageConfig.getSqlite().getBusyTimeoutMs() + ";");

            // System State table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS system_state (
                        state_key TEXT PRIMARY KEY,
                        state_value TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );
                    """);

            // Processing Runs table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS processing_runs (
                        id TEXT PRIMARY KEY,
                        task_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        details TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );
                    """);

            // Muse API Logs table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS muse_logs (
                        id TEXT PRIMARY KEY,
                        endpoint TEXT NOT NULL,
                        request_summary TEXT,
                        status_code INTEGER NOT NULL,
                        latency_ms INTEGER NOT NULL,
                        timestamp TEXT NOT NULL
                    );
                    """);

            LOG.info("SQLite database schema verified successfully on SSD.");
        } catch (SQLException e) {
            LOG.error("Failed to initialize SQLite database: {}", e.getMessage(), e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    // ---------------- System State (Key-Value) ----------------

    public void set(String key, String value) {
        String sql = """
                INSERT INTO system_state (state_key, state_value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(state_key) DO UPDATE SET
                    state_value = excluded.state_value,
                    updated_at = excluded.updated_at;
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Error setting state for key {}: {}", key, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public Optional<SystemState> get(String key) {
        String sql = "SELECT state_key, state_value, updated_at FROM system_state WHERE state_key = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new SystemState(
                            rs.getString("state_key"),
                            rs.getString("state_value"),
                            Instant.parse(rs.getString("updated_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("Error getting state for key {}: {}", key, e.getMessage());
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public List<SystemState> getAll() {
        List<SystemState> list = new ArrayList<>();
        String sql = "SELECT state_key, state_value, updated_at FROM system_state ORDER BY state_key ASC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new SystemState(
                        rs.getString("state_key"),
                        rs.getString("state_value"),
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            LOG.error("Error retrieving all state: {}", e.getMessage());
            throw new RuntimeException(e);
        }
        return list;
    }

    // ---------------- Processing Runs ----------------

    public void saveRun(ProcessingRun run) {
        String sql = """
                INSERT INTO processing_runs (id, task_type, status, details, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    status = excluded.status,
                    details = excluded.details,
                    updated_at = excluded.updated_at;
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, run.id());
            ps.setString(2, run.taskType());
            ps.setString(3, run.status());
            ps.setString(4, run.details());
            ps.setString(5, run.createdAt().toString());
            ps.setString(6, run.updatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Error saving run {}: {}", run.id(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public List<ProcessingRun> getRecentRuns(int limit) {
        List<ProcessingRun> runs = new ArrayList<>();
        String sql = "SELECT id, task_type, status, details, created_at, updated_at FROM processing_runs ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    runs.add(new ProcessingRun(
                            rs.getString("id"),
                            rs.getString("task_type"),
                            rs.getString("status"),
                            rs.getString("details"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("Error fetching runs: {}", e.getMessage());
            throw new RuntimeException(e);
        }
        return runs;
    }

    // ---------------- Muse Logs ----------------

    public void logMuseCall(MuseLogEntry entry) {
        String sql = """
                INSERT INTO muse_logs (id, endpoint, request_summary, status_code, latency_ms, timestamp)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.id());
            ps.setString(2, entry.endpoint());
            ps.setString(3, entry.requestSummary());
            ps.setInt(4, entry.statusCode());
            ps.setLong(5, entry.latencyMs());
            ps.setString(6, entry.timestamp().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to log Muse API call {}: {}", entry.id(), e.getMessage());
        }
    }

    public List<MuseLogEntry> getRecentMuseLogs(int limit) {
        List<MuseLogEntry> logs = new ArrayList<>();
        String sql = "SELECT id, endpoint, request_summary, status_code, latency_ms, timestamp FROM muse_logs ORDER BY timestamp DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logs.add(new MuseLogEntry(
                            rs.getString("id"),
                            rs.getString("endpoint"),
                            rs.getString("request_summary"),
                            rs.getInt("status_code"),
                            rs.getLong("latency_ms"),
                            Instant.parse(rs.getString("timestamp"))
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("Error fetching muse logs: {}", e.getMessage());
            throw new RuntimeException(e);
        }
        return logs;
    }
}
