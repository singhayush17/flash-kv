package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.CacheManager;
import io.github.whyush.flashkv.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandParserTest {
    private final Metrics metrics = new Metrics();
    private CacheManager cache;

    @AfterEach
    void shutDown() {
        if (cache != null) cache.shutdown();
    }

    @Test
    void parsesCommandsWithoutRegexAndPreservesValueSpaces() {
        cache = new CacheManager(10, metrics);
        assertEquals("OK", CommandParser.handle("SET key value with spaces 1000", cache, metrics).getResponse());
        assertEquals("value with spaces", CommandParser.handle("GET key", cache, metrics).getResponse());
        assertEquals("OK", CommandParser.handle("DELETE key", cache, metrics).getResponse());
        assertEquals("NULL", CommandParser.handle("GET key", cache, metrics).getResponse());
    }
}
