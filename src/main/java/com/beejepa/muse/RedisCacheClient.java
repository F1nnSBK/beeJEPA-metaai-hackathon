package com.beejepa.muse;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, zero-dependency RESP2 client for Redis cache operations (GET, SET with EX TTL, DEL, PING).
 * Automatically falls back when Redis is unreachable without blocking caller threads.
 */
@Singleton
public class RedisCacheClient {

    private static final Logger LOG = LoggerFactory.getLogger(RedisCacheClient.class);
    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final long FAILURE_COOLDOWN_MS = 5_000L;

    private final String host;
    private final int port;
    private final boolean enabled;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final AtomicLong lastFailureTimeMs = new AtomicLong(0L);

    @jakarta.inject.Inject
    public RedisCacheClient() {
        this(
                resolveHost(),
                resolvePort(),
                resolveEnabled(),
                300,
                1500
        );
    }

    public RedisCacheClient(String host, int port, boolean enabled) {
        this(host, port, enabled, 300, 1500);
    }

    public RedisCacheClient(String host, int port, boolean enabled, int connectTimeoutMs, int readTimeoutMs) {
        this.host = (host != null && !host.isBlank()) ? host.trim() : "127.0.0.1";
        this.port = port > 0 ? port : 6379;
        this.enabled = enabled;
        this.connectTimeoutMs = Math.max(50, connectTimeoutMs);
        this.readTimeoutMs = Math.max(100, readTimeoutMs);
    }

    private static String resolveHost() {
        String h = Sam31Client.getEnvOrDotEnv("REDIS_HOST");
        return h.isBlank() ? "127.0.0.1" : h;
    }

    private static int resolvePort() {
        String p = Sam31Client.getEnvOrDotEnv("REDIS_PORT");
        if (!p.isBlank()) {
            try {
                return Integer.parseInt(p.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 6379;
    }

    private static boolean resolveEnabled() {
        String e = Sam31Client.getEnvOrDotEnv("REDIS_ENABLED");
        if (!e.isBlank()) {
            return !"false".equalsIgnoreCase(e.trim());
        }
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAvailable() {
        return ping();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    private boolean isInCooldown() {
        long lastFail = lastFailureTimeMs.get();
        return lastFail > 0 && (System.currentTimeMillis() - lastFail) < FAILURE_COOLDOWN_MS;
    }

    private void recordFailure() {
        lastFailureTimeMs.set(System.currentTimeMillis());
    }

    private void resetFailure() {
        lastFailureTimeMs.set(0L);
    }

    public boolean ping() {
        if (!enabled) {
            return false;
        }
        try (Socket socket = connect()) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            writeCommand(out, "PING".getBytes(StandardCharsets.UTF_8));
            byte prefix = readByte(in);
            String line = readLine(in);
            resetFailure();
            return prefix == '+' && "PONG".equalsIgnoreCase(line);
        } catch (IOException e) {
            recordFailure();
            return false;
        }
    }

    public Optional<String> getString(String key) {
        return getBytes(key).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    public Optional<byte[]> getBytes(String key) {
        if (!enabled || key == null || key.isBlank() || isInCooldown()) {
            return Optional.empty();
        }
        try (Socket socket = connect()) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            writeCommand(out, "GET".getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
            byte[] result = readBulkResponse(in);
            resetFailure();
            return Optional.ofNullable(result);
        } catch (IOException e) {
            recordFailure();
            LOG.debug("[RedisCacheClient] GET failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean setString(String key, String value, long ttlSeconds) {
        if (value == null) {
            return false;
        }
        return setBytes(key, value.getBytes(StandardCharsets.UTF_8), ttlSeconds);
    }

    public boolean setBytes(String key, byte[] value, long ttlSeconds) {
        if (!enabled || key == null || key.isBlank() || value == null || isInCooldown()) {
            return false;
        }
        try (Socket socket = connect()) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            long effectiveTtl = Math.max(1L, ttlSeconds);
            writeCommand(
                    out,
                    "SET".getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8),
                    value,
                    "EX".getBytes(StandardCharsets.UTF_8),
                    Long.toString(effectiveTtl).getBytes(StandardCharsets.UTF_8)
            );
            byte prefix = readByte(in);
            String reply = readLine(in);
            resetFailure();
            return prefix == '+' && "OK".equalsIgnoreCase(reply);
        } catch (IOException e) {
            recordFailure();
            LOG.debug("[RedisCacheClient] SET failed for key {}: {}", key, e.getMessage());
            return false;
        }
    }

    public boolean delete(String key) {
        if (!enabled || key == null || key.isBlank() || isInCooldown()) {
            return false;
        }
        try (Socket socket = connect()) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            writeCommand(out, "DEL".getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
            byte prefix = readByte(in);
            readLine(in);
            resetFailure();
            return prefix == ':';
        } catch (IOException e) {
            recordFailure();
            return false;
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(readTimeoutMs);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        return socket;
    }

    private static void writeCommand(OutputStream out, byte[]... args) throws IOException {
        out.write(('*' + Integer.toString(args.length)).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        for (byte[] arg : args) {
            out.write(('$' + Integer.toString(arg.length)).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
            out.write(arg);
            out.write(CRLF);
        }
        out.flush();
    }

    private static byte[] readBulkResponse(InputStream in) throws IOException {
        byte prefix = readByte(in);
        String line = readLine(in);
        if (prefix == '$') {
            int len = Integer.parseInt(line.trim());
            if (len < 0) {
                return null;
            }
            byte[] payload = in.readNBytes(len);
            if (payload.length < len) {
                throw new EOFException("Truncated Redis bulk string payload");
            }
            // Consume trailing CRLF
            readByte(in);
            readByte(in);
            return payload;
        } else if (prefix == '+') {
            return line.getBytes(StandardCharsets.UTF_8);
        } else if (prefix == '-') {
            throw new IOException("Redis error reply: " + line);
        }
        return null;
    }

    private static byte readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Unexpected EOF from Redis socket");
        }
        return (byte) b;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Unexpected EOF while reading line from Redis");
            }
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    break;
                }
                buf.write(b);
                if (next >= 0) {
                    buf.write(next);
                }
            } else {
                buf.write(b);
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
