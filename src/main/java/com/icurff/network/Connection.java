package com.icurff.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import com.icurff.protocol.RespParser;

final class Connection {
    private static final int INITIAL_BUFFER_SIZE = 8 * 1024;

    ByteBuffer inputBuffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE).flip();
    final RespParser parser = new RespParser();
    final ResponsePipeline pipeline = new ResponsePipeline();
    final Queue<ByteBuffer> output = new ArrayDeque<>(128);
    boolean eof;
    boolean closeAfterWrite;

    /** Grow the full write-mode input buffer while preserving its bytes. */
    void growInputBuffer() throws IOException {
        if (inputBuffer.capacity() == Integer.MAX_VALUE) {
            throw new IOException("Input buffer exceeds Java buffer capacity");
        }
        int newCapacity = (int) Math.min((long) inputBuffer.capacity() * 2, Integer.MAX_VALUE);
        ByteBuffer larger = ByteBuffer.allocate(newCapacity);
        inputBuffer.flip();
        larger.put(inputBuffer);
        inputBuffer = larger;
    }
}
