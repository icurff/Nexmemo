package com.icurff.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public class MutableClock extends Clock {
    private final AtomicLong now = new AtomicLong(1_000_000);

    public void advance(long millis) {
        now.addAndGet(millis);
    }

    @Override public long millis() { return now.get(); }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) {
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId other) { return MutableClock.this.withZone(other); }
            @Override public Instant instant() { return MutableClock.this.instant(); }
        };
    }
}
