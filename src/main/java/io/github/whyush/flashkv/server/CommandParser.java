package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.Cache;
import io.github.whyush.flashkv.metrics.Metrics;

public class CommandParser {
    public static CommandResult handle(String input, Cache cache, Metrics metrics) {
        try {
            int commandEnd = nextWhitespace(input, 0);
            String command = input.substring(0, commandEnd);
            int argumentStart = skipWhitespace(input, commandEnd);
            if (command.equalsIgnoreCase("GET")) {
                return CommandResult.ok(handleGet(input, argumentStart, cache));
            }
            if (command.equalsIgnoreCase("SET")) {
                return CommandResult.ok(handleSet(input, argumentStart, cache));
            }
            if (command.equalsIgnoreCase("DELETE")) {
                return CommandResult.ok(handleDelete(input, argumentStart, cache));
            }
            if (command.equalsIgnoreCase("STATS") && argumentStart == input.length()) {
                return CommandResult.ok(metrics.snapshot());
            }
            if (command.equalsIgnoreCase("QUIT") && argumentStart == input.length()) {
                return CommandResult.close("BYE");
            }
            return CommandResult.ok("ERROR unknown command");

        } catch (Exception e) {
            return CommandResult.ok("ERROR " + e.getMessage());
        }
    }

    private static String handleGet(String input, int argumentStart, Cache cache) {
        int keyEnd = nextWhitespace(input, argumentStart);
        if (argumentStart == input.length() || skipWhitespace(input, keyEnd) != input.length()) return "ERROR invalid GET";
        String val = cache.get(input.substring(argumentStart, keyEnd));
        return val == null ? "NULL" : val;
    }

    private static String handleSet(String input, int argumentStart, Cache cache) {
        int keyEnd = nextWhitespace(input, argumentStart);
        int valueStart = skipWhitespace(input, keyEnd);
        int ttlEnd = trimEnd(input);
        int ttlStart = lastTokenStart(input, ttlEnd);
        int valueEnd = trimEnd(input, ttlStart);
        if (argumentStart == input.length() || valueStart >= valueEnd || ttlStart == ttlEnd) {
            return "ERROR invalid SET: expected SET <key> <value> <ttl>";
        }

        long ttl;
        try {
            ttl = Long.parseLong(input.substring(ttlStart, ttlEnd));
            if (ttl < 0) return "ERROR invalid SET: TTL must be non-negative";
        } catch (NumberFormatException e) {
            return "ERROR invalid SET: TTL must be a valid number";
        }

        cache.put(input.substring(argumentStart, keyEnd), input.substring(valueStart, valueEnd), ttl);
        return "OK";
    }

    private static String handleDelete(String input, int argumentStart, Cache cache) {
        int keyEnd = nextWhitespace(input, argumentStart);
        if (argumentStart == input.length() || skipWhitespace(input, keyEnd) != input.length()) return "ERROR invalid DELETE";
        cache.delete(input.substring(argumentStart, keyEnd));
        return "OK";
    }

    private static int nextWhitespace(String input, int start) {
        int index = start;
        while (index < input.length() && !Character.isWhitespace(input.charAt(index))) index++;
        return index;
    }

    private static int skipWhitespace(String input, int start) {
        int index = start;
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        return index;
    }

    private static int trimEnd(String input) {
        return trimEnd(input, input.length());
    }

    private static int trimEnd(String input, int end) {
        int index = end;
        while (index > 0 && Character.isWhitespace(input.charAt(index - 1))) index--;
        return index;
    }

    private static int lastTokenStart(String input, int end) {
        int index = end;
        while (index > 0 && !Character.isWhitespace(input.charAt(index - 1))) index--;
        return index;
    }
}
