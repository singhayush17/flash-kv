package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.Cache;
import io.github.whyush.flashkv.metrics.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class TcpServer {
    private final int port;
    private final Cache cache;
    private final ExecutorService executor;
    private final Metrics metrics;

    public TcpServer(int port, Cache cache, Metrics metrics) {
        this.port = port;
        this.cache = cache;
        this.metrics = metrics;
        this.executor = Executors.newFixedThreadPool(8);
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        log.info("Cache server listening to port {}", port);

        while (true) {
            Socket client = serverSocket.accept();
            metrics.recordConnection();
            executor.submit(new ClientHandler(client, cache, metrics));
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
