package io.github.whyush.flashkv.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class CacheShard {
    final int shardIndex;
    final int capacity;
    final ReentrantLock lock = new ReentrantLock();
    final Map<String, CacheEntry> map;

    public CacheShard(int shardIndex, int capacity) {
        this.shardIndex = shardIndex;
        this.capacity = capacity;
        //1.0 load factor as capacity is known, init with shardCapacity
        //accessOrder:true to use LRU, every get/put, key moves to last, so remove first during eviction
        this.map = new LinkedHashMap<>(capacity, 1.0f, true);
    }

    CacheEntry get(String key) {
        return map.get(key);
    }

    boolean containsKey(String key) {
        return map.containsKey(key);
    }

    void put(String key, CacheEntry cacheEntry) {
        map.put(key, cacheEntry);
    }

    void remove(String key) {
        map.remove(key);
    }

    int size() {
        return map.size();
    }

    String eldestKey() {
        return map.keySet().iterator().next();
    }

    boolean isFull() {
        return map.size() >= capacity;
    }

    int removeExpiredEntries() {
        int removed = 0;
        var iterator = map.entrySet().iterator();
        long now = System.currentTimeMillis();

        while (iterator.hasNext()) {
            CacheEntry entry = iterator.next().getValue();
            if (entry.expiryTimeMillis < now) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }
}
