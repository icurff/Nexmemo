package com.icurff.storage;

import java.util.Arrays;

/** An immutable key whose equality is based on byte content. */
final class ByteArrayKey {
    private final byte[] bytes;
    private final int hash;

    ByteArrayKey(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key must not be null");
        }
        bytes = Arrays.copyOf(key, key.length);
        hash = Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ByteArrayKey)) {
            return false;
        }
        ByteArrayKey otherKey = (ByteArrayKey) other;
        return Arrays.equals(bytes, otherKey.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
