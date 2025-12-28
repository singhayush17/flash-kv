package io.github.whyush.flashkv.cache;

import io.github.whyush.flashkv.metrics.Metrics;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ExpiryScheduler {
    private final CacheShard[] shards;
    private final ScheduledExecutorService executor;
    private final Metrics metrics;

    public ExpiryScheduler(CacheShard[] cacheShards, Metrics metrics){
        this.shards = cacheShards;
        this.metrics = metrics;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        executor.scheduleAtFixedRate(
                this::runExpiry,
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    private void runExpiry() {
        for (CacheShard shard : shards) {
            shard.lock.lock();
            try {
                int removed = shard.removeExpiredEntries();
                if (removed > 0) {
                    metrics.recordExpired(removed);
                }
            } finally {
                shard.lock.unlock();
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
