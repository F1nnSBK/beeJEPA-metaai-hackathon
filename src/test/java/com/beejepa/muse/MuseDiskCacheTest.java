package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.beejepa.muse.SamGeometry.ContainedBee;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.beejepa.state.StateRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class MuseDiskCacheTest {

    @Test
    void testSpatialSummarySqlitePersistenceAndBoardBees(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);

        EventSpatialSummary summary = new EventSpatialSummary(
                "evt_test_001",
                "12:00:00",
                4.25,
                "HIGH",
                25,
                3,
                "multi_bee_interaction_cluster",
                List.of(new ContainedBee(0, List.of(300, 350, 340, 390), 0.85)),
                List.of(List.of(300, 350, 340, 390), List.of(210, 380, 240, 410)),
                "dummy_frame_base64",
                "dummy_crop_base64"
        );

        Assertions.assertTrue(cache.loadSpatialSummary("evt_test_001").isEmpty());

        cache.saveSpatialSummary(summary);

        Optional<EventSpatialSummary> loaded = cache.loadSpatialSummary("evt_test_001");
        Assertions.assertTrue(loaded.isPresent());
        Assertions.assertEquals("evt_test_001", loaded.get().eventId());
        Assertions.assertEquals(25, loaded.get().landingBoardBeeCount());
        Assertions.assertEquals(3, loaded.get().beesInsideHotspotCount());
        Assertions.assertEquals(2, loaded.get().boardBees().size());
        Assertions.assertEquals(1, loaded.get().containedBees().size());

        // Verify persistence across new cache instance backed by the same SQLite state.db
        MuseDiskCache reloaded = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);
        Assertions.assertTrue(reloaded.loadSpatialSummary("evt_test_001").isPresent());
    }

    @Test
    void testRejectsSummaryWithoutEvidenceImages(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);

        // A real SAM result with zero bees inside the hotspot is a valid measurement and must be kept
        EventSpatialSummary zeroInHotspot = new EventSpatialSummary(
                "evt_rec2125_seq000_f1217",
                "00:20",
                13.72,
                "ABOVE_TAU",
                37,
                0,
                "no_bee_inside_hotspot",
                List.of(),
                List.of(List.of(193, 412, 232, 439)),
                "frame",
                "crop"
        );
        Assertions.assertTrue(cache.isValidSpatialSummary(zeroInHotspot));

        EventSpatialSummary missingImages = new EventSpatialSummary(
                "evt_rec2125_seq000_f1217",
                "00:20",
                13.72,
                "ABOVE_TAU",
                37,
                0,
                "no_bee_inside_hotspot",
                List.of(),
                List.of(),
                "",
                ""
        );
        Assertions.assertFalse(cache.isValidSpatialSummary(missingImages));
    }

    @Test
    void testMuseResponsesTtsAndFrameSqlitePersistence(@TempDir Path tempDir) {
        MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);

        String key = "what happened at my hive today?|evt_001,evt_002";
        String report = "### Summary\nHive activity is high.";

        Assertions.assertTrue(cache.getMuseResponse(key).isEmpty());

        cache.saveMuseResponse(key, report);

        Assertions.assertEquals(Optional.of(report), cache.getMuseResponse(key));
        Assertions.assertEquals(Optional.of(report), cache.getMuseResponse("  WHAT HAPPENED AT MY HIVE TODAY?|evt_001,evt_002  "));
        // A different question must never return another question's answer
        Assertions.assertTrue(cache.getMuseResponse("are there hornets?|evt_003").isEmpty());

        byte[] dummyMp3 = new byte[1024];
        dummyMp3[0] = 0x49;
        dummyMp3[1] = 0x44;
        dummyMp3[2] = 0x33;
        cache.saveTtsAudio("hash123", "Spoken test", dummyMp3);
        Assertions.assertTrue(cache.getTtsAudio("hash123").isPresent());
        Assertions.assertEquals(1024, cache.getTtsAudio("hash123").get().length);

        byte[] dummyFrame = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        cache.saveFrameBytes("evt_001", dummyFrame);
        Assertions.assertTrue(cache.getFrameBytes("evt_001").isPresent());
        Assertions.assertArrayEquals(dummyFrame, cache.getFrameBytes("evt_001").get());

        MuseDiskCache newCacheInstance = new MuseDiskCache(JacksonFactory.createTolerantMapper(), tempDir);
        Assertions.assertEquals(Optional.of(report), newCacheInstance.getMuseResponse(key));
        Assertions.assertTrue(newCacheInstance.getTtsAudio("hash123").isPresent());
        Assertions.assertTrue(newCacheInstance.getFrameBytes("evt_001").isPresent());
    }

    @Test
    void testRedisL1AndSqliteL2DualTierCache(@TempDir Path tempDir) throws Exception {
        Map<String, byte[]> redisStore = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            pool.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        pool.submit(() -> handleRespClient(client, redisStore));
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });

            RedisCacheClient redisClient = new RedisCacheClient("127.0.0.1", port, true);
            Assertions.assertTrue(redisClient.isAvailable(), "Loopback RESP2 server should respond PONG");

            StateRepository repo = new StateRepository(tempDir.resolve("state.db"));
            MuseDiskCache cache = new MuseDiskCache(JacksonFactory.createTolerantMapper(), repo, redisClient, 3600L);
            Assertions.assertTrue(cache.isRedisActive());
            Assertions.assertEquals("REDIS_L1+SQLITE_L2", cache.getCacheBackendDescription());

            cache.saveMuseResponse("redis_test_key", "<REPORT>Live Redis Cached Report</REPORT>");
            Assertions.assertTrue(redisStore.containsKey("beejepa:muse:redis_test_key"));
            Assertions.assertEquals(
                    Optional.of("<REPORT>Live Redis Cached Report</REPORT>"),
                    cache.getMuseResponse("redis_test_key")
            );
        } finally {
            pool.shutdownNow();
        }
    }

    private static void handleRespClient(Socket socket, Map<String, byte[]> store) {
        try (socket;
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            int b = in.read();
            if (b != '*') return;
            int argCount = Integer.parseInt(readLine(in));
            List<byte[]> args = new ArrayList<>(argCount);
            for (int i = 0; i < argCount; i++) {
                int dollar = in.read();
                if (dollar != '$') return;
                int len = Integer.parseInt(readLine(in));
                byte[] buf = in.readNBytes(len);
                in.readNBytes(2); // CRLF
                args.add(buf);
            }
            String cmd = new String(args.get(0), StandardCharsets.UTF_8).toUpperCase();
            switch (cmd) {
                case "PING" -> out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                case "SET" -> {
                    String key = new String(args.get(1), StandardCharsets.UTF_8);
                    store.put(key, args.get(2));
                    out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
                }
                case "GET" -> {
                    String key = new String(args.get(1), StandardCharsets.UTF_8);
                    byte[] val = store.get(key);
                    if (val == null) {
                        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
                    } else {
                        out.write(("$" + val.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(val);
                        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                }
                case "DEL" -> {
                    String key = new String(args.get(1), StandardCharsets.UTF_8);
                    store.remove(key);
                    out.write(":1\r\n".getBytes(StandardCharsets.UTF_8));
                }
                default -> out.write("-ERR unknown\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        int cur;
        while ((cur = in.read()) != -1) {
            if (prev == '\r' && cur == '\n') {
                byte[] bytes = baos.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            baos.write(cur);
            prev = cur;
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
