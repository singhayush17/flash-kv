package io.github.whyush.flashkv.benchmark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closed-loop FlashKV benchmark client. Latency is client-observed: from write
 * through receipt of one newline response. --expected-interval-us optionally
 * applies coordinated-omission correction to recorded distributions.
 */
public final class JavaBenchmarkClient {
    private JavaBenchmarkClient() { }

    public static void main(String[] argv) throws Exception {
        Config config = Config.parse(argv);
        prepopulate(config);

        CountDownLatch connected = new CountDownLatch(config.clients);
        CountDownLatch warmed = new CountDownLatch(config.clients);
        CountDownLatch startWarmup = new CountDownLatch(1);
        CountDownLatch startMeasurement = new CountDownLatch(1);
        AtomicBoolean measuring = new AtomicBoolean();
        ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(config.clients);
        for (int client = 0; client < config.clients; client++) {
            int clientId = client;
            pool.execute(() -> runClient(config, clientId, connected, warmed, startWarmup, startMeasurement, measuring, results));
        }
        if (!connected.await(config.timeoutSeconds, TimeUnit.SECONDS)) throw new IllegalStateException("connection phase timed out");
        startWarmup.countDown();
        if (!warmed.await(config.warmupSeconds + config.timeoutSeconds, TimeUnit.SECONDS)) throw new IllegalStateException("warm-up phase timed out");
        long started = System.nanoTime();
        measuring.set(true);
        startMeasurement.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(config.durationSeconds + config.timeoutSeconds, TimeUnit.SECONDS)) throw new IllegalStateException("measurement phase timed out");
        printResults(config, results, (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private static void prepopulate(Config config) throws IOException {
        if (config.prepopulateKeys == 0) return;
        try (Connection connection = Connection.open(config)) {
            for (int key = 0; key < config.prepopulateKeys; key++) {
                if (!"OK".equals(connection.command("SET key-" + key + " " + config.ttlMillis + " value-key-" + key))) {
                    throw new IOException("prepopulation failed at key " + key);
                }
            }
        }
    }

    private static void runClient(Config config, int clientId, CountDownLatch connected, CountDownLatch warmed,
                                  CountDownLatch startWarmup, CountDownLatch startMeasurement, AtomicBoolean measuring,
                                  ConcurrentLinkedQueue<Result> results) {
        Result result = new Result();
        Random random = new Random(config.seed + clientId);
        boolean connectionComplete = false;
        boolean warmupComplete = false;
        try (Connection connection = Connection.open(config)) {
            String handshake = connection.command("STATS");
            if ("ERROR server busy".equals(handshake)) result.rejectedConnections++;
            else if (handshake.startsWith("ERROR")) result.failedConnections++;
            else {
                result.successfulConnections++;
                connected.countDown();
                connectionComplete = true;
                startWarmup.await();
                long warmupEnds = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.warmupSeconds);
                while (System.nanoTime() < warmupEnds) execute(connection, config, random, result, false);
                warmed.countDown();
                warmupComplete = true;
                startMeasurement.await();
                long measurementEnds = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.durationSeconds);
                while (System.nanoTime() < measurementEnds && measuring.get()) execute(connection, config, random, result, true);
            }
        } catch (Exception ignored) {
            result.failedConnections++;
        } finally {
            if (!connectionComplete) connected.countDown();
            if (!warmupComplete) warmed.countDown();
            results.add(result);
        }
    }

    private static void execute(Connection connection, Config config, Random random, Result result, boolean record) throws IOException {
        boolean get = random.nextDouble() < config.getRatio;
        String key;
        if (get && config.prepopulateKeys > 0 && random.nextDouble() < config.getHitRatio) key = "key-" + keyIndex(random, config);
        else key = get ? "miss-" + keyIndex(random, config) : "key-" + keyIndex(random, config);
        String command = get ? "GET " + key : "SET " + key + " " + config.ttlMillis + " value-" + key;
        long started = record ? System.nanoTime() : 0;
        String response = connection.command(command);
        if (!record) return;
        long elapsed = System.nanoTime() - started;
        if (response.startsWith("ERROR")) {
            result.failedOperations++;
            return;
        }
        result.successfulOperations++;
        if (get) {
            result.get.record(elapsed, config.expectedIntervalNanos);
            if ("NULL".equals(response)) result.getMisses++; else result.getHits++;
        } else result.set.record(elapsed, config.expectedIntervalNanos);
    }

    private static int keyIndex(Random random, Config config) {
        if (!config.hotKeys) return random.nextInt(config.keyspace);
        return Math.min((int) (config.keyspace * Math.pow(random.nextDouble(), 5)), config.keyspace - 1);
    }

    private static void printResults(Config config, ConcurrentLinkedQueue<Result> results, double elapsedSeconds) {
        Result total = new Result();
        results.forEach(total::add);
        Latencies all = new Latencies(); all.add(total.get); all.add(total.set);
        System.out.printf(Locale.ROOT, "connections successful=%d rejected=%d failed=%d%n", total.successfulConnections, total.rejectedConnections, total.failedConnections);
        System.out.printf(Locale.ROOT, "operations successful=%d failed=%d throughput=%.2f ops/s%n", total.successfulOperations, total.failedOperations, total.successfulOperations / elapsedSeconds);
        System.out.printf(Locale.ROOT, "GET hits=%d misses=%d hit_ratio=%s%n", total.getHits, total.getMisses,
                total.getHits + total.getMisses == 0 ? "n/a" : String.format(Locale.ROOT, "%.3f%%", 100.0 * total.getHits / (total.getHits + total.getMisses)));
        printLatency("overall", all); printLatency("GET", total.get); printLatency("SET", total.set);
        if (config.expectedIntervalNanos > 0) System.out.println("latencies include coordinated-omission correction at expected interval " + config.expectedIntervalNanos + " ns");
    }

    private static void printLatency(String name, Latencies latencies) {
        if (latencies.size == 0) { System.out.println(name + " no successful operations"); return; }
        latencies.sort();
        System.out.printf(Locale.ROOT, "%s count=%d mean=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms p99.9=%.3fms%n", name, latencies.size,
                latencies.mean / latencies.size / 1e6, latencies.percentile(50) / 1e6, latencies.percentile(95) / 1e6,
                latencies.percentile(99) / 1e6, latencies.percentile(99.9) / 1e6);
    }

    private record Connection(Socket socket, BufferedReader reader, BufferedWriter writer) implements AutoCloseable {
        static Connection open(Config config) throws IOException {
            Socket socket = new Socket(config.host, config.port);
            socket.setSoTimeout(config.timeoutSeconds * 1000); socket.setTcpNoDelay(true);
            return new Connection(socket, new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
        }
        String command(String command) throws IOException {
            writer.write(command); writer.newLine(); writer.flush();
            String response = reader.readLine();
            if (response == null) throw new IOException("incomplete response");
            return response;
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static final class Latencies {
        private long[] values = new long[1024]; private int size; private long mean;
        void record(long value, long expectedInterval) {
            add(value);
            for (long corrected = value - expectedInterval; expectedInterval > 0 && corrected > 0; corrected -= expectedInterval) add(corrected);
        }
        void add(Latencies source) { for (int i = 0; i < source.size; i++) add(source.values[i]); }
        private void add(long value) { if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2); values[size++] = value; mean += value; }
        void sort() { java.util.Arrays.sort(values, 0, size); }
        long percentile(double percentile) { return values[Math.min(size - 1, (int) Math.ceil(size * percentile / 100.0) - 1)]; }
    }

    private static final class Result {
        long successfulConnections, rejectedConnections, failedConnections, successfulOperations, failedOperations, getHits, getMisses;
        final Latencies get = new Latencies(), set = new Latencies();
        void add(Result other) { successfulConnections += other.successfulConnections; rejectedConnections += other.rejectedConnections; failedConnections += other.failedConnections; successfulOperations += other.successfulOperations; failedOperations += other.failedOperations; getHits += other.getHits; getMisses += other.getMisses; get.add(other.get); set.add(other.set); }
    }

    private record Config(String host, int port, int clients, int warmupSeconds, int durationSeconds, int keyspace,
                          int prepopulateKeys, double getRatio, double getHitRatio, long ttlMillis, boolean hotKeys,
                          long seed, int timeoutSeconds, long expectedIntervalNanos) {
        static Config parse(String[] args) {
            Map<String, String> values = new java.util.HashMap<>();
            for (int i = 0; i < args.length; i += 2) values.put(args[i], args[i + 1]);
            int keyspace = integer(values, "--keyspace", 100_000);
            int prepopulate = integer(values, "--prepopulate-keys", keyspace);
            Config config = new Config(values.getOrDefault("--host", "127.0.0.1"), integer(values, "--port", 8080), integer(values, "--clients", 32),
                    integer(values, "--warmup-seconds", 15), integer(values, "--duration-seconds", 30), keyspace, prepopulate,
                    decimal(values, "--get-ratio", .8), decimal(values, "--get-hit-ratio", 1), Long.parseLong(values.getOrDefault("--ttl-ms", "60000")),
                    "hot".equals(values.getOrDefault("--distribution", "uniform")), Long.parseLong(values.getOrDefault("--seed", "42")),
                    integer(values, "--timeout-seconds", 10), Long.parseLong(values.getOrDefault("--expected-interval-us", "0")) * 1_000);
            if (config.clients < 1 || config.keyspace < 1 || config.prepopulateKeys < 0 || config.prepopulateKeys > config.keyspace || config.durationSeconds < 1 || config.warmupSeconds < 0 || config.getRatio < 0 || config.getRatio > 1 || config.getHitRatio < 0 || config.getHitRatio > 1) throw new IllegalArgumentException("invalid arguments");
            return config;
        }
        static int integer(Map<String, String> values, String key, int defaultValue) { return Integer.parseInt(values.getOrDefault(key, Integer.toString(defaultValue))); }
        static double decimal(Map<String, String> values, String key, double defaultValue) { return Double.parseDouble(values.getOrDefault(key, Double.toString(defaultValue))); }
    }
}
