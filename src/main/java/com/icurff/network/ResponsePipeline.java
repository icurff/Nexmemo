package com.icurff.network;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/** Request-order slots, owned and completed by the connection's event loop. */
final class ResponsePipeline {
    private final Queue<Slot> slots = new ArrayDeque<>(128);

    Slot reserve() {
        Slot slot = new Slot();
        slots.add(slot);
        return slot;
    }

    /** Move only the completed prefix into the ordered socket-write queue. */
    void flushTo(Queue<ByteBuffer> output) {
        while (!slots.isEmpty()) {
            Slot slot = slots.peek();
            if (!slot.completed) {
                break;
            }
            output.add(slot.response);
            slots.poll();
            slot.response = null;
        }
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    static final class Slot {
        private ByteBuffer response;
        private boolean completed;

        void complete(ByteBuffer response) {
            if (completed) {
                throw new IllegalStateException("Response slot already completed");
            }
            if (response == null) {
                throw new NullPointerException("Response must not be null");
            }
            this.response = response;
            completed = true;
        }
    }
}
