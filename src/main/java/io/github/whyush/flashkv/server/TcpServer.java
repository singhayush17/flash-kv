package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.Cache;
import io.github.whyush.flashkv.metrics.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TcpServer {
    private final int port;
    private final Cache cache;
    private final ThreadPoolExecutor executor;
    private final Metrics metrics;
    private final int socketReadTimeoutMillis;
    private final Set<Socket> activeConnections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket serverSocket;

    public TcpServer(int port, Cache cache, Metrics metrics) {
        this(port, cache, metrics, defaultWorkerCount(), defaultQueueCapacity(), 30_000);
    }

    public TcpServer(int port, Cache cache, Metrics metrics, int workerCount, int queueCapacity,
                     int socketReadTimeoutMillis) {
        if (workerCount <= 0 || queueCapacity < 0 || socketReadTimeoutMillis <= 0) {
            throw new IllegalArgumentException("workerCount and socketReadTimeoutMillis must be positive; queueCapacity cannot be negative");
        }
        this.port = port;
        this.cache = cache;
        this.metrics = metrics;
        this.socketReadTimeoutMillis = socketReadTimeoutMillis;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "flashkv-client-handler");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                queueCapacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueCapacity), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }
        ServerSocket listeningSocket;
        try {
            listeningSocket = new ServerSocket(port);
        } catch (IOException e) {
            running.set(false);
            throw e;
        }
        this.serverSocket = listeningSocket;
        log.info("Cache server listening to port {}", port);

        try {
            while (running.get()) {
                try {
                    Socket client = listeningSocket.accept();
                    try {
                        client.setSoTimeout(socketReadTimeoutMillis);
                        client.setTcpNoDelay(true);
                        activeConnections.add(client);
                        executor.execute(new ClientHandler(client, cache, metrics,
                                () -> activeConnections.remove(client)));
                        metrics.recordConnection();
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        activeConnections.remove(client);
                        rejectAndClose(client);
                        log.warn("Rejecting connection: handler capacity is exhausted");
                    } catch (IOException configurationFailure) {
                        activeConnections.remove(client);
                        closeQuietly(client);
                        log.debug("Unable to configure accepted client connection", configurationFailure);
                    }
                } catch (SocketException e) {
                    if (running.get()) {
                        throw e;
                    }
                }
            }
        } finally {
            running.set(false);
            closeQuietly(listeningSocket);
        }
    }

    public void shutdown() {
        running.set(false);
        closeQuietly(serverSocket);
        for (Socket connection : activeConnections) {
            closeQuietly(connection);
        }
        activeConnections.clear();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Client handler executor did not terminate within five seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? port : socket.getLocalPort();
    }

    public static int defaultWorkerCount() {
        return Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
    }

    public static int defaultQueueCapacity() {
        return defaultWorkerCount() * 2;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a socket is best effort during shutdown.
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a socket is best effort during shutdown.
            }
        }
    }

    private static void rejectAndClose(Socket socket) {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {
            writer.write("ERROR server busy");
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // The peer may already have disconnected before the rejection response.
        } finally {
            closeQuietly(socket);
        }
    }
}
