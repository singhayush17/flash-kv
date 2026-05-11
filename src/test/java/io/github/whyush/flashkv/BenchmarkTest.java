package io.github.whyush.flashkv;

import io.github.whyush.flashkv.cache.CacheManager;
import io.github.whyush.flashkv.metrics.Metrics;
import io.github.whyush.flashkv.server.TcpServer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BenchmarkTest {

    private static final int BENCH_OPS   = 100_000;
    private static final int WARMUP_OPS  = 10_000;
    private static final int PORT_SINGLE = 7379;
    private static final int PORT_MULTI  = 7380;

    // ── 1. Single-threaded in-process latency ────────────────────────────────
    @Test @Order(1)
    void bench1_singleThreadedInProcessLatency() {
        Metrics m = new Metrics();
        CacheManager cache = new CacheManager(200_000, m);

        for (int i = 0; i < WARMUP_OPS; i++) {
            cache.put("w" + i, "v" + i, 60_000);
            cache.get("w" + i);
        }

        long[] setNs = new long[BENCH_OPS];
        for (int i = 0; i < BENCH_OPS; i++) {
            long t = System.nanoTime();
            cache.put("k" + i, "val" + i, 60_000);
            setNs[i] = System.nanoTime() - t;
        }

        long[] getNs = new long[BENCH_OPS];
        for (int i = 0; i < BENCH_OPS; i++) {
            long t = System.nanoTime();
            cache.get("k" + (i % BENCH_OPS));
            getNs[i] = System.nanoTime() - t;
        }

        Arrays.sort(setNs);
        Arrays.sort(getNs);

        System.out.printf("%n╔══════════════════════════════════════════════════════════╗%n");
        System.out.printf("║          FlashKV Benchmark Results                       ║%n");
        System.out.printf("╚══════════════════════════════════════════════════════════╝%n");
        System.out.printf("%n── [1] In-Process Latency  (%,d ops, single thread) ─────────%n", BENCH_OPS);
        printLatencyUs("GET", getNs);
        printLatencyUs("SET", setNs);

        cache.shutdown();
    }

    // ── 2. Concurrent in-process throughput ─────────────────────────────────
    @Test @Order(2)
    void bench2_concurrentThroughput() throws InterruptedException {
        int[] threadCounts  = {1, 4, 8, 16, 32, 64, 250};
        int   OPS_PER_THREAD = 10_000;

        System.out.printf("%n── [2] Concurrent In-Process Throughput  (80%% GET / 20%% SET) ──%n");
        System.out.printf("  %-10s  %-18s  %-8s%n", "Threads", "Throughput (ops/s)", "Errors");

        for (int threads : threadCounts) {
            Metrics m = new Metrics();
            CacheManager cache = new CacheManager(100_000, m);
            for (int i = 0; i < 50_000; i++) cache.put("k" + i, "v", 60_000);

            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go    = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            AtomicLong ops       = new AtomicLong();
            AtomicLong errs      = new AtomicLong();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        for (int i = 0; i < OPS_PER_THREAD; i++) {
                            if (i % 5 == 0) cache.put("t" + tid + "k" + i, "v", 30_000);
                            else            cache.get("k" + (i % 50_000));
                            ops.incrementAndGet();
                        }
                    } catch (Exception e) { errs.incrementAndGet(); }
                    finally { done.countDown(); }
                });
            }

            ready.await();
            long t0 = System.nanoTime();
            go.countDown();
            done.await();
            double sec = (System.nanoTime() - t0) / 1e9;

            System.out.printf("  %-10d  %-18,.0f  %-8d%n", threads, ops.get() / sec, errs.get());
            pool.shutdown();
            cache.shutdown();
        }
    }

    // ── 3. Hit rate ──────────────────────────────────────────────────────────
    @Test @Order(3)
    void bench3_hitRate() {
        Metrics m = new Metrics();
        CacheManager cache = new CacheManager(10_000, m);
        for (int i = 0; i < 10_000; i++) cache.put("k" + i, "v" + i, 120_000);

        Random rng = new Random(42);
        int hits = 0, getOps = 0;
        for (int i = 0; i < 100_000; i++) {
            if (rng.nextInt(5) == 0) {
                cache.put("new" + i, "val", 60_000);
            } else {
                getOps++;
                if (cache.get("k" + rng.nextInt(10_000)) != null) hits++;
            }
        }

        System.out.printf("%n── [3] Hit Rate  (10K capacity, warm cache, 80%% GET / 20%% SET) ──%n");
        System.out.printf("  Hit rate : %.1f%%  (%,d hits / %,d reads)%n",
                hits * 100.0 / getOps, hits, getOps);

        cache.shutdown();
    }

    // ── 4. LRU eviction accuracy ─────────────────────────────────────────────
    @Test @Order(4)
    void bench4_lruEviction() {
        Metrics m = new Metrics();
        CacheManager cache = new CacheManager(1_000, m);

        for (int i = 0; i < 1_000; i++) cache.put("k" + i, "v" + i, 60_000);
        // touch first 100 → mark as recently used
        for (int i = 0; i < 100; i++) cache.get("k" + i);
        // insert 200 new → should evict 200 LRU (not the ones we just touched)
        for (int i = 1_000; i < 1_200; i++) cache.put("k" + i, "v" + i, 60_000);

        int hotAlive = 0;
        for (int i = 0; i < 100; i++) if (cache.get("k" + i) != null) hotAlive++;

        System.out.printf("%n── [4] LRU Eviction Accuracy ────────────────────────────────%n");
        System.out.printf("  Recently-accessed keys surviving eviction: %d / 100%n", hotAlive);

        cache.shutdown();
    }

    // ── 5. TTL expiry accuracy ────────────────────────────────────────────────
    @Test @Order(5)
    void bench5_ttlAccuracy() throws InterruptedException {
        Metrics m = new Metrics();
        CacheManager cache = new CacheManager(1_000, m);

        int count = 500;
        for (int i = 0; i < count; i++) cache.put("t" + i, "v", 200); // 200 ms TTL

        int before = 0;
        for (int i = 0; i < count; i++) if (cache.get("t" + i) != null) before++;

        Thread.sleep(350); // past TTL

        int after = 0;
        for (int i = 0; i < count; i++) if (cache.get("t" + i) != null) after++;

        System.out.printf("%n── [5] TTL Expiry Accuracy ──────────────────────────────────%n");
        System.out.printf("  Immediately after insert : %d / %d present%n", before, count);
        System.out.printf("  After 350 ms (TTL=200ms) : %d / %d present%n", after, count);

        cache.shutdown();
    }

    // ── 6. TCP single-client latency ─────────────────────────────────────────
    @Test @Order(6)
    void bench6_tcpSingleClientLatency() throws Exception {
        Metrics m = new Metrics();
        CacheManager cache = new CacheManager(50_000, m);
        startServer(PORT_SINGLE, cache, m);

        int TCP_OPS = 5_000;

        // warm-up
        try (Socket s = connect(PORT_SINGLE);
             BufferedReader in = reader(s);
             PrintWriter out   = writer(s)) {
            for (int i = 0; i < 500; i++) {
                out.println("SET w" + i + " 60000 val"); in.readLine();
                out.println("GET w" + i);               in.readLine();
            }
        }

        long[] setNs = new long[TCP_OPS];
        long[] getNs = new long[TCP_OPS];

        try (Socket s = connect(PORT_SINGLE);
             BufferedReader in = reader(s);
             PrintWriter out   = writer(s)) {
            for (int i = 0; i < TCP_OPS; i++) {
                long t = System.nanoTime();
                out.println("SET k" + i + " 60000 v" + i); in.readLine();
                setNs[i] = System.nanoTime() - t;
            }
            for (int i = 0; i < TCP_OPS; i++) {
                long t = System.nanoTime();
                out.println("GET k" + (i % TCP_OPS)); in.readLine();
                getNs[i] = System.nanoTime() - t;
            }
        }

        Arrays.sort(setNs); Arrays.sort(getNs);

        System.out.printf("%n── [6] TCP Latency  (localhost, single client, %,d ops) ─────%n", TCP_OPS);
        printLatencyMs("GET", getNs);
        printLatencyMs("SET", setNs);

        cache.shutdown();
    }

    // ── 7. TCP — 250 concurrent clients ──────────────────────────────────────
    @Test @Order(7)
    void bench7_tcp250ConcurrentClients() throws Exception {
        int CLIENTS   = 250;
        int OPS_EACH  = 400;   // 80% GET / 20% SET

        Metrics m = new Metrics();
        CacheManager cache = new CacheManager(50_000, m);
        startServer(PORT_MULTI, cache, m);

        // pre-populate so GETs mostly hit
        try (Socket s = connect(PORT_MULTI);
             BufferedReader in = reader(s);
             PrintWriter out   = writer(s)) {
            for (int i = 0; i < 10_000; i++) {
                out.println("SET k" + i + " 60000 v" + i);
                in.readLine();
            }
        }

        CountDownLatch ready  = new CountDownLatch(CLIENTS);
        CountDownLatch go     = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(CLIENTS);
        AtomicLong totalOps   = new AtomicLong();
        AtomicLong errors     = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        for (int c = 0; c < CLIENTS; c++) {
            final int cid = c;
            pool.submit(() -> {
                try (Socket s = connect(PORT_MULTI);
                     BufferedReader in = reader(s);
                     PrintWriter out   = writer(s)) {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < OPS_EACH; i++) {
                        if (i % 5 == 0) {
                            out.println("SET c" + cid + "k" + i + " 60000 v");
                        } else {
                            out.println("GET k" + (i % 10_000));
                        }
                        in.readLine();
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
                finally { done.countDown(); }
            });
        }

        ready.await();
        long t0 = System.nanoTime();
        go.countDown();
        done.await(120, TimeUnit.SECONDS);
        double sec = (System.nanoTime() - t0) / 1e9;

        long completed = totalOps.get();
        System.out.printf("%n── [7] TCP — 250 Concurrent Clients  (%d ops each) ──────────%n", OPS_EACH);
        System.out.printf("  Clients connected : %d%n", CLIENTS);
        System.out.printf("  Total ops         : %,d%n", completed);
        System.out.printf("  Elapsed           : %.2f s%n", sec);
        System.out.printf("  Throughput        : %,.0f ops/sec%n", completed / sec);
        System.out.printf("  Avg latency/op    : %.2f ms%n", (sec * 1000.0) / (completed / (double) CLIENTS));
        System.out.printf("  Errors            : %d%n", errors.get());

        pool.shutdown();
        cache.shutdown();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void startServer(int port, CacheManager cache, Metrics metrics)
            throws InterruptedException {
        TcpServer server = new TcpServer(port, cache, metrics);
        Thread t = new Thread(() -> {
            try { server.start(); } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();

        for (int i = 0; i < 30; i++) {
            try (Socket probe = new Socket("127.0.0.1", port)) { break; }
            catch (Exception e) { Thread.sleep(100); }
        }
    }

    private Socket connect(int port) throws IOException {
        return new Socket("127.0.0.1", port);
    }

    private BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream()));
    }

    private PrintWriter writer(Socket s) throws IOException {
        return new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream())), true);
    }

    private void printLatencyUs(String op, long[] sortedNs) {
        int n = sortedNs.length;
        System.out.printf("  %-4s  avg=%5.2fμs  p50=%5.2fμs  p95=%6.2fμs  p99=%7.2fμs  p999=%8.2fμs%n",
                op,
                Arrays.stream(sortedNs).average().orElse(0) / 1_000.0,
                sortedNs[n / 2]              / 1_000.0,
                sortedNs[(int)(n * 0.95)]    / 1_000.0,
                sortedNs[(int)(n * 0.99)]    / 1_000.0,
                sortedNs[(int)(n * 0.999)]   / 1_000.0);
    }

    private void printLatencyMs(String op, long[] sortedNs) {
        int n = sortedNs.length;
        System.out.printf("  %-4s  avg=%.2fms  p50=%.2fms  p95=%.2fms  p99=%.2fms%n",
                op,
                Arrays.stream(sortedNs).average().orElse(0) / 1_000_000.0,
                sortedNs[n / 2]           / 1_000_000.0,
                sortedNs[(int)(n * 0.95)] / 1_000_000.0,
                sortedNs[(int)(n * 0.99)] / 1_000_000.0);
    }
}
