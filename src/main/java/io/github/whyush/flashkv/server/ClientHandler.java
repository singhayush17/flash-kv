package io.github.whyush.flashkv.server;

import io.github.whyush.flashkv.cache.Cache;
import io.github.whyush.flashkv.metrics.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final Cache cache;
    private final Metrics metrics;

    public ClientHandler(Socket socket, Cache cache, Metrics metrics) {
        this.socket = socket;
        this.cache = cache;
        this.metrics = metrics;
    }

    @Override
    public void run(){
        try(
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );

                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                )
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                metrics.recordCommand();
                CommandResult response = CommandParser.handle(line, cache, metrics);
                out.write(response.getResponse());
                out.newLine();
                out.flush();

                if (response.isCloseable()) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Error while parsing command closing connection");
        }
    }
}
