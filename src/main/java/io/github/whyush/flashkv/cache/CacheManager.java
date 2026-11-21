package io.github.whyush.flashkv.cache;


import io.github.whyush.flashkv.metrics.Metrics;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheManager implements Cache {
    private final CacheShard[] shards;
    private final int shardCount;
    private final ExpiryScheduler expiryScheduler;
    private final Metrics metrics;

    private static final int DEFAULT_SHARDS = 4;

    public CacheManager(int capacity, Metrics metrics) {
        this(capacity, DEFAULT_SHARDS, metrics);
    }

    public CacheManager(int capacity, int requestedShardCount, Metrics metrics) {

        if (capacity <= 0 || requestedShardCount <= 0) throw new IllegalArgumentException();

        // A shard must have at least one entry of capacity.
        int shardCount = Math.min(requestedShardCount, capacity);
        this.shardCount = shardCount;
        this.shards = new CacheShard[shardCount];
        this.metrics = metrics;

        int baseShardCapacity = capacity / shardCount;
        int remaining = capacity % shardCount;

        for (int i = 0; i < shardCount; i++) {
            int shardCapacity = baseShardCapacity + (i < remaining ? 1 : 0);
            shards[i] = new CacheShard(i, shardCapacity);
        }

        this.expiryScheduler = new ExpiryScheduler(shards, metrics);
        this.expiryScheduler.start();
    }

    private CacheShard shardForKey(String key) {
        // int index = Math.abs(key.hashCode()) % 4; --> wont work
        //Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE --> still negative

        //Clears the sign bit, (&& with INT_MAX)
        int index = (key.hashCode() & 0x7fffffff) % shardCount;
        return shards[index];
    }

    @Override
    public String get(String key) {
        CacheShard shard = shardForKey(key);
        shard.lock.lock();
        try {
            CacheEntry cacheEntry = shard.get(key);
            if (cacheEntry == null) {
                metrics.recordMiss();
                return null;
            }

            if (cacheEntry.isExpired()) {
                metrics.recordExpired();
                metrics.recordMiss();
                shard.remove(key);
                return null;
            }

            cacheEntry.lastAccessTimeMillis = System.currentTimeMillis();
            metrics.recordHit();
            return cacheEntry.value;
        } finally {
            shard.lock.unlock();
        }
    }

    @Override
    public void put(String key, String value, long ttlMillis) {

        CacheShard shard = shardForKey(key);
        shard.lock.lock();

        try {
            boolean isNewKey = !shard.containsKey(key);
            if (isNewKey && shard.isFull()) {
                // Scan only under capacity pressure. Scanning every SET made writes O(shard-size)
                // even though the scheduler and lazy GET path already handle normal expiry.
                int expired = shard.removeExpiredEntries();
                if (expired > 0) {
                    metrics.recordExpired(expired);
                }
                if (shard.isFull()) {
                    evictLRU(shard);
                }
            }
            shard.put(key, new CacheEntry(key, value, ttlMillis));
        } finally {
            shard.lock.unlock();
        }
    }

    @Override
    public void delete(String key) {

        CacheShard shard = shardForKey(key);
        shard.lock.lock();

        try {
            shard.remove(key);
        } finally {
            shard.lock.unlock();
        }
    }

    private void evictLRU(CacheShard shard) {
        String lruKey = shard.eldestKey();
        metrics.recordEviction();
        shard.remove(lruKey);
    }

    //used for testing background eviction, why approximate? --> no global locking
    int approximateSize() {
        int size = 0;
        for (CacheShard shard : shards) {
            shard.lock.lock();
            try {
                size += shard.size();
            } finally {
                shard.lock.unlock();
            }
        }
        return size;
    }

    public void shutdown() {
        expiryScheduler.shutdown();
    }

    public int shardCount() {
        return shardCount;
    }
}
