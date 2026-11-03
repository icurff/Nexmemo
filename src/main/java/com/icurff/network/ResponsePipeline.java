package com.icurff.network;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/** Request-order slots, owned and completed by the connection's event loop. */
final class ResponsePipeline {
    private final Queue<ResponseSlot> slots = new ArrayDeque<>(128);

    ResponseSlot reserve() {
        ResponseSlot responseSlot = new ResponseSlot();
        slots.add(responseSlot);
        return responseSlot;
    }

    /** Move only the completed prefix into the ordered socket-write queue. */
    void flushTo(Queue<ByteBuffer> output) {
        while (!slots.isEmpty()) {
            ResponseSlot responseSlot = slots.peek();
            if (!responseSlot.completed) {
                break;
            }
            output.add(responseSlot.response);
            slots.poll();
            responseSlot.response = null;
        }
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    static final class ResponseSlot {
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
