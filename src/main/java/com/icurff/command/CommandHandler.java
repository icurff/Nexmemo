package com.icurff.command;

import com.icurff.protocol.RespEncoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Runs on the event loop; independent of sockets and connection state. */
public final class CommandHandler {
    public ByteBuffer execute(List<byte[]> arguments) {
        String command = new String(arguments.getFirst(), StandardCharsets.US_ASCII)
                .toUpperCase(Locale.ROOT);
        return switch (command) {
            case "PING" -> switch (arguments.size()) {
                case 1 -> RespEncoder.simpleString("PONG");
                case 2 -> RespEncoder.bulkString(arguments.get(1));
                default -> wrongArity("ping");
            };
            case "ECHO" -> arguments.size() == 2
                    ? RespEncoder.bulkString(arguments.get(1)) : wrongArity("echo");
            default -> RespEncoder.error("ERR unknown command");
        };
    }

    private static ByteBuffer wrongArity(String command) {
        return RespEncoder.error("ERR wrong number of arguments for '" + command + "' command");
    }
}
