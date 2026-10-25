package com.icurff.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ServerTest {
    private static Socket connect(Server server) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(4000);
        socket.connect(server.localAddress(), 3000);
        return socket;
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static void echo(Socket client, byte[] bytes) throws IOException {
        client.getOutputStream().write(bytes);
        assertArrayEquals(bytes, client.getInputStream().readNBytes(bytes.length));
    }

    @Test
    void echoesFragmentedAndCombinedBinaryData() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            byte[] bytes = {0, 13, 10, (byte) 255, 42, 0, (byte) 128};
            for (byte value : bytes) {
                client.getOutputStream().write(value);
                assertEquals(Byte.toUnsignedInt(value), client.getInputStream().read());
            }
            echo(client, bytes);
            echo(client, new byte[]{1, 2, 3});
        }
    }

    @Test
    void echoesLargeStreamBeforeClosingAfterEof() throws Exception {
        try (RunningServer running = new RunningServer();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket client = connect(running.server)) {
            byte[] bytes = payload(512 * 1024);
            Future<?> writer = executor.submit(() -> {
                client.getOutputStream().write(bytes);
                client.shutdownOutput();
                return null;
            });
            assertArrayEquals(bytes, client.getInputStream().readNBytes(bytes.length));
            assertEquals(-1, client.getInputStream().read());
            writer.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void servesConcurrentClientsWhileAnotherClientIsIdle() throws Exception {
        try (RunningServer running = new RunningServer();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket idle = connect(running.server)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) {
                jobs.add(executor.submit(() -> {
                    try (Socket client = connect(running.server)) {
                        for (int round = 0; round < 5; round++) {
                            echo(client, payload(4096));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                job.get(5, TimeUnit.SECONDS);
            }
            echo(idle, new byte[]{1, 2, 3});
        }
    }

    @Test
    void resumesSlowClientAndKeepsServingAnotherClient() throws Exception {
        try (RunningServer running = new RunningServer();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket slow = connect(running.server);
             Socket normal = connect(running.server)) {
            byte[] bytes = payload(4 * 1024 * 1024);
            Future<?> writer = executor.submit(() -> {
                slow.getOutputStream().write(bytes);
                slow.shutdownOutput();
                return null;
            });
            // Receive the first byte, then leave the rest unread while using another client.
            assertEquals(Byte.toUnsignedInt(bytes[0]), slow.getInputStream().read());
            for (int round = 0; round < 10; round++) {
                echo(normal, payload(256));
            }
            byte[] echoed = new byte[bytes.length];
            echoed[0] = bytes[0];
            assertEquals(bytes.length - 1,
                    slow.getInputStream().readNBytes(echoed, 1, bytes.length - 1));
            assertArrayEquals(bytes, echoed);
            assertEquals(-1, slow.getInputStream().read());
            writer.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void clientResetDoesNotStopServer() throws Exception {
        try (RunningServer running = new RunningServer()) {
            for (int round = 0; round < 10; round++) {
                try (Socket reset = connect(running.server)) {
                    reset.setSoLinger(true, 0);
                    reset.getOutputStream().write(payload(1024));
                }
                try (Socket healthy = connect(running.server)) {
                    echo(healthy, new byte[]{1, 0, (byte) 255});
                }
            }
        }
    }

    @Test
    void reportsBindFailureAndAllowsPortToBeReused() throws Exception {
        InetSocketAddress address;
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            address = (InetSocketAddress) occupied.getLocalSocketAddress();
            assertThrows(IOException.class, () -> new Server(address));
        }
        try (RunningServer running = new RunningServer(address);
             Socket client = connect(running.server)) {
            echo(client, new byte[]{7});
        }
    }

    @Test
    void stopWakesSelectorClosesClientsAndReleasesPort() throws Exception {
        InetSocketAddress address;
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            echo(client, new byte[]{1});
            address = running.server.localAddress();
            running.server.stop();
            running.server.stop();
            running.task.get(3, TimeUnit.SECONDS);
            assertEquals(-1, client.getInputStream().read());
        }
        try (RunningServer replacement = new RunningServer(address);
             Socket client = connect(replacement.server)) {
            echo(client, new byte[]{2});
        }
        // Also stop with no connected clients, when select() has no socket activity.
        try (RunningServer idle = new RunningServer()) {
            idle.server.stop();
            idle.task.get(3, TimeUnit.SECONDS);
        }
    }

    // Only tests need a background thread. The application runs the loop on main.
    private static class RunningServer implements AutoCloseable {
        final Server server;
        final FutureTask<Void> task;
        final Thread thread;

        RunningServer() throws IOException {
            this(new InetSocketAddress("127.0.0.1", 0));
        }

        RunningServer(InetSocketAddress address) throws IOException {
            server = new Server(address);
            task = new FutureTask<>(() -> {
                server.run();
                return null;
            });
            thread = new Thread(task, "nexmemo-test");
            thread.start();
        }

        @Override
        public void close() throws Exception {
            server.stop();
            thread.join(3000);
            assertFalse(thread.isAlive(), "Server did not stop");
            task.get(1, TimeUnit.SECONDS);
        }
    }
}
