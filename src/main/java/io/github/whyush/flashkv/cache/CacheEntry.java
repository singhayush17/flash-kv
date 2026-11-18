package io.github.whyush.flashkv.cache;


public class CacheEntry {
    final String key;
    String value;
    final long expiryTimeMillis;
    long lastAccessTimeMillis;

    public CacheEntry(String key, String value, long ttlInMillis) {
        this.key = key;
        this.value = value;
        long now = System.currentTimeMillis();
        this.lastAccessTimeMillis = now;
        this.expiryTimeMillis = ttlInMillis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttlInMillis;
    }

    public boolean isExpired() {
        return this.expiryTimeMillis <= System.currentTimeMillis();
    }
}
