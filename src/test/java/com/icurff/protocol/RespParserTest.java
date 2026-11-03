package com.icurff.protocol;

import com.icurff.testutil.RespRequests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RespParserTest {
    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void retainsPartialHeaderAndPayloadInInput() throws Exception {
        RespParser parser = new RespParser();
        ByteBuffer input = ByteBuffer.allocate(32);
        input.put(ascii("*")).flip();
        assertNull(parser.parse(input));
        assertTrue(parser.hasPartialRequest());
        assertEquals("*", StandardCharsets.US_ASCII.decode(input.duplicate()).toString());

        input.compact().put(ascii("1\r\n$4\r\nPI")).flip();
        assertNull(parser.parse(input));
        assertEquals("PI", StandardCharsets.US_ASCII.decode(input.duplicate()).toString());

        input.compact().put(ascii("NG\r\n")).flip();
        assertArrayEquals(ascii("PING"), parser.parse(input).getFirst());
        assertFalse(parser.hasPartialRequest());
    }

    @Test
    void parsesEveryPossibleSplitIncludingCrLfAndBinaryPayload() throws Exception {
        byte[] frame = ascii("*3\r\n$4\r\nECHO\r\n$0\r\n\r\n$5\r\n\0\r\nXY\r\n");
        frame[frame.length - 3] = (byte) 255;
        for (int split = 1; split < frame.length; split++) {
            RespParser parser = new RespParser();
            ByteBuffer input = ByteBuffer.allocate(frame.length);
            input.put(frame, 0, split).flip();
            assertNull(parser.parse(input));
            assertTrue(parser.hasPartialRequest());
            input.compact().put(frame, split, frame.length - split).flip();
            List<byte[]> request = parser.parse(input);
            assertNotNull(request, "split=" + split);
            assertEquals(3, request.size());
            assertArrayEquals(ascii("ECHO"), request.getFirst());
            assertArrayEquals(new byte[0], request.get(1));
            assertArrayEquals(new byte[]{0, 13, 10, 'X', (byte) 255}, request.get(2));
            assertFalse(parser.hasPartialRequest());
        }
    }

    @Test
    void retainsIncompleteInputWhenReceivingOneByteAtATime() throws Exception {
        byte[] frame = ascii("*1\r\n$4\r\nPING\r\n");
        RespParser parser = new RespParser();
        ByteBuffer scratch = ByteBuffer.allocate(frame.length).flip();
        List<byte[]> request = null;
        for (int i = 0; i < frame.length; i++) {
            scratch.compact().put(frame[i]).flip();
            request = parser.parse(scratch);
            if (i < frame.length - 1) {
                assertNull(request);
            }
        }
        assertArrayEquals(ascii("PING"), request.getFirst());
    }

    @Test
    void leavesPipelineInInputAndPayloadSurvivesBufferReuse() throws Exception {
        byte[] frame = ascii("*1\r\n$4\r\nPING\r\n");
        ByteBuffer input = ByteBuffer.allocateDirect(frame.length * 2);
        input.put(frame).put(frame).flip();
        RespParser parser = new RespParser();
        List<byte[]> first = parser.parse(input);
        assertEquals(frame.length, input.remaining());
        List<byte[]> second = parser.parse(input);
        assertFalse(input.hasRemaining());
        input.clear();
        while (input.hasRemaining()) {
            input.put((byte) 0);
        }
        assertArrayEquals(ascii("PING"), first.getFirst());
        assertArrayEquals(ascii("PING"), second.getFirst());
        assertNull(parser.parse(ByteBuffer.allocate(0)));
    }

    @Test
    void parsesLargeBinaryArgumentAcrossRandomChunks() throws Exception {
        byte[] payload = new byte[128 * 1024];
        Random random = new Random(21);
        random.nextBytes(payload);
        ByteBuffer frame = ByteBuffer.allocate(payload.length + 32);
        frame.put(ascii("*1\r\n$" + payload.length + "\r\n")).put(payload).put(ascii("\r\n")).flip();
        RespParser parser = new RespParser();
        List<byte[]> request = null;
        ByteBuffer input = ByteBuffer.allocate(frame.remaining()).flip();
        while (frame.hasRemaining()) {
            int count = Math.min(frame.remaining(), 1 + random.nextInt(8192));
            ByteBuffer chunk = frame.slice();
            chunk.limit(count);
            input.compact().put(chunk).flip();
            request = parser.parse(input);
            frame.position(frame.position() + count);
            if (frame.hasRemaining()) {
                assertNull(request);
            }
        }
        assertArrayEquals(payload, request.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PING\r\n", "+PING\r\n", "*0\r\n", "*-1\r\n", "*\r\n", "*1\n",
            "*+1\r\n", "*1\rX", "*1\r\n*1\r\n", "*1\r\n$-1\r\n", "*1\r\n$\r\n",
            "*1\r\n$x\r\n", "*1\r\n$0\r\nX", "*1\r\n$1\r\nax", "*1\r\n$1\r\na\rX",
            "*2147483648\r\n", "*1\r\n$99999999999999999\r\n", "*00000000001\r\n"
    })
    void rejectsMalformedOrUnsupportedFrames(String frame) {
        RespParser parser = new RespParser();
        assertThrows(ProtocolException.class, () -> parser.parse(ByteBuffer.wrap(ascii(frame))));
        assertThrows(IllegalStateException.class, () -> parser.parse(ByteBuffer.allocate(0)));
    }

    @Test
    void acceptsRequestsBeyondFormerArgumentAndByteLimits() throws Exception {
        byte[] large = new byte[3 * 1024 * 1024];
        new Random(12).nextBytes(large);
        var arguments = new java.util.ArrayList<byte[]>();
        arguments.add(ascii("ECHO"));
        arguments.add(large);
        for (int i = 0; i < 2048; i++) {
            arguments.add(new byte[0]);
        }
        RespParser parser = new RespParser();
        List<byte[]> request = parser.parse(RespRequests.request(arguments));
        assertEquals(arguments.size(), request.size());
        assertArrayEquals(large, request.get(1));
        assertArrayEquals(new byte[0], request.getLast());
        assertFalse(parser.hasPartialRequest());
        assertNotNull(parser.parse(ByteBuffer.wrap(ascii("*1\r\n$4\r\nPING\r\n"))));
    }

    @Test
    void allowsEmptyBulk() throws Exception {
        List<byte[]> request = new RespParser()
                .parse(ByteBuffer.wrap(ascii("*1\r\n$0\r\n\r\n")));
        assertArrayEquals(new byte[0], request.getFirst());
    }
}
