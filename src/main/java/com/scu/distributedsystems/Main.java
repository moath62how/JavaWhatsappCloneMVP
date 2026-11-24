package com.scu.distributedsystems;

import com.scu.distributedsystems.server.ChatServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class Main {
    static void main(String[] args) throws Exception {
        int socketPort = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        int rmiPort = args.length > 1 ? Integer.parseInt(args[1]) : 3001;
        if (isInvalidPort(socketPort) || isInvalidPort(rmiPort) || socketPort == rmiPort) {
            throw new IllegalArgumentException("Provide two distinct ports between 1 and 65535.");
        }
        Path database = Path.of(args.length > 2 ? args[2] : "chat.db");
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        var stopped = new CountDownLatch(1);
        try (var server = new ChatServer(socketPort, rmiPort, database)) {
            Runnable closeServer = server::close;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    closeServer.run();
                } finally {
                    stopped.countDown();
                }
            }, "chat-shutdown"));
            server.start();
            System.out.printf("Socket.IO at http://127.0.0.1:%d, RMI at 127.0.0.1:%d, SQLite at %s%n",
                    socketPort, rmiPort, database.toAbsolutePath());
            stopped.await();
        }
    }

    private static boolean isInvalidPort(int value) {
        return value < 1 || value > 65535;
    }
}
