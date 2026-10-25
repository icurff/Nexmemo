package com.icurff.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public final class Server {
    private static final int BUFFER_SIZE = 8 * 1024;

    private final Selector selector;
    private final ServerSocketChannel listener;
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
                                } else if (key.isWritable()) {
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
            channel.configureBlocking(false);
            // Each client owns one buffer, used alternately for reading and writing.
            channel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(BUFFER_SIZE));
        } catch (IOException exception) {
            closeChannel(channel);
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        int count = channel.read(buffer);
        if (count == -1) {
            closeConnection(key);
        } else if (count > 0) {
            buffer.flip();
            // Echo the bytes already in the buffer before reading anything else.
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        channel.write(buffer);
        // A partial or zero-byte write leaves the remaining bytes for the next event.
        if (!buffer.hasRemaining()) {
            buffer.clear();
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void closeConnection(SelectionKey key) {
        key.cancel();
        closeChannel(key.channel());
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
