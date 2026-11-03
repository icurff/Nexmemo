package com.icurff.testutil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds RESP requests for protocol and server tests. */
public final class RespRequests {
    private RespRequests() {}

    public static ByteBuffer request(List<byte[]> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("A request needs a command");
        }
        byte[] header = ("*" + arguments.size() + "\r\n").getBytes(StandardCharsets.US_ASCII);
        int size = header.length;
        for (byte[] argument : arguments) {
            int lengthDigits = Integer.toString(argument.length).length();
            size += argument.length + lengthDigits + 5;
        }
        ByteBuffer output = ByteBuffer.allocate(size);
        output.put(header);
        for (byte[] argument : arguments) {
            output.put(("$" + argument.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.put(argument).put((byte) '\r').put((byte) '\n');
        }
        return output.flip();
    }
}
