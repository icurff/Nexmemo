package com.icurff.storage;

import com.icurff.support.MutableClock;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExpirationTest {
    private final MutableClock clock = new MutableClock();
    private final KvStore store = new KvStore(clock);
    private final byte[] key = {0, (byte) 255};
    private final byte[] value = {13, 10, 0};

    private int queueSize() throws Exception {
        Field field = KvStore.class.getDeclaredField("expirations");
        field.setAccessible(true);
        return ((Collection<?>) field.get(store)).size();
    }

    @Test
    void expiresExactlyAtDeadlineAndChecksAllAccessPaths() throws Exception {
        assertEquals(-2, store.pttl(key));
        store.set(key, value);
        assertEquals(-1, store.pttl(key));
        assertTrue(store.expire(key, 1000));
        clock.advance(999);
        assertEquals(1, store.pttl(key));
        assertArrayEquals(value, store.get(key));
        clock.advance(1);
        assertNull(store.get(key));
        assertEquals(-2, store.pttl(key));
        assertEquals(0, queueSize());
        for (int operation = 0; operation < 5; operation++) {
            store.set(key, value, 10);
            clock.advance(11);
            switch (operation) {
                case 0 -> assertFalse(store.exists(key));
                case 1 -> assertFalse(store.delete(key));
                case 2 -> assertFalse(store.expire(key, 100));
                case 3 -> assertFalse(store.persist(key));
                case 4 -> assertEquals(-2, store.pttl(key));
            }
            assertEquals(0, queueSize());
        }
    }

    @Test
    void updatesDeadlinesWithoutLeavingOldNodes() throws Exception {
        store.set(key, value, 100);
        for (int i = 0; i < 200; i++) {
            assertTrue(store.expire(key, 500 + i));
            assertEquals(1, queueSize());
        }
        clock.advance(100);
        assertEquals(0, store.purgeExpired(100));
        assertTrue(store.exists(key));
        assertTrue(store.expire(key, 20));
        clock.advance(20);
        assertEquals(1, store.purgeExpired(100));
        assertFalse(store.exists(key));
    }

    @Test
    void persistsOverwritesAndRecreatesWithoutStaleExpiration() throws Exception {
        store.set(key, value, 10);
        assertTrue(store.persist(key));
        assertFalse(store.persist(key));
        clock.advance(10);
        assertEquals(0, store.purgeExpired(100));
        assertEquals(-1, store.pttl(key));
        store.expire(key, 10);
        store.set(key, new byte[]{42});
        clock.advance(10);
        assertEquals(0, store.purgeExpired(100));
        assertArrayEquals(new byte[]{42}, store.get(key));
        store.set(key, value, 10);
        assertTrue(store.delete(key));
        store.set(key, new byte[]{7}, 100);
        clock.advance(10);
        assertEquals(0, store.purgeExpired(100));
        assertArrayEquals(new byte[]{7}, store.get(key));
        assertEquals(1, queueSize());
    }

    @Test
    void purgesInDeadlineOrderWithWorkLimit() throws Exception {
        store.set(new byte[]{1}, value, 30);
        store.set(new byte[]{2}, value, 10);
        store.set(new byte[]{3}, value, 20);
        store.set(new byte[]{4}, value);
        clock.advance(20);
        assertEquals(0, store.purgeExpired(0));
        assertEquals(1, store.purgeExpired(1));
        assertEquals(2, queueSize());
        assertEquals(1, store.purgeExpired(100));
        assertEquals(1, queueSize());
        clock.advance(10);
        assertEquals(1, store.purgeExpired(100));
        Field field = KvStore.class.getDeclaredField("entries");
        field.setAccessible(true);
        assertEquals(1, ((Map<?, ?>) field.get(store)).size());
        assertTrue(store.exists(new byte[]{4}));
    }

    @Test
    void rejectsOverflowBeforeMutationAndDeletesNonPositiveExpirations() {
        store.set(key, value, 100);
        assertThrows(IllegalArgumentException.class, () -> store.set(key, new byte[]{9}, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> store.expire(key, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> store.set(key, value, 0));
        assertArrayEquals(value, store.get(key));
        assertEquals(100, store.pttl(key));
        assertTrue(store.expire(key, 0));
        assertFalse(store.exists(key));
        store.set(key, value);
        assertTrue(store.expire(key, Long.MIN_VALUE));
        assertFalse(store.expire(key, 100));
        assertFalse(store.persist(key));
    }
}
