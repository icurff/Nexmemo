package com.icurff.storage;

import java.util.Arrays;
import java.util.HashMap;

/** In-memory storage owned by one server event loop. */
public final class KvStore {
    private final HashMap<ByteArrayKey, byte[]> entries = new HashMap<>();

    public void set(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        ByteArrayKey storedKey = new ByteArrayKey(key);
        byte[] storedValue = Arrays.copyOf(value, value.length);
        entries.put(storedKey, storedValue);
    }

    public byte[] get(byte[] key) {
        byte[] value = entries.get(new ByteArrayKey(key));
        if (value == null) {
            return null;
        }
        return Arrays.copyOf(value, value.length);
    }

    public boolean delete(byte[] key) {
        return entries.remove(new ByteArrayKey(key)) != null;
    }

    public boolean exists(byte[] key) {
        return entries.containsKey(new ByteArrayKey(key));
    }
}
