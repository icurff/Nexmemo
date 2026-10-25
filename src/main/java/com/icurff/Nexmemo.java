package com.icurff;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.icurff.network.Server;

public class Nexmemo {
    public static void main(String[] args) throws IOException {
        Server server = new Server(new InetSocketAddress("localhost", 6379));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Server listening on " + server.localAddress());
        server.run();
    }
}
