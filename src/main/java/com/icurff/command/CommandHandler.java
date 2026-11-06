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
        try {
            return executeCommand(arguments);
        } catch (IllegalArgumentException exception) {
            return RespEncoder.error(exception.getMessage());
        }
    }

    private ByteBuffer executeCommand(List<byte[]> arguments) {
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
                if (arguments.size() < 3) {
                    return wrongArity("set");
                }
                if (arguments.size() == 3) {
                    store.set(arguments.get(1), arguments.get(2));
                    return RespEncoder.simpleString("OK");
                }
                if (arguments.size() != 5) {
                    return RespEncoder.error("ERR syntax error");
                }
                String option = new String(arguments.get(3), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
                if (!option.equals("EX") && !option.equals("PX")) {
                    return RespEncoder.error("ERR syntax error");
                }
                long duration = parseInteger(arguments.get(4));
                if (duration <= 0) {
                    return RespEncoder.error("ERR invalid expire time in 'set' command");
                }
                if (option.equals("EX")) {
                    duration = toMillis(duration);
                }
                store.set(arguments.get(1), arguments.get(2), duration);
                return RespEncoder.simpleString("OK");
            case "EXPIRE":
            case "PEXPIRE":
                if (arguments.size() != 3) {
                    return wrongArity(command.toLowerCase(Locale.ROOT));
                }
                long timeout = parseInteger(arguments.get(2));
                if (command.equals("EXPIRE")) {
                    timeout = toMillis(timeout);
                }
                if (store.expire(arguments.get(1), timeout)) {
                    return RespEncoder.integer(1);
                }
                return RespEncoder.integer(0);
            case "TTL":
            case "PTTL":
                if (arguments.size() != 2) {
                    return wrongArity(command.toLowerCase(Locale.ROOT));
                }
                long ttl = store.pttl(arguments.get(1));
                if (command.equals("TTL") && ttl >= 0) {
                    long seconds = ttl / 1000;
                    if (ttl % 1000 >= 500) {
                        seconds++;
                    }
                    ttl = seconds;
                }
                return RespEncoder.integer(ttl);
            case "PERSIST":
                if (arguments.size() != 2) {
                    return wrongArity("persist");
                }
                if (store.persist(arguments.get(1))) {
                    return RespEncoder.integer(1);
                }
                return RespEncoder.integer(0);
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

    private static long parseInteger(byte[] bytes) {
        int start = 0;
        if (bytes.length > 0 && bytes[0] == '-') {
            start = 1;
        }
        if (start == bytes.length) {
            throw new IllegalArgumentException("ERR value is not an integer or out of range");
        }
        for (int i = start; i < bytes.length; i++) {
            if (bytes[i] < '0' || bytes[i] > '9') {
                throw new IllegalArgumentException("ERR value is not an integer or out of range");
            }
        }
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.US_ASCII));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ERR value is not an integer or out of range");
        }
    }

    private static long toMillis(long seconds) {
        if (seconds > Long.MAX_VALUE / 1000 || seconds < Long.MIN_VALUE / 1000) {
            throw new IllegalArgumentException("ERR value is not an integer or out of range");
        }
        return seconds * 1000;
    }
}
