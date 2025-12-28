package io.github.whyush.flashkv.cache;

public interface Cache {
    String get(String key);
    void put(String key, String value, long ttlMillis);
    void delete(String key);
}