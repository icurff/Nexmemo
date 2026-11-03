package com.icurff.storage;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class KvStoreTest {
    @Test
    void storesOverwritesAndDeletesByContent() {
        KvStore store = new KvStore();
        assertNull(store.get(new byte[]{1}));
        assertFalse(store.exists(new byte[]{1}));
        assertFalse(store.delete(new byte[]{1}));
        store.set(new byte[]{1}, new byte[]{2});
        assertTrue(store.exists(new byte[]{1}));
        assertArrayEquals(new byte[]{2}, store.get(new byte[]{1}));
        store.set(new byte[]{1}, new byte[]{3});
        assertArrayEquals(new byte[]{3}, store.get(new byte[]{1}));
        assertTrue(store.delete(new byte[]{1}));
        assertFalse(store.delete(new byte[]{1}));
        assertNull(store.get(new byte[]{1}));
    }

    @Test
    void distinguishesKeysWithTheSameHash() {
        byte[] first = {0, 31};
        byte[] second = {1, 0};
        assertEquals(Arrays.hashCode(first), Arrays.hashCode(second));
        KvStore store = new KvStore();
        store.set(first, new byte[]{10});
        store.set(second, new byte[]{20});
        assertArrayEquals(new byte[]{10}, store.get(first));
        assertArrayEquals(new byte[]{20}, store.get(second));
        store.delete(first);
        assertTrue(store.exists(second));
    }

    @Test
    void ownsKeysAndValuesAndCopiesReadResults() {
        KvStore store = new KvStore();
        byte[] key = {0, (byte) 255, 13, 10};
        byte[] originalKey = key.clone();
        byte[] value = {(byte) 128, 0, 13, 10};
        byte[] originalValue = value.clone();
        store.set(key, value);
        Arrays.fill(key, (byte) 1);
        Arrays.fill(value, (byte) 2);
        assertArrayEquals(originalValue, store.get(originalKey));
        byte[] result = store.get(originalKey);
        Arrays.fill(result, (byte) 3);
        assertArrayEquals(originalValue, store.get(originalKey));
    }

    @Test
    void supportsEmptyKeysAndValues() {
        KvStore store = new KvStore();
        store.set(new byte[0], new byte[0]);
        assertTrue(store.exists(new byte[0]));
        assertArrayEquals(new byte[0], store.get(new byte[0]));
        assertTrue(store.delete(new byte[0]));
    }
}
