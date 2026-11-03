package com.icurff.protocol;

/** Invalid or unrepresentable RESP framing; close after replying. */
public final class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
