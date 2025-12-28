package io.github.whyush.flashkv.cache;

import io.github.whyush.flashkv.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}