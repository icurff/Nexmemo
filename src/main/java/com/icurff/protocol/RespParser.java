package com.icurff.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class RespParser {
    private enum State {
        ARRAY_LENGTH, BULK_LENGTH, BULK_DATA
    }

    private State state = State.ARRAY_LENGTH;
    private List<byte[]> arguments;
    private int argumentCount;
    private int bulkLength;
    private boolean partialHeader;
    private boolean failed;

    /** Leaves incomplete headers and bulk data in input for the next read. */
    public List<byte[]> parse(ByteBuffer input) throws ProtocolException {
        if (failed) {
            throw new IllegalStateException("Parser failed; close the connection");
        }
        try {
            while (input.hasRemaining()) {
                if (state == State.BULK_DATA) {
                    int remaining = input.remaining();
                    if (remaining > bulkLength && input.get(input.position() + bulkLength) != '\r') {
                        throw new ProtocolException("unexpected byte in RESP frame");
                    }
                    if ((long) remaining > (long) bulkLength + 1
                            && input.get(input.position() + bulkLength + 1) != '\n') {
                        throw new ProtocolException("unexpected byte in RESP frame");
                    }
                    if ((long) remaining < (long) bulkLength + 2) {
                        return null;
                    }
                    byte[] argument = new byte[bulkLength];
                    input.get(argument);
                    input.position(input.position() + 2);
                    arguments.add(argument);
                    if (arguments.size() == argumentCount) {
                        List<byte[]> request = List.copyOf(arguments);
                        arguments = null;
                        state = State.ARRAY_LENGTH;
                        return request;
                    }
                    state = State.BULK_LENGTH;
                } else {
                    int length = parseLength(input);
                    if (length < 0) {
                        partialHeader = true;
                        return null;
                    }
                    partialHeader = false;
                    if (state == State.ARRAY_LENGTH) {
                        argumentCount = length;
                        arguments = new ArrayList<>();
                        state = State.BULK_LENGTH;
                    } else {
                        bulkLength = length;
                        state = State.BULK_DATA;
                    }
                }
            }
            return null;
        } catch (ProtocolException exception) {
            failed = true;
            arguments = null;
            throw exception;
        }
    }

    public boolean hasPartialRequest() {
        return state != State.ARRAY_LENGTH || partialHeader;
    }

    /** Returns -1 without consuming input when the header is incomplete. */
    private int parseLength(ByteBuffer input) throws ProtocolException {
        int start = input.position();
        char prefix = state == State.ARRAY_LENGTH ? '*' : '$';
        if (input.get(start) != prefix) {
            throw new ProtocolException("unexpected byte in RESP frame");
        }
        int length = 0;
        int digits = 0;
        for (int position = start + 1; position < input.limit(); position++) {
            byte value = input.get(position);
            if (value == '\r') {
                if (digits == 0) {
                    throw new ProtocolException("missing length");
                }
                if (state == State.ARRAY_LENGTH && length == 0) {
                    throw new ProtocolException("empty command array");
                }
                if (position + 1 == input.limit()) {
                    return -1;
                }
                if (input.get(position + 1) != '\n') {
                    throw new ProtocolException("unexpected byte in RESP frame");
                }
                input.position(position + 2);
                return length;
            }
            if (value < '0' || value > '9') {
                throw new ProtocolException("expected unsigned decimal length");
            }
            long nextLength = (long) length * 10 + (value - '0');
            if (++digits > 10 || nextLength > Integer.MAX_VALUE) {
                throw new ProtocolException("length exceeds Java array capacity");
            }
            length = (int) nextLength;
        }
        return -1;
    }
}
