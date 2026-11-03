package com.icurff.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespEncoderTest {
    private static void encoded(String expected, ByteBuffer actual) {
        byte[] bytes = new byte[actual.remaining()];
        actual.get(bytes);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    void encodesResp2ResponseTypesAsWireBytes() {
        encoded("+OK\r\n", RespEncoder.simpleString("OK"));
        encoded("-ERR unknown command\r\n", RespEncoder.error("ERR unknown command"));
        encoded(":0\r\n", RespEncoder.integer(0));
        encoded(":-9223372036854775808\r\n", RespEncoder.integer(Long.MIN_VALUE));
        encoded(":9223372036854775807\r\n", RespEncoder.integer(Long.MAX_VALUE));
        encoded("$-1\r\n", RespEncoder.bulkString(null));
        encoded("$0\r\n\r\n", RespEncoder.bulkString(new byte[0]));
        encoded("$3\r\nà\n\r\n", RespEncoder.bulkString("à\n".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(new byte[]{'$', '3', 13, 10, 0, (byte) 255, 13, 13, 10},
                RespEncoder.bulkString(new byte[]{0, (byte) 255, 13}).array());
    }

    @Test
    void encodesCommandArrayUsingByteLengths() {
        encoded("*2\r\n$4\r\nECHO\r\n$3\r\nà\n\r\n", RespEncoder.request(List.of(
                "ECHO".getBytes(StandardCharsets.US_ASCII), "à\n".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void preventsLineInjectionAndDoesNotShareBuffers() {
        assertThrows(IllegalArgumentException.class, () -> RespEncoder.error("ERR\r\n+OK"));
        assertThrows(IllegalArgumentException.class, () -> RespEncoder.simpleString("hello\nworld"));
        assertThrows(IllegalArgumentException.class, () -> RespEncoder.request(List.of()));
        byte[] payload = {1, 2};
        ByteBuffer first = RespEncoder.bulkString(payload);
        payload[0] = 9;
        assertEquals(1, first.get(4));
        ByteBuffer pong = RespEncoder.simpleString("PONG");
        pong.get();
        encoded("+PONG\r\n", RespEncoder.simpleString("PONG"));
    }
}
