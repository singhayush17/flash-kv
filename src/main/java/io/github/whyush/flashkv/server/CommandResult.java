package io.github.whyush.flashkv.server;

public class CommandResult {
    private final String response;
    private final boolean closeable;

    public CommandResult(String response, boolean shouldClose) {
        this.response = response;
        this.closeable = shouldClose;
    }

    public String getResponse() {
        return response;
    }

    public boolean isCloseable() {
        return closeable;
    }

    public static CommandResult ok(String response) {
        return new CommandResult(response, false);
    }

    public static CommandResult close(String response) {
        return new CommandResult(response, true);
    }
}
