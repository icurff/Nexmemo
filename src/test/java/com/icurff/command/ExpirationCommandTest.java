package com.icurff.command;

import com.icurff.storage.KvStore;
import com.icurff.support.MutableClock;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExpirationCommandTest {
    private final MutableClock clock = new MutableClock();
    private final CommandHandler handler = new CommandHandler(new KvStore(clock));

    private String run(String... words) {
        List<byte[]> arguments = new ArrayList<>();
        for (String word : words) arguments.add(word.getBytes(StandardCharsets.UTF_8));
        return StandardCharsets.UTF_8.decode(handler.execute(arguments)).toString();
    }

    @Test
    void supportsExpirationUnitsRoundingAndPersistence() {
        assertEquals(":-2\r\n", run("TTL", "key"));
        assertEquals("+OK\r\n", run("SET", "key", "v"));
        assertEquals(":-1\r\n", run("PTTL", "key"));
        assertEquals(":1\r\n", run("eXpIrE", "key", "2"));
        assertEquals(":2000\r\n", run("pTtL", "key"));
        clock.advance(500);
        assertEquals(":2\r\n", run("TTL", "key"));
        clock.advance(1);
        assertEquals(":1\r\n", run("TTL", "key"));
        assertEquals(":1\r\n", run("PEXPIRE", "key", "499"));
        assertEquals(":0\r\n", run("TTL", "key"));
        assertEquals(":1\r\n", run("pErSiSt", "key"));
        assertEquals(":0\r\n", run("PERSIST", "key"));
        assertEquals(":-1\r\n", run("TTL", "key"));
        assertEquals("+OK\r\n", run("SET", "key", "v", "eX", "3"));
        assertEquals(":3000\r\n", run("PTTL", "key"));
        assertEquals("+OK\r\n", run("SET", "key", "v", "pX", "12"));
        clock.advance(12);
        assertEquals(":-2\r\n", run("PTTL", "key"));
        assertEquals("$-1\r\n", run("GET", "key"));
        assertEquals(":0\r\n", run("EXPIRE", "key", "2"));
        assertEquals(":0\r\n", run("PERSIST", "key"));
    }

    @Test
    void rejectsInvalidDurationsWithoutChangingValueOrTtl() {
        run("SET", "key", "v", "PX", "100");
        String[] invalid = {"", "-", "+1", " 1", "1 ", "1.5", "x", "1\0", "１２",
                "9223372036854775808", "-9223372036854775809"};
        for (String duration : invalid) {
            assertEquals("-ERR value is not an integer or out of range\r\n",
                    run("SET", "key", "changed", "PX", duration));
            assertEquals("-ERR value is not an integer or out of range\r\n",
                    run("PEXPIRE", "key", duration));
        }
        for (String duration : new String[]{"0", "-1", "-9223372036854775808"}) {
            assertEquals("-ERR invalid expire time in 'set' command\r\n",
                    run("SET", "key", "changed", "EX", duration));
        }
        String[][] overflow = {
                {"SET", "key", "changed", "EX", "9223372036854776"},
                {"SET", "key", "changed", "PX", "9223372036854775807"},
                {"EXPIRE", "key", "-9223372036854776"},
                {"EXPIRE", "key", "9223372036854776"},
                {"PEXPIRE", "key", "9223372036854775807"}
        };
        for (String[] command : overflow) {
            assertEquals("-ERR value is not an integer or out of range\r\n", run(command));
        }
        assertEquals("$1\r\nv\r\n", run("GET", "key"));
        assertEquals(":100\r\n", run("PTTL", "key"));
    }

    @Test
    void validatesOptionsAndArityBeforeMutation() {
        run("SET", "key", "v", "EX", "10");
        String[][] invalid = {
                {"SET", "key", "new", "EX"}, {"SET", "key", "new", "NX", "1"},
                {"SET", "key", "new", "EX", "1", "PX", "1"}, {"SET", "key", "new", "KEEPTTL"}
        };
        for (String[] command : invalid) assertEquals("-ERR syntax error\r\n", run(command));
        for (String command : new String[]{"EXPIRE", "PEXPIRE", "TTL", "PTTL", "PERSIST"}) {
            assertEquals("-ERR wrong number of arguments for '" + command.toLowerCase(java.util.Locale.ROOT)
                    + "' command\r\n", run(command));
            assertTrue(run(command, "key", "1", "EXTRA").startsWith("-ERR wrong number"));
        }
        assertEquals("$1\r\nv\r\n", run("GET", "key"));
        assertEquals(":10000\r\n", run("PTTL", "key"));
    }

    @Test
    void deletesImmediatelyAndHandlesLargestValidDeadline() {
        run("SET", "key", "v");
        assertEquals(":1\r\n", run("EXPIRE", "key", "0"));
        assertEquals(":0\r\n", run("EXISTS", "key"));
        run("SET", "key", "v");
        assertEquals(":1\r\n", run("PEXPIRE", "key", "-9223372036854775808"));
        long duration = Long.MAX_VALUE - clock.millis();
        assertEquals("+OK\r\n", run("SET", "key", "v", "PX", Long.toString(duration)));
        assertEquals(":" + duration + "\r\n", run("PTTL", "key"));
        long rounded = duration / 1000 + (duration % 1000 >= 500 ? 1 : 0);
        assertEquals(":" + rounded + "\r\n", run("TTL", "key"));
        run("SET", "key", "new");
        assertEquals(":-1\r\n", run("TTL", "key"));
    }
}
