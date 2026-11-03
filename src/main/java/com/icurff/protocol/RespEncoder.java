package com.icurff.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Encoded buffers are in read mode and owned by the caller. */
public final class RespEncoder {
    private RespEncoder() {}

    public static ByteBuffer simpleString(String value) {
        return line('+', value);
    }

    public static ByteBuffer error(String value) {
        return line('-', value);
    }

    public static ByteBuffer integer(long value) {
        return ascii(":" + value + "\r\n");
    }

    public static ByteBuffer bulkString(byte[] value) {
        if (value == null) {
            return ascii("$-1\r\n");
        }
        byte[] header = ("$" + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII);
        int totalSize = header.length + value.length + 2;
        ByteBuffer output = ByteBuffer.allocate(totalSize);
        output.put(header).put(value).put((byte) '\r').put((byte) '\n');
        return output.flip();
    }

    private static ByteBuffer line(char prefix, String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("RESP lines cannot contain CR or LF");
        }
        return ByteBuffer.wrap((prefix + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuffer ascii(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.US_ASCII));
    }
}
