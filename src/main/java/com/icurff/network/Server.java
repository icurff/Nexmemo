package com.icurff.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import com.icurff.command.CommandHandler;
import com.icurff.protocol.ProtocolException;
import com.icurff.protocol.RespEncoder;
import com.icurff.storage.KvStore;

public final class Server {
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final KvStore store;
    private final CommandHandler handler;
    private volatile boolean running = true;

    public Server(InetSocketAddress address) throws IOException {
        this(address, new KvStore());
    }

    Server(InetSocketAddress address, KvStore store) throws IOException {
        this.store = store;
        handler = new CommandHandler(store);
        selector = Selector.open();
        ServerSocketChannel channel = null;
        try {
            channel = ServerSocketChannel.open();
            channel.bind(address);
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException exception) {
            closeChannel(channel); // close io channel
            selector.close();
            throw exception;
        }
        listener = channel;
    }

    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) listener.getLocalAddress();
    }
    // event loop
    public void run() throws IOException {
        try {
            while (running) {
                selector.select(100);
                store.purgeExpired(100);
                var keys = selector.selectedKeys().iterator();
                while (running && keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        acceptConnection();
                    } else {
                        try {
                            if (key.isReadable()) {
                                readFromConnection(key);
                            }
                            if (key.isValid() && key.isWritable()) {
                                writeToConnection(key);
                            }
                        } catch (IOException exception) {
                            System.err.println("Client I/O error: " + exception.getMessage());
                            closeChannel(key.channel());
                        }
                    }
                }
            }
        } finally {
            for (SelectionKey key : selector.keys()) {
                    closeChannel(key.channel());
            }
            closeChannel(listener);
            selector.close();
        }
    }

    public void stop() {
        running = false;
        selector.wakeup();
    }

    private void acceptConnection() throws IOException {
        SocketChannel channel = listener.accept();
        if (channel == null) {
            return;
        }
        try {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ, new Connection());
        } catch (IOException exception) {
            closeChannel(channel);
        }
    }

    private void readFromConnection(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();
        // move remaining bytes to front of buffer
        connection.inputBuffer.compact();
        int count = channel.read(connection.inputBuffer);
        if (count > 0 && !connection.inputBuffer.hasRemaining()) {
            connection.growInputBuffer();
        }
        // convert to read mode
        connection.inputBuffer.flip();
        if (count == -1) {
            connection.eof = true;
        }
        processInput(key, connection);
    }

    private void writeToConnection(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection connection = (Connection) key.attachment();
        while (!connection.output.isEmpty()) {
            ByteBuffer response = connection.output.peek();
            channel.write(response);
            if (response.hasRemaining()) {
                break;
            }
            connection.output.poll();
        }
        flushResponses(key, connection);
    }

    private void processInput(SelectionKey key, Connection connection) {
        try {
            while (connection.inputBuffer.hasRemaining()) {
                var request = connection.parser.parse(connection.inputBuffer);
                // not a complete request, wait for more bytes
                if (request == null) {
                    break;
                }
                ResponsePipeline.ResponseSlot responseSlot = connection.pipeline.reserve();
                responseSlot.complete(handler.execute(request));
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
        flushResponses(key, connection);
    }

    private void flushResponses(SelectionKey key, Connection connection) {
        connection.pipeline.flushTo(connection.output);
        if (connection.closeAfterWrite && connection.pipeline.isEmpty() && connection.output.isEmpty()) {
            closeChannel(key.channel());
            return;
        }
        if (!connection.closeAfterWrite && !connection.eof) {
            key.interestOps(SelectionKey.OP_READ);
        } else {
            key.interestOps(0);
        }
        if (!connection.output.isEmpty()) {
            key.interestOpsOr(SelectionKey.OP_WRITE);
        }
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
