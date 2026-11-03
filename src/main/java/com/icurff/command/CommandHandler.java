package com.icurff.command;

import com.icurff.protocol.RespEncoder;
import com.icurff.storage.KvStore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Runs on the event loop; independent of sockets and connection state. */
public final class CommandHandler {
    private final KvStore store;

    public CommandHandler(KvStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Store must not be null");
        }
        this.store = store;
    }

    public ByteBuffer execute(List<byte[]> arguments) {
        String command = new String(arguments.getFirst(), StandardCharsets.US_ASCII)
                .toUpperCase(Locale.ROOT);
        switch (command) {
            case "PING":
                if (arguments.size() == 1) {
                    return RespEncoder.simpleString("PONG");
                }
                if (arguments.size() == 2) {
                    return RespEncoder.bulkString(arguments.get(1));
                }
                return wrongArity("ping");
            case "ECHO":
                if (arguments.size() != 2) {
                    return wrongArity("echo");
                }
                return RespEncoder.bulkString(arguments.get(1));
            case "SET":
                if (arguments.size() != 3) {
                    return wrongArity("set");
                }
                store.set(arguments.get(1), arguments.get(2));
                return RespEncoder.simpleString("OK");
            case "GET":
                if (arguments.size() != 2) {
                    return wrongArity("get");
                }
                return RespEncoder.bulkString(store.get(arguments.get(1)));
            case "DEL":
                if (arguments.size() < 2) {
                    return wrongArity("del");
                }
                long deleted = 0;
                for (int i = 1; i < arguments.size(); i++) {
                    if (store.delete(arguments.get(i))) {
                        deleted++;
                    }
                }
                return RespEncoder.integer(deleted);
            case "EXISTS":
                if (arguments.size() < 2) {
                    return wrongArity("exists");
                }
                long existing = 0;
                for (int i = 1; i < arguments.size(); i++) {
                    if (store.exists(arguments.get(i))) {
                        existing++;
                    }
                }
                return RespEncoder.integer(existing);
            default:
                return RespEncoder.error("ERR unknown command");
        }
    }

    private static ByteBuffer wrongArity(String command) {
        return RespEncoder.error("ERR wrong number of arguments for '" + command + "' command");
    }
}
