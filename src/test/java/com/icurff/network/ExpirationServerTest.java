package com.icurff.network;

import com.icurff.storage.KvStore;
import com.icurff.support.MutableClock;
import com.icurff.testutil.RespRequests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ExpirationServerTest {
    private static byte[] request(String... words) {
        List<byte[]> args = new ArrayList<>();
        for (String word : words) args.add(word.getBytes(StandardCharsets.UTF_8));
        return RespRequests.request(args).array();
    }

    private static void exchange(Socket client, String expected, String... command) throws Exception {
        client.getOutputStream().write(request(command));
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(bytes, client.getInputStream().readNBytes(bytes.length));
    }

    @Test
    void clientsObserveTheSameDeadlineAndPipelineSurvivesErrors() throws Exception {
        MutableClock clock = new MutableClock();
        try (Running running = new Running(new KvStore(clock));
             Socket first = running.connect(); Socket second = running.connect()) {
            exchange(first, "+OK\r\n", "SET", "key", "value", "PX", "1000");
            exchange(second, ":1000\r\n", "PTTL", "key");
            clock.advance(1000);
            exchange(second, "$-1\r\n", "GET", "key");
            exchange(first, ":0\r\n", "EXISTS", "key");
            String[][] commands = {
                    {"SET", "key", "v", "EX", "2"}, {"TTL", "key"},
                    {"PEXPIRE", "key", "50"}, {"PTTL", "key"},
                    {"SET", "key", "bad", "PX", "0"}, {"GET", "key"},
                    {"PERSIST", "key"}, {"TTL", "key"},
                    {"EXPIRE", "key", "-1"}, {"PTTL", "key"}, {"PING"}
            };
            ByteArrayOutputStream pipeline = new ByteArrayOutputStream();
            for (String[] command : commands) pipeline.write(request(command));
            first.getOutputStream().write(pipeline.toByteArray());
            String expected = "+OK\r\n:2\r\n:1\r\n:50\r\n"
                    + "-ERR invalid expire time in 'set' command\r\n$1\r\nv\r\n"
                    + ":1\r\n:-1\r\n:1\r\n:-2\r\n+PONG\r\n";
            assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII),
                    first.getInputStream().readNBytes(expected.length()));
        }
    }

    @Test
    void idleServerPhysicallyRemovesExpiredEntriesWithoutReads() throws Exception {
        CountDownLatch ticks = new CountDownLatch(2);
        MutableClock clock = new MutableClock() {
            @Override public long millis() {
                // The second tick proves the previous purge completed.
                if (Thread.currentThread().getName().equals("ttl-test-server")) ticks.countDown();
                return super.millis();
            }
        };
        KvStore store = new KvStore(clock);
        store.set(new byte[]{1}, new byte[]{2}, 10);
        clock.advance(10);
        try (Running running = new Running(store)) {
            assertTrue(ticks.await(3, TimeUnit.SECONDS), "Idle event loop did not run expiration");
        }
        // No concurrent access: Running.close stops and joins the event loop first.
        Field entries = KvStore.class.getDeclaredField("entries");
        entries.setAccessible(true);
        assertTrue(((Map<?, ?>) entries.get(store)).isEmpty());
        Field expirations = KvStore.class.getDeclaredField("expirations");
        expirations.setAccessible(true);
        assertTrue(((Collection<?>) expirations.get(store)).isEmpty());
    }

    private static class Running implements AutoCloseable {
        private final Server server;
        private final FutureTask<Void> task;
        private final Thread thread;

        Running(KvStore store) throws Exception {
            server = new Server(new InetSocketAddress("127.0.0.1", 0), store);
            task = new FutureTask<>(() -> { server.run(); return null; });
            thread = new Thread(task, "ttl-test-server");
            thread.start();
        }

        Socket connect() throws Exception {
            Socket socket = new Socket();
            socket.setSoTimeout(3000);
            socket.connect(server.localAddress(), 3000);
            return socket;
        }

        @Override public void close() throws Exception {
            server.stop();
            thread.join(3000);
            assertFalse(thread.isAlive());
            task.get(1, TimeUnit.SECONDS);
        }
    }
}
