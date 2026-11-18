package io.github.whyush.flashkv.cache;

import io.github.whyush.flashkv.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CacheManagerTest {

    Metrics metrics = new Metrics();
    private CacheManager cache;
    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.shutdown();
        }
    }


    @Test
    void putAndGet() {
        cache = new CacheManager(2, metrics);

        cache.put("a", "1", 5000);

        assertEquals("1", cache.get("a"));
    }

    @Test
    void getAfterTTLReturnsNull() throws InterruptedException {
        cache = new CacheManager(2, metrics);

        cache.put("a", "1", 100);
        Thread.sleep(200);

        assertNull(cache.get("a"));
    }

    @Test
    void exceedingCapacityShouldEvictOneEntryInShard() {
        cache = new CacheManager(2, metrics);

        cache.put("a", "1", 5000);
        cache.put("b", "2", 5000);
        cache.put("c", "3", 5000);

        int present =
                (cache.get("a") != null ? 1 : 0) +
                        (cache.get("b") != null ? 1 : 0) +
                        (cache.get("c") != null ? 1 : 0);

        assertEquals(2, present);
    }

    @Test
    void expiredEntry_shouldFreeCapacityWithoutEviction() throws InterruptedException {
        cache = new CacheManager(2, metrics);

        cache.put("a", "1", 100);
        cache.put("b", "2", 5000);

        Thread.sleep(200);
        assertNull(cache.get("a")); // expired

        cache.put("c", "3", 5000);

        // b should still exist
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void overwriteKey_shouldNotTriggerEviction() {

        //test isNewKey part
        cache = new CacheManager(1, metrics);

        cache.put("a", "1", 5000);
        cache.put("a", "2", 5000);

        assertEquals("2", cache.get("a"));
    }

    @Test
    void sharding_shouldNotChangeExternalBehavior() {
        cache = new CacheManager(3, metrics);

        cache.put("a", "1", 5000);
        cache.put("b", "2", 5000);
        cache.put("c", "3", 5000);

        assertEquals("1", cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    void backgroundExpiryShouldRemoveExpiredEntriesWithoutAccess() throws InterruptedException {
        cache = new CacheManager(1, metrics);

        cache.put("a", "1", 100);
        Thread.sleep(1500);

        //evicted by background thread, if would've used get, lazy eviction would've been triggered
        assertEquals(0, cache.approximateSize());
    }

    @Test
    void zeroTtlIsImmediatelyExpired() {
        cache = new CacheManager(1, metrics);
        cache.put("now", "gone", 0);
        assertNull(cache.get("now"));
    }

    @Test
    void concurrentGetSetAndDeleteRemainWithinCapacity() throws Exception {
        cache = new CacheManager(128, metrics);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Void>> work = new ArrayList<>();
        for (int thread = 0; thread < 16; thread++) {
            int id = thread;
            work.add(() -> {
                for (int i = 0; i < 2_000; i++) {
                    String key = "key-" + ((i + id) % 256);
                    cache.put(key, "value", 60_000);
                    cache.get(key);
                    if (i % 7 == 0) cache.delete(key);
                }
                return null;
            });
        }
        for (Future<Void> future : pool.invokeAll(work)) future.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(cache.approximateSize() <= 128);
    }
}
