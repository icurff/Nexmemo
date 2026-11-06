package com.icurff.storage;

import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

/** In-memory storage owned by one server event loop. */
public final class KvStore {
    private final HashMap<ByteArrayKey, Entry> entries = new HashMap<>();
    private final PriorityQueue<Entry> expirations =
            new PriorityQueue<>(Comparator.comparingLong(entry -> entry.expiresAtMillis));
    private final Clock clock;

    public KvStore() {
        this(Clock.systemUTC());
    }

    public KvStore(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock must not be null");
        }
        this.clock = clock;
    }

    public void set(byte[] key, byte[] value) {
        put(key, value, 0);
    }

    public void set(byte[] key, byte[] value, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ERR invalid expire time in 'set' command");
        }
        put(key, value, deadline(clock.millis(), ttlMillis));
    }

    private void put(byte[] key, byte[] value, long expiresAtMillis) {
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        ByteArrayKey storedKey = new ByteArrayKey(key);
        Entry entry = new Entry(storedKey, Arrays.copyOf(value, value.length), expiresAtMillis);
        Entry old = entries.put(storedKey, entry);
        if (old != null && old.expiresAtMillis != 0) {
            expirations.remove(old);
        }
        if (expiresAtMillis != 0) {
            expirations.add(entry);
        }
    }

    public byte[] get(byte[] key) {
        Entry entry = findLive(key, clock.millis());
        if (entry == null) {
            return null;
        }
        return Arrays.copyOf(entry.value, entry.value.length);
    }

    public boolean delete(byte[] key) {
        Entry entry = findLive(key, clock.millis());
        if (entry == null) {
            return false;
        }
        remove(entry);
        return true;
    }

    public boolean exists(byte[] key) {
        return findLive(key, clock.millis()) != null;
    }

    public boolean expire(byte[] key, long ttlMillis) {
        long now = clock.millis();
        long expiresAt = 0;
        if (ttlMillis > 0) {
            expiresAt = deadline(now, ttlMillis);
        }
        Entry entry = findLive(key, now);
        if (entry == null) {
            return false;
        }
        if (ttlMillis <= 0) {
            remove(entry);
            return true;
        }
        if (entry.expiresAtMillis != 0) {
            expirations.remove(entry);
        }
        entry.expiresAtMillis = expiresAt;
        expirations.add(entry);
        return true;
    }

    public long pttl(byte[] key) {
        long now = clock.millis();
        Entry entry = findLive(key, now);
        if (entry == null) {
            return -2;
        }
        if (entry.expiresAtMillis == 0) {
            return -1;
        }
        return entry.expiresAtMillis - now;
    }

    public boolean persist(byte[] key) {
        Entry entry = findLive(key, clock.millis());
        if (entry == null || entry.expiresAtMillis == 0) {
            return false;
        }
        expirations.remove(entry);
        entry.expiresAtMillis = 0;
        return true;
    }

    public int purgeExpired(int maxEntries) {
        long now = clock.millis();
        int removed = 0;
        while (removed < maxEntries && !expirations.isEmpty()) {
            Entry entry = expirations.peek();
            if (entry.expiresAtMillis > now) {
                break;
            }
            expirations.poll();
            entries.remove(entry.key);
            removed++;
        }
        return removed;
    }

    private Entry findLive(byte[] key, long now) {
        Entry entry = entries.get(new ByteArrayKey(key));
        if (entry != null && entry.expiresAtMillis != 0 && now >= entry.expiresAtMillis) {
            remove(entry);
            return null;
        }
        return entry;
    }

    private void remove(Entry entry) {
        entries.remove(entry.key);
        if (entry.expiresAtMillis != 0) {
            expirations.remove(entry);
        }
    }

    private static long deadline(long now, long ttlMillis) {
        if (now > Long.MAX_VALUE - ttlMillis) {
            throw new IllegalArgumentException("ERR value is not an integer or out of range");
        }
        return now + ttlMillis;
    }

    private static final class Entry {
        final ByteArrayKey key;
        final byte[] value;
        long expiresAtMillis;

        Entry(ByteArrayKey key, byte[] value, long expiresAtMillis) {
            this.key = key;
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
