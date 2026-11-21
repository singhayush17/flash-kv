package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.CacheManager;
import io.github.whyush.flashkv.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class TcpServerTest {
    private TcpServer server;
    private CacheManager cache;
    private Thread serverThread;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) server.shutdown();
        if (serverThread != null) serverThread.join(2_000);
        if (cache != null) cache.shutdown();
    }

    @Test
    void handlesLineSplitAcrossTcpReads() throws Exception {
        start(2, 2, 2_000);
        try (Socket socket = connect();
             BufferedReader reader = reader(socket);
             BufferedWriter writer = writer(socket)) {
            writer.write("SE"); writer.flush();
            writer.write("T split 60000 value\n"); writer.flush();
            assertEquals("OK", reader.readLine());
            writer.write("GET split\n"); writer.flush();
            assertEquals("value", reader.readLine());
        }
    }

    @Test
    void servesMultipleConcurrentPersistentClients() throws Exception {
        start(8, 16, 2_000);
        ExecutorService clients = Executors.newFixedThreadPool(16);
        List<Callable<Void>> work = new ArrayList<>();
        for (int client = 0; client < 16; client++) {
            int id = client;
            work.add(() -> {
                try (Socket socket = connect(); BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
                    for (int i = 0; i < 100; i++) {
                        writer.write("SET c" + id + "-" + i + " 60000 v\n"); writer.flush();
                        assertEquals("OK", reader.readLine());
                        writer.write("GET c" + id + "-" + i + "\n"); writer.flush();
                        assertEquals("v", reader.readLine());
                    }
                }
                return null;
            });
        }
        for (Future<Void> result : clients.invokeAll(work)) result.get(10, TimeUnit.SECONDS);
        clients.shutdownNow();
    }

    @Test
    void idleClientTimesOutAndDoesNotKeepWorkerForever() throws Exception {
        start(1, 0, 150);
        try (Socket idle = connect()) {
            Thread.sleep(300);
            try (Socket active = connect(); BufferedReader reader = reader(active); BufferedWriter writer = writer(active)) {
                writer.write("SET after-idle 60000 v\n"); writer.flush();
                assertEquals("OK", reader.readLine());
            }
        }
    }

    @Test
    void clientDisconnectDoesNotStopLaterClients() throws Exception {
        start(2, 2, 2_000);
        Socket disconnected = connect();
        disconnected.close();
        try (Socket socket = connect(); BufferedReader reader = reader(socket); BufferedWriter writer = writer(socket)) {
            writer.write("SET still-running 60000 v\n"); writer.flush();
            assertEquals("OK", reader.readLine());
        }
    }

    @Test
    void rejectedConnectionIsClosedWhenHandlerPoolIsSaturated() throws Exception {
        start(1, 0, 2_000);
        Socket occupied = connect();
        try (Socket rejected = connect()) {
            rejected.setSoTimeout(1_000);
            assertEquals("ERROR server busy", new BufferedReader(
                    new InputStreamReader(rejected.getInputStream(), UTF_8)).readLine());
        } finally {
            occupied.close();
        }
    }

    @Test
    void shutdownClosesListeningAndActiveSockets() throws Exception {
        start(1, 1, 5_000);
        Socket active = connect();
        BufferedWriter writer = writer(active);
        writer.write("STATS\n");
        writer.flush();
        assertNotNull(reader(active).readLine());
        server.shutdown();
        assertTrue(active.isClosed() || active.getInputStream().read() == -1);
        active.close();
        assertThrows(IOException.class, this::connect);
    }

    private void start(int workers, int queue, int readTimeoutMillis) throws Exception {
        cache = new CacheManager(1_000, new Metrics());
        server = new TcpServer(0, cache, new Metrics(), workers, queue, readTimeoutMillis);
        serverThread = new Thread(() -> {
            try { server.start(); } catch (IOException ignored) { }
        });
        serverThread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.getPort() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotEquals(0, server.getPort());
    }

    private Socket connect() throws IOException { return new Socket("127.0.0.1", server.getPort()); }
    private BufferedReader reader(Socket socket) throws IOException { return new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8)); }
    private BufferedWriter writer(Socket socket) throws IOException { return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8)); }
}
