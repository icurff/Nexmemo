package com.icurff.network;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class ResponsePipelineTest {
    @Test
    void holdsCompletedResponsesBehindPendingHead() {
        ResponsePipeline pipeline = new ResponsePipeline();
        Queue<ByteBuffer> output = new ArrayDeque<>(128);
        var first = pipeline.reserve();
        var second = pipeline.reserve();
        var third = pipeline.reserve();
        ByteBuffer a = ByteBuffer.wrap(new byte[]{1});
        ByteBuffer b = ByteBuffer.wrap(new byte[]{2});
        ByteBuffer c = ByteBuffer.wrap(new byte[]{3});

        third.complete(c);
        second.complete(b);
        pipeline.flushTo(output);
        assertTrue(output.isEmpty());
        assertFalse(pipeline.isEmpty());

        first.complete(a);
        pipeline.flushTo(output);
        assertSame(a, output.poll());
        assertSame(b, output.poll());
        assertSame(c, output.poll());
        assertTrue(output.isEmpty());
        assertTrue(pipeline.isEmpty());
    }

    @Test
    void flushesReadyPrefixWithoutWaitingForEntirePipeline() {
        ResponsePipeline pipeline = new ResponsePipeline();
        Queue<ByteBuffer> output = new ArrayDeque<>(128);
        var first = pipeline.reserve();
        var second = pipeline.reserve();
        var third = pipeline.reserve();
        ByteBuffer a = ByteBuffer.wrap(new byte[]{1});
        ByteBuffer b = ByteBuffer.wrap(new byte[]{2});
        ByteBuffer c = ByteBuffer.wrap(new byte[]{3});

        first.complete(a);
        third.complete(c);
        pipeline.flushTo(output);
        assertEquals(1, output.size());
        assertSame(a, output.peek());
        assertFalse(pipeline.isEmpty());
        pipeline.flushTo(output);
        assertEquals(1, output.size());

        second.complete(b);
        pipeline.flushTo(output);
        assertSame(a, output.poll());
        assertSame(b, output.poll());
        assertSame(c, output.poll());
        assertTrue(output.isEmpty());
        assertTrue(pipeline.isEmpty());
    }

    @Test
    void preservesPendingSlotReferencesAndResponseOrderAcrossGrowth() {
        ResponsePipeline pipeline = new ResponsePipeline();
        Queue<ByteBuffer> output = new ArrayDeque<>(128);
        var slots = new ArrayList<ResponsePipeline.ResponseSlot>();
        var responses = new ArrayList<ByteBuffer>();
        for (int i = 0; i < 400; i++) {
            slots.add(pipeline.reserve());
            responses.add(ByteBuffer.allocate(4).putInt(i).flip());
        }
        for (int i = slots.size() - 1; i > 0; i--) {
            slots.get(i).complete(responses.get(i));
        }
        pipeline.flushTo(output);
        assertTrue(output.isEmpty());
        slots.getFirst().complete(responses.getFirst());
        pipeline.flushTo(output);
        for (ByteBuffer response : responses) {
            assertSame(response, output.poll());
        }
        assertTrue(output.isEmpty());
        assertTrue(pipeline.isEmpty());
    }
}
