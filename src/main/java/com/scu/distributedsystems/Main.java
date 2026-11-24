package com.scu.distributedsystems;

import com.scu.distributedsystems.server.ChatServer;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        int socketPort = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        int rmiPort = args.length > 1 ? Integer.parseInt(args[1]) : 3001;
        if (!isPort(socketPort) || !isPort(rmiPort) || socketPort == rmiPort) {
            throw new IllegalArgumentException("Provide two distinct ports between 1 and 65535.");
        }
        Path database = Path.of(args.length > 2 ? args[2] : "chat.db");
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        var server = new ChatServer(socketPort, rmiPort, database);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "chat-shutdown"));
        server.start();
        System.out.printf("Socket.IO at http://127.0.0.1:%d, RMI at 127.0.0.1:%d, SQLite at %s%n",
                socketPort, rmiPort, database.toAbsolutePath());
    }

    private static boolean isPort(int value) {
        return value >= 1 && value <= 65535;
    }
}
