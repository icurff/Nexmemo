package com.icurff.network;

import com.icurff.testutil.RespRequests;
import com.icurff.protocol.RespEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        client.getOutputStream().write(echoRequest(bytes));
        byte[] expected = bulk(bytes);
        assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] echoRequest(byte[] bytes) {
        return RespRequests.request(List.of(ascii("ECHO"), bytes)).array();
    }

    private static byte[] bulk(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ascii("$" + bytes.length + "\r\n"));
        output.write(bytes);
        output.write(ascii("\r\n"));
        return output.toByteArray();
    }

    @Test
    void echoesFragmentedRespWithBinaryData() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            byte[] bytes = {0, 13, 10, (byte) 255, 42, 0, (byte) 128};
            client.setTcpNoDelay(true);
            for (byte value : echoRequest(bytes)) {
                client.getOutputStream().write(value);
            }
            byte[] expected = bulk(bytes);
            assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length));
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
                client.getOutputStream().write(echoRequest(bytes));
                client.shutdownOutput();
                return null;
            });
            byte[] expected = bulk(bytes);
            assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length));
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
            ByteArrayOutputStream requests = new ByteArrayOutputStream();
            ByteArrayOutputStream responses = new ByteArrayOutputStream();
            for (int i = 0; i < 4; i++) {
                byte[] bytes = payload(1024 * 1024);
                requests.write(echoRequest(bytes));
                responses.write(bulk(bytes));
            }
            byte[] expected = responses.toByteArray();
            Future<?> writer = executor.submit(() -> {
                slow.getOutputStream().write(requests.toByteArray());
                slow.shutdownOutput();
                return null;
            });
            // Receive the first byte, then leave the rest unread while using another client.
            assertEquals(Byte.toUnsignedInt(expected[0]), slow.getInputStream().read());
            for (int round = 0; round < 10; round++) {
                echo(normal, payload(256));
            }
            byte[] echoed = new byte[expected.length];
            echoed[0] = expected[0];
            assertEquals(expected.length - 1,
                    slow.getInputStream().readNBytes(echoed, 1, expected.length - 1));
            assertArrayEquals(expected, echoed);
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
                    reset.getOutputStream().write(echoRequest(payload(1024)));
                }
                try (Socket healthy = connect(running.server)) {
                    echo(healthy, new byte[]{1, 0, (byte) 255});
                }
            }
        }
    }

    @Test
    void processesBufferedPipelineInOrderWithoutMoreNetworkInput() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            ByteArrayOutputStream requests = new ByteArrayOutputStream();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            for (int i = 0; i < 500; i++) {
                byte[] value = ascii("message-" + i);
                requests.write(echoRequest(value));
                expected.write(bulk(value));
            }
            client.getOutputStream().write(requests.toByteArray());
            assertArrayEquals(expected.toByteArray(), client.getInputStream().readNBytes(expected.size()));
            // The pipeline is drained with the connection still open.
            echo(client, new byte[0]);
        }
    }

    @Test
    void keepsReadingRequestsWhileClientHasNotReadQueuedResponses() throws Exception {
        try (RunningServer running = new RunningServer();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket client = new Socket()) {
            client.setSoTimeout(4000);
            client.setSendBufferSize(8192);
            client.setReceiveBufferSize(256 * 1024);
            client.setTcpNoDelay(true);
            client.connect(running.server.localAddress(), 3000);
            byte[] bytes = payload(256 * 1024);
            byte[] request = echoRequest(bytes);
            byte[] expected = bulk(bytes);
            int requestCount = 64;
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < requestCount; i++) {
                    client.getOutputStream().write(request);
                }
                return null;
            });
            // Send 16 MiB before reading any response. Writes must not pause server reads.
            writer.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < requestCount; i++) {
                assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length));
            }
            echo(client, ascii("pipeline drained"));
        }
    }

    @Test
    void handlesPingCaseArityAndUnknownCommandWithoutClosingConnection() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            ByteArrayOutputStream requests = new ByteArrayOutputStream();
            requests.write(RespRequests.request(List.of(ascii("pInG"))).array());
            requests.write(RespRequests.request(List.of(ascii("PING"), new byte[0])).array());
            requests.write(RespRequests.request(List.of(ascii("PING"), ascii("a"), ascii("b"))).array());
            requests.write(RespRequests.request(List.of(ascii("ECHO"))).array());
            requests.write(RespRequests.request(List.of(ascii("UNKNOWN"), ascii("key"))).array());
            requests.write(RespRequests.request(List.of(new byte[]{0, 13, 10, (byte) 255})).array());
            String expected = "+PONG\r\n$0\r\n\r\n"
                    + "-ERR wrong number of arguments for 'ping' command\r\n"
                    + "-ERR wrong number of arguments for 'echo' command\r\n"
                    + "-ERR unknown command\r\n-ERR unknown command\r\n";
            client.getOutputStream().write(requests.toByteArray());
            assertArrayEquals(ascii(expected), client.getInputStream().readNBytes(ascii(expected).length));
            echo(client, "xin chào".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void sendsEarlierRepliesThenProtocolErrorAndClosesOnlyOffendingClient() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            client.getOutputStream().write(ascii("*1\r\n$4\r\nPING\r\n*1\r\n$1\r\nX!*1\r\n$4\r\nPING\r\n"));
            String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("+PONG\r\n-ERR Protocol error: "), response);
            assertTrue(response.endsWith("\r\n"));
            assertFalse(response.substring("+PONG\r\n".length()).contains("+PONG"), response);
            try (Socket healthy = connect(running.server)) {
                echo(healthy, ascii("still alive"));
            }
        }
    }

    @Test
    void eofAfterPipelineDrainsRepliesAndReportsTrailingIncompleteRequest() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            client.getOutputStream().write(ascii("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPI"));
            client.shutdownOutput();
            assertEquals("+PONG\r\n+PONG\r\n-ERR Protocol error: unexpected EOF in request\r\n",
                    new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void eofOnIdleConnectionClosesCleanly() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            client.shutdownOutput();
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    void echoesBeyondFormerRequestLimitAndReusesConnection() throws Exception {
        try (RunningServer running = new RunningServer();
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             Socket client = connect(running.server)) {
            byte[] bytes = payload(3 * 1024 * 1024);
            Future<?> writer = executor.submit(() -> {
                client.getOutputStream().write(echoRequest(bytes));
                return null;
            });
            byte[] expected = bulk(bytes);
            assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length));
            writer.get(5, TimeUnit.SECONDS);
            echo(client, ascii("after buffer growth"));
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

    @Test
    void sharesStoredBinaryDataBetweenClientsAndSurvivesDisconnect() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket reader = connect(running.server)) {
            byte[] key = {0, 13, 10, (byte) 255};
            byte[] value = payload(32 * 1024);
            try (Socket writer = connect(running.server)) {
                byte[] request = RespRequests.request(List.of(ascii("SET"), key, value)).array();
                writer.setTcpNoDelay(true);
                for (int i = 0; i < request.length; i += 37) {
                    writer.getOutputStream().write(request, i, Math.min(37, request.length - i));
                }
                assertArrayEquals(ascii("+OK\r\n"), writer.getInputStream().readNBytes(5));
            }
            reader.getOutputStream().write(RespRequests.request(List.of(ascii("GET"), key)).array());
            byte[] expected = bulk(value);
            assertArrayEquals(expected, reader.getInputStream().readNBytes(expected.length));
        }
    }

    @Test
    void runsStoragePipelineInOrderAndKeepsConnectionAfterArityError() throws Exception {
        try (RunningServer running = new RunningServer();
             Socket client = connect(running.server)) {
            String[][] commands = {
                    {"SET", "a", "An"}, {"GET", "a"},
                    {"EXISTS", "a", "a", "missing"}, {"SET", "a", "wrong", "NX"},
                    {"GET", "a"}, {"DEL", "a", "a", "missing"}, {"GET", "a"}
            };
            ByteArrayOutputStream requests = new ByteArrayOutputStream();
            for (String[] command : commands) {
                List<byte[]> arguments = new ArrayList<>();
                for (String argument : command) {
                    arguments.add(ascii(argument));
                }
                requests.write(RespRequests.request(arguments).array());
            }
            client.getOutputStream().write(requests.toByteArray());
            byte[] expected = ascii("+OK\r\n$2\r\nAn\r\n:2\r\n"
                    + "-ERR wrong number of arguments for 'set' command\r\n"
                    + "$2\r\nAn\r\n:1\r\n$-1\r\n");
            assertArrayEquals(expected, client.getInputStream().readNBytes(expected.length));
            echo(client, ascii("still connected"));
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
