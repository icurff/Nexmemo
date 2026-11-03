package com.icurff.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class RespParser {
    private enum State {
        ARRAY_PREFIX, ARRAY_LENGTH, ARRAY_LF, BULK_PREFIX, BULK_LENGTH, BULK_LF,
        DATA, DATA_CR, DATA_LF
    }

    private State state = State.ARRAY_PREFIX;
    private List<byte[]> arguments;
    private int argumentCount;
    private byte[] payload;
    private int payloadOffset;
    private int number;
    private int digits;
    private boolean failed;

    /**
     * Consumes input in read mode. Returns one request, or null if more bytes are
     * needed. The result owns its payload arrays and never retains input.
     * Leaves subsequent pipelined requests in input.
     * After a ProtocolException this parser cannot be reused.
     */
    public List<byte[]> parse(ByteBuffer input) throws ProtocolException {
        if (failed) {
            throw new IllegalStateException("Parser failed; close the connection");
        }
        try {
            while (input.hasRemaining()) {
                if (state == State.DATA) {
                    int count = Math.min(input.remaining(), payload.length - payloadOffset);
                    input.get(payload, payloadOffset, count);
                    payloadOffset += count;
                    if (payloadOffset == payload.length) {
                        state = State.DATA_CR;
                    }
                    continue;
                }

                byte value = input.get();
                switch (state) {
                    case ARRAY_PREFIX, BULK_PREFIX -> {
                        boolean array = state == State.ARRAY_PREFIX;
                        expect(value, array ? '*' : '$');
                        number = 0;
                        digits = 0;
                        state = array ? State.ARRAY_LENGTH : State.BULK_LENGTH;
                    }
                    case ARRAY_LENGTH, BULK_LENGTH -> {
                        boolean array = state == State.ARRAY_LENGTH;
                        if (value == '\r') {
                            if (digits == 0) {
                                throw new ProtocolException("missing length");
                            }
                            if (array && number == 0) {
                                throw new ProtocolException("empty command array");
                            }
                            state = array ? State.ARRAY_LF : State.BULK_LF;
                        } else {
                            if (value < '0' || value > '9') {
                                throw new ProtocolException("expected unsigned decimal length");
                            }
                            long next = (long) number * 10 + (value - '0');
                            digits++;
                            if (digits > 10 || next > Integer.MAX_VALUE) {
                                throw new ProtocolException("length exceeds Java array capacity");
                            }
                            number = (int) next;
                        }
                    }
                    case ARRAY_LF -> {
                        expect(value, '\n');
                        argumentCount = number;
                        arguments = new ArrayList<>();
                        state = State.BULK_PREFIX;
                    }
                    case BULK_LF -> {
                        expect(value, '\n');
                        payload = new byte[number];
                        payloadOffset = 0;
                        state = number == 0 ? State.DATA_CR : State.DATA;
                    }
                    case DATA_CR -> {
                        expect(value, '\r');
                        state = State.DATA_LF;
                    }
                    case DATA_LF -> {
                        expect(value, '\n');
                        arguments.add(payload);
                        payload = null;
                        if (arguments.size() == argumentCount) {
                            List<byte[]> request = List.copyOf(arguments);
                            arguments = null;
                            state = State.ARRAY_PREFIX;
                            return request;
                        }
                        state = State.BULK_PREFIX;
                    }
                    default -> throw new IllegalStateException("Unexpected parser state");
                }
            }
            return null;
        } catch (ProtocolException exception) {
            failed = true;
            arguments = null;
            payload = null;
            throw exception;
        }
    }

    public boolean hasPartialRequest() {
        return state != State.ARRAY_PREFIX;
    }

    private static void expect(byte actual, char expected) throws ProtocolException {
        if (actual != expected) {
            throw new ProtocolException("unexpected byte in RESP frame");
        }
    }
}
