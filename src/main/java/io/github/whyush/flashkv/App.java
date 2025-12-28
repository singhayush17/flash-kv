package io.github.whyush.flashkv;

import io.github.whyush.flashkv.cache.CacheManager;
import io.github.whyush.flashkv.metrics.Metrics;
import io.github.whyush.flashkv.server.TcpServer;

public class App {
    public static void main(String[] args) throws Exception {

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int capacity = args.length > 1 ? Integer.parseInt(args[1]) : 1000;

        Metrics metrics = new Metrics();
        CacheManager cache = new CacheManager(capacity, metrics);
        TcpServer server = new TcpServer(port, cache, metrics);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.shutdown();
            cache.shutdown();
        }));

        server.start();

    }
}