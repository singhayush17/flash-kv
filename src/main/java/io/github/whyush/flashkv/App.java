package io.github.whyush.flashkv;

import io.github.whyush.flashkv.cache.CacheManager;
import io.github.whyush.flashkv.metrics.Metrics;
import io.github.whyush.flashkv.server.TcpServer;

public class App {
    public static void main(String[] args) throws Exception {

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int capacity = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int workers = args.length > 2 ? Integer.parseInt(args[2]) : TcpServer.defaultWorkerCount();
        int queueCapacity = args.length > 3 ? Integer.parseInt(args[3]) : TcpServer.defaultQueueCapacity();
        int readTimeoutMillis = args.length > 4 ? Integer.parseInt(args[4]) : 30_000;
        int shards = args.length > 5 ? Integer.parseInt(args[5]) : 4;

        Metrics metrics = new Metrics();
        CacheManager cache = new CacheManager(capacity, shards, metrics);
        TcpServer server = new TcpServer(port, cache, metrics, workers, queueCapacity, readTimeoutMillis);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.shutdown();
            cache.shutdown();
        }));

        server.start();

    }
}
