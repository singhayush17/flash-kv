package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.Cache;
import io.github.whyush.flashkv.metrics.Metrics;

public class CommandParser {
    public static CommandResult handle(String input, Cache cache, Metrics metrics) {
        try {
            String[] parts = input.trim().split("\\s+");
            String cmd = parts[0].toUpperCase();

            return switch (cmd) {
                case "GET" -> CommandResult.ok(handleGet(parts, cache));
                case "SET" -> CommandResult.ok(handleSet(parts, cache));
                case "DELETE" -> CommandResult.ok(handleDelete(parts, cache));
                case "STATS" -> CommandResult.ok(metrics.snapshot());
                case "QUIT" -> CommandResult.close("BYE");
                default -> CommandResult.ok("ERROR unknown command");
            };

        } catch (Exception e) {
            return CommandResult.ok("ERROR " + e.getMessage());
        }
    }

    private static String handleGet(String [] parts, Cache cache) {
        if (parts.length != 2) return "ERROR invalid GET";
        String val = cache.get(parts[1]);
        return val == null ? "NULL" : val;
    }

    private static String handleSet(String[] parts, Cache cache) {
        if (parts.length < 4) return "ERROR invalid SET: expected SET <key> <ttl> <value>";

        String key = parts[1];

        long ttl;
        try {
            ttl = Long.parseLong(parts[2]);
            if (ttl < 0) return "ERROR invalid SET: TTL must be non-negative";
        } catch (NumberFormatException e) {
            return "ERROR invalid SET: TTL must be a valid number";
        }

        String value = String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length));

        if (value.isEmpty()) return "ERROR invalid SET: value cannot be empty";

        cache.put(key, value, ttl);
        return "OK";
    }

    private static String handleDelete(String [] parts, Cache cache) {
        if (parts.length != 2) return "ERROR invalid DELETE";
        cache.delete(parts[1]);
        return "OK";
    }
}
