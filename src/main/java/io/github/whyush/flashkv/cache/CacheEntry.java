package io.github.whyush.flashkv.cache;


public class CacheEntry {
    final String key;
    String value;
    final long expiryTimeMillis;
    long lastAccessTimeMillis;

    public CacheEntry(String key, String value, long ttlInMillis) {
        this.key = key;
        this.value = value;
        this.lastAccessTimeMillis = System.currentTimeMillis();
        this.expiryTimeMillis = ttlInMillis + System.currentTimeMillis();
    }

    public boolean isExpired() {
        return this.expiryTimeMillis < System.currentTimeMillis();
    }
}
