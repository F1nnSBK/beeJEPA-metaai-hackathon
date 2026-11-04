package com.beejepa.state;

import com.beejepa.auth.BeekeeperUser;
import com.beejepa.config.StorageConfig;
import com.beejepa.state.model.MuseLogEntry;
import com.beejepa.state.model.ProcessingRun;
import com.beejepa.state.model.SystemState;
import com.beejepa.storage.StorageService;
import com.beejepa.telemetry.TelemetryEntry;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
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
    private final Path explicitDbPath;
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock(true);
    private String jdbcUrl;

    @Inject
    public StateRepository(StorageService storageService, StorageConfig storageConfig) {
        this.storageService = storageService;
        this.storageConfig = storageConfig;
        this.explicitDbPath = null;
    }

    public StateRepository(Path explicitDbPath) {
        this.storageService = null;
        this.storageConfig = new StorageConfig();
        this.explicitDbPath = explicitDbPath;
        init();
    }

    @PostConstruct
    public void init() {
        Path dbPath = explicitDbPath != null ? explicitDbPath : storageService.getDatabasePath();
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
        } catch (Exception ignored) {}
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        LOG.info("Initializing SQLite state & cache database at: {}", dbPath.toAbsolutePath());

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("PRAGMA journal_mode=WAL;");
            } catch (SQLException walEx) {
                LOG.warn("WAL mode not supported on filesystem for {}, falling back to DELETE journal: {}", dbPath, walEx.getMessage());
                stmt.execute("PRAGMA journal_mode=DELETE;");
            }
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

            // Telemetry Logs table for all platform & HTTP requests
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS telemetry_logs (
                        id TEXT PRIMARY KEY,
                        platform TEXT NOT NULL,
                        method TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        status_code INTEGER NOT NULL,
                        duration_ms REAL NOT NULL,
                        client_ip TEXT,
                        timestamp TEXT NOT NULL
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_telemetry_uri ON telemetry_logs(uri);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_telemetry_timestamp ON telemetry_logs(timestamp);");

            // Beekeeper User Management table
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS beekeepers (
                        id TEXT PRIMARY KEY,
                        username TEXT UNIQUE NOT NULL,
                        password_hash TEXT NOT NULL,
                        salt TEXT NOT NULL,
                        role TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        last_login_at TEXT
                    );
                    """);

            // Persistent Beekeeper Sessions table (survives JVM restart)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS beekeeper_sessions (
                        token TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        username TEXT NOT NULL,
                        role TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    );
                    """);

            // Live API Cache Table 1: Muse Spark Open Responses (with TTL)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS muse_response_cache (
                        cache_key TEXT PRIMARY KEY,
                        response_markdown TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_muse_cache_expires ON muse_response_cache(expires_at);");

            // Live API Cache Table 2: SAM 3.1 Spatial Summaries (with TTL)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS sam_spatial_cache (
                        event_id TEXT PRIMARY KEY,
                        summary_json TEXT NOT NULL,
                        board_bees INTEGER NOT NULL,
                        hotspot_bees INTEGER NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sam_cache_expires ON sam_spatial_cache(expires_at);");

            // Live API Cache Table 3: Neural TTS Audio Stream (with TTL)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS tts_audio_cache (
                        audio_hash TEXT PRIMARY KEY,
                        spoken_text TEXT,
                        audio_bytes BLOB NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tts_cache_expires ON tts_audio_cache(expires_at);");

            // Live Media Cache Table 4: Extracted Video Keyframes (with TTL)
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS frame_cache (
                        event_id TEXT PRIMARY KEY,
                        frame_bytes BLOB NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_frame_cache_expires ON frame_cache(expires_at);");

            LOG.info("SQLite database schema (including live API cache tables) verified successfully.");
        } catch (SQLException e) {
            LOG.error("Failed to initialize SQLite database: {}", e.getMessage(), e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        int busyTimeout = (storageConfig != null && storageConfig.getSqlite() != null)
                ? storageConfig.getSqlite().getBusyTimeoutMs()
                : 5000;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=" + busyTimeout + ";");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA temp_store=MEMORY;");
            stmt.execute("PRAGMA cache_size=-4000;");
            stmt.execute("PRAGMA foreign_keys=ON;");
        }
        return conn;
    }

    // ---------------- Live API Cache (SQLite L2 with TTL) ----------------

    public Optional<String> getCachedMuseResponse(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT response_markdown, expires_at FROM muse_response_cache WHERE cache_key = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant expiresAt = Instant.parse(rs.getString("expires_at"));
                    if (Instant.now().isBefore(expiresAt)) {
                        return Optional.ofNullable(rs.getString("response_markdown"));
                    } else {
                        deleteCachedMuseResponse(cacheKey);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error reading muse_response_cache for key {}: {}", cacheKey, e.getMessage());
        }
        return Optional.empty();
    }

    public void saveCachedMuseResponse(String cacheKey, String responseMarkdown, long ttlSeconds) {
        if (cacheKey == null || cacheKey.isBlank() || responseMarkdown == null || responseMarkdown.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1L, ttlSeconds));
        String sql = """
                INSERT INTO muse_response_cache (cache_key, response_markdown, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(cache_key) DO UPDATE SET
                    response_markdown = excluded.response_markdown,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at;
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ps.setString(2, responseMarkdown);
            ps.setString(3, now.toString());
            ps.setString(4, expiresAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to persist muse_response_cache for {}: {}", cacheKey, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void deleteCachedMuseResponse(String cacheKey) {
        if (cacheKey == null) return;
        String sql = "DELETE FROM muse_response_cache WHERE cache_key = ?";
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cacheKey);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<String> getCachedSpatialSummaryJson(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT summary_json, expires_at FROM sam_spatial_cache WHERE event_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant expiresAt = Instant.parse(rs.getString("expires_at"));
                    if (Instant.now().isBefore(expiresAt)) {
                        return Optional.ofNullable(rs.getString("summary_json"));
                    } else {
                        deleteCachedSpatialSummary(eventId);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error reading sam_spatial_cache for {}: {}", eventId, e.getMessage());
        }
        return Optional.empty();
    }

    public void saveCachedSpatialSummaryJson(String eventId, String summaryJson, int boardBees, int hotspotBees, long ttlSeconds) {
        if (eventId == null || eventId.isBlank() || summaryJson == null || summaryJson.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1L, ttlSeconds));
        String sql = """
                INSERT INTO sam_spatial_cache (event_id, summary_json, board_bees, hotspot_bees, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(event_id) DO UPDATE SET
                    summary_json = excluded.summary_json,
                    board_bees = excluded.board_bees,
                    hotspot_bees = excluded.hotspot_bees,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at;
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setString(2, summaryJson);
            ps.setInt(3, boardBees);
            ps.setInt(4, hotspotBees);
            ps.setString(5, now.toString());
            ps.setString(6, expiresAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to persist sam_spatial_cache for {}: {}", eventId, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void deleteCachedSpatialSummary(String eventId) {
        if (eventId == null) return;
        String sql = "DELETE FROM sam_spatial_cache WHERE event_id = ?";
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<byte[]> getCachedTtsAudio(String audioHash) {
        if (audioHash == null || audioHash.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT audio_bytes, expires_at FROM tts_audio_cache WHERE audio_hash = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, audioHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant expiresAt = Instant.parse(rs.getString("expires_at"));
                    if (Instant.now().isBefore(expiresAt)) {
                        byte[] bytes = rs.getBytes("audio_bytes");
                        if (bytes != null && bytes.length > 0) {
                            return Optional.of(bytes);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error reading tts_audio_cache for {}: {}", audioHash, e.getMessage());
        }
        return Optional.empty();
    }

    public void saveCachedTtsAudio(String audioHash, String spokenText, byte[] audioBytes, long ttlSeconds) {
        if (audioHash == null || audioHash.isBlank() || audioBytes == null || audioBytes.length == 0) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1L, ttlSeconds));
        String sql = """
                INSERT INTO tts_audio_cache (audio_hash, spoken_text, audio_bytes, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(audio_hash) DO UPDATE SET
                    spoken_text = excluded.spoken_text,
                    audio_bytes = excluded.audio_bytes,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at;
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, audioHash);
            ps.setString(2, spokenText);
            ps.setBytes(3, audioBytes);
            ps.setString(4, now.toString());
            ps.setString(5, expiresAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to persist tts_audio_cache for {}: {}", audioHash, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<byte[]> getCachedFrameBytes(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT frame_bytes, expires_at FROM frame_cache WHERE event_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant expiresAt = Instant.parse(rs.getString("expires_at"));
                    if (Instant.now().isBefore(expiresAt)) {
                        byte[] bytes = rs.getBytes("frame_bytes");
                        if (bytes != null && bytes.length > 0) {
                            return Optional.of(bytes);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error reading frame_cache for {}: {}", eventId, e.getMessage());
        }
        return Optional.empty();
    }

    public void saveCachedFrameBytes(String eventId, byte[] frameBytes, long ttlSeconds) {
        if (eventId == null || eventId.isBlank() || frameBytes == null || frameBytes.length == 0) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1L, ttlSeconds));
        String sql = """
                INSERT INTO frame_cache (event_id, frame_bytes, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(event_id) DO UPDATE SET
                    frame_bytes = excluded.frame_bytes,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at;
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setBytes(2, frameBytes);
            ps.setString(3, now.toString());
            ps.setString(4, expiresAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to persist frame_cache for {}: {}", eventId, e.getMessage());
        } finally {
            writeLock.unlock();
        }
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
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Error setting state for key {}: {}", key, e.getMessage());
            throw new RuntimeException(e);
        } finally {
            writeLock.unlock();
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
        writeLock.lock();
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
        } finally {
            writeLock.unlock();
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
        writeLock.lock();
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
        } finally {
            writeLock.unlock();
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

    // ---------------- Beekeeper User Management ----------------

    public Optional<BeekeeperUser> findBeekeeperByUsername(String username) {
        String sql = "SELECT id, username, password_hash, salt, role, created_at, last_login_at FROM beekeepers WHERE username = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String lastLogin = rs.getString("last_login_at");
                    return Optional.of(new BeekeeperUser(
                            rs.getString("id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("salt"),
                            rs.getString("role"),
                            Instant.parse(rs.getString("created_at")),
                            lastLogin != null ? Instant.parse(lastLogin) : null
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("Error finding beekeeper {}: {}", username, e.getMessage());
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public void saveBeekeeper(BeekeeperUser user) {
        String sql = """
                INSERT INTO beekeepers (id, username, password_hash, salt, role, created_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    password_hash = excluded.password_hash,
                    salt = excluded.salt,
                    role = excluded.role,
                    last_login_at = excluded.last_login_at;
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.id());
            ps.setString(2, user.username());
            ps.setString(3, user.passwordHash());
            ps.setString(4, user.salt());
            ps.setString(5, user.role());
            ps.setString(6, user.createdAt().toString());
            ps.setString(7, user.lastLoginAt() != null ? user.lastLoginAt().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Error saving beekeeper {}: {}", user.username(), e.getMessage());
            throw new RuntimeException(e);
        } finally {
            writeLock.unlock();
        }
    }

    public void updateBeekeeperLoginTime(String userId) {
        String sql = "UPDATE beekeepers SET last_login_at = ? WHERE id = ?";
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to update last login for {}: {}", userId, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void saveSession(String token, String userId, String username, String role, Instant createdAt, Instant expiresAt) {
        String sql = """
                INSERT INTO beekeeper_sessions (token, user_id, username, role, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(token) DO UPDATE SET
                    expires_at = excluded.expires_at;
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setString(2, userId);
            ps.setString(3, username);
            ps.setString(4, role);
            ps.setString(5, createdAt.toString());
            ps.setString(6, expiresAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to persist session for {}: {}", username, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<String> findSessionUsername(String token) {
        String sql = "SELECT username, expires_at FROM beekeeper_sessions WHERE token = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant expiresAt = Instant.parse(rs.getString("expires_at"));
                    if (Instant.now().isBefore(expiresAt)) {
                        return Optional.of(rs.getString("username"));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error querying session token: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void deleteSession(String token) {
        String sql = "DELETE FROM beekeeper_sessions WHERE token = ?";
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to delete session token: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public int countBeekeepers() {
        String sql = "SELECT COUNT(*) FROM beekeepers";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOG.error("Error counting beekeepers: {}", e.getMessage());
        }
        return 0;
    }

    // ---------------- Telemetry Logs (Prometheus & Latency Storage) ----------------

    private final java.util.concurrent.atomic.AtomicLong telemetryWriteCounter = new java.util.concurrent.atomic.AtomicLong(0);

    public void saveTelemetry(TelemetryEntry entry) {
        String sql = """
                INSERT INTO telemetry_logs (id, platform, method, uri, status_code, duration_ms, client_ip, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writeLock.lock();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.id());
            ps.setString(2, entry.platform());
            ps.setString(3, entry.method());
            ps.setString(4, entry.uri());
            ps.setInt(5, entry.statusCode());
            ps.setDouble(6, entry.durationMs());
            ps.setString(7, entry.clientIp());
            ps.setString(8, entry.timestamp().toString());
            ps.executeUpdate();

            if (telemetryWriteCounter.incrementAndGet() % 500 == 0) {
                try (Statement pruneStmt = conn.createStatement()) {
                    pruneStmt.execute("""
                            DELETE FROM telemetry_logs
                            WHERE id NOT IN (
                                SELECT id FROM telemetry_logs ORDER BY timestamp DESC LIMIT 5000
                            );
                            """);
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to save telemetry entry: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public List<TelemetryEntry> getRecentTelemetry(int limit) {
        List<TelemetryEntry> list = new ArrayList<>();
        String sql = "SELECT id, platform, method, uri, status_code, duration_ms, client_ip, timestamp FROM telemetry_logs ORDER BY timestamp DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TelemetryEntry(
                            rs.getString("id"),
                            rs.getString("platform"),
                            rs.getString("method"),
                            rs.getString("uri"),
                            rs.getInt("status_code"),
                            rs.getDouble("duration_ms"),
                            rs.getString("client_ip"),
                            Instant.parse(rs.getString("timestamp"))
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to query recent telemetry: {}", e.getMessage());
        }
        return list;
    }

    public List<TelemetryEntry> getAllTelemetry() {
        List<TelemetryEntry> list = new ArrayList<>();
        String sql = "SELECT id, platform, method, uri, status_code, duration_ms, client_ip, timestamp FROM telemetry_logs ORDER BY timestamp DESC LIMIT 5000";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new TelemetryEntry(
                        rs.getString("id"),
                        rs.getString("platform"),
                        rs.getString("method"),
                        rs.getString("uri"),
                        rs.getInt("status_code"),
                        rs.getDouble("duration_ms"),
                        rs.getString("client_ip"),
                        Instant.parse(rs.getString("timestamp"))
                ));
            }
        } catch (SQLException e) {
            LOG.error("Failed to query all telemetry: {}", e.getMessage());
        }
        return list;
    }
}
