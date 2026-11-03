package com.icurff.network;

import com.icurff.command.CommandHandler;
import com.icurff.protocol.ProtocolException;
import com.icurff.protocol.RespEncoder;
import com.icurff.protocol.RespParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

public final class Server {
    private static final int INITIAL_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_CONNECTIONS = 1024;

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final CommandHandler handler = new CommandHandler();
    private int connections;
    private volatile boolean running = true;

    public Server(InetSocketAddress address) throws IOException {
        selector = Selector.open();
        ServerSocketChannel channel = null;
        try {
            channel = ServerSocketChannel.open();
            channel.bind(address);
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException exception) {
            closeChannel(channel);
            selector.close();
            throw exception;
        }
        listener = channel;
    }

    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) listener.getLocalAddress();
    }

    public void run() throws IOException {
        try (selector; listener) {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    selector.select();
                    var keys = selector.selectedKeys().iterator();
                    while (running && keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            accept();
                        } else {
                            try {
                                if (key.isReadable()) {
                                    read(key);
                                }
                                if (key.isValid() && key.isWritable()) {
                                    write(key);
                                }
                            } catch (IOException exception) {
                                System.err.println("Client I/O error: " + exception.getMessage());
                                closeConnection(key);
                            }
                        }
                    }
                }
            } finally {
                for (SelectionKey key : selector.keys()) {
                    closeConnection(key);
                }
            }
        }
    }

    public void stop() {
        running = false;
        selector.wakeup();
    }

    private void accept() throws IOException {
        SocketChannel channel = listener.accept();
        if (channel == null) {
            return;
        }
        try {
            if (connections >= MAX_CONNECTIONS) {
                closeChannel(channel);
                return;
            }
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ, new Connection());
            connections++;
        } catch (IOException exception) {
            closeChannel(channel);
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();
        // input stays in read mode between events. compact preserves unread bytes.
        connection.input.compact();
        int count = channel.read(connection.input);
        // A full read expands capacity for subsequent data, retaining all bytes.
        if (count > 0 && !connection.input.hasRemaining()) {
            connection.input = grow(connection.input);
        }
        connection.input.flip();
        if (count == -1) {
            connection.eof = true;
        }
        processInput(key, connection);
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();
        while (!connection.output.isEmpty()) {
            ByteBuffer response = connection.output.peek();
            channel.write(response);
            // Keep a partial or zero-byte write at the head for the next event.
            if (response.hasRemaining()) {
                break;
            }
            connection.output.poll();
        }
        tryFlushPipeline(key, connection);
    }

    private void processInput(SelectionKey key, Connection connection) {
        try {
            while (connection.input.hasRemaining()) {
                var request = connection.parser.parse(connection.input);
                if (request == null) {
                    break;
                }
                ResponsePipeline.Slot slot = connection.pipeline.reserve();
                slot.complete(handler.execute(request));
            }
            if (connection.eof) {
                if (connection.parser.hasPartialRequest()) {
                    throw new ProtocolException("unexpected EOF in request");
                }
                connection.closeAfterWrite = true;
            }
        } catch (ProtocolException exception) {
            connection.pipeline.reserve().complete(
                    RespEncoder.error("ERR Protocol error: " + exception.getMessage()));
            connection.closeAfterWrite = true;
        }
        tryFlushPipeline(key, connection);
    }

    private void tryFlushPipeline(SelectionKey key, Connection connection) {
        connection.pipeline.flushTo(connection.output);
        if (connection.closeAfterWrite && connection.pipeline.isEmpty() && connection.output.isEmpty()) {
            closeConnection(key);
            return;
        }
        // Reads continue independently of pending writes until EOF or a protocol error.
        int interests = connection.closeAfterWrite || connection.eof ? 0 : SelectionKey.OP_READ;
        if (!connection.output.isEmpty()) {
            interests |= SelectionKey.OP_WRITE;
        }
        key.interestOps(interests);
    }

    private void closeConnection(SelectionKey key) {
        if (key.attachment() instanceof Connection) {
            key.attach(null);
            connections--;
        }
        key.cancel();
        closeChannel(key.channel());
    }

    private static final class Connection {
        ByteBuffer input = ByteBuffer.allocate(INITIAL_BUFFER_SIZE).flip();
        final RespParser parser = new RespParser();
        final ResponsePipeline pipeline = new ResponsePipeline();
        final Queue<ByteBuffer> output = new ArrayDeque<>(128);
        boolean eof;
        boolean closeAfterWrite;
    }

    /** Grow a full write-mode input buffer while preserving any unread bytes. */
    private static ByteBuffer grow(ByteBuffer fullBuffer) throws IOException {
        if (fullBuffer.capacity() == Integer.MAX_VALUE) {
            throw new IOException("Input buffer exceeds Java buffer capacity");
        }
        int newCapacity = (int) Math.min((long) fullBuffer.capacity() * 2, Integer.MAX_VALUE);
        ByteBuffer larger = ByteBuffer.allocate(newCapacity);
        fullBuffer.flip();
        larger.put(fullBuffer);
        return larger;
    }

    private static void closeChannel(Channel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException exception) {
                System.err.println("Could not close channel: " + exception.getMessage());
            }
        }
    }
}
