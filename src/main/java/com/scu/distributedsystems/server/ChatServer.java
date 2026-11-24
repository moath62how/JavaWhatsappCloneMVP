package com.scu.distributedsystems.server;

import com.corundumstudio.socketio.AckMode;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.scu.distributedsystems.protocol.ChatData.Group;
import com.scu.distributedsystems.protocol.ChatData.Login;
import com.scu.distributedsystems.protocol.ChatData.Reply;
import com.scu.distributedsystems.protocol.ChatData.User;
import com.scu.distributedsystems.protocol.ChatRequests;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class ChatServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ChatServer.class.getName());

    private final SocketIOServer socketServer;
    private final ChatRepository repository;
    private final ChatService service;
    private final ChatRequests rmiEndpoint;
    private final int rmiPort;
    private Registry registry;
    private boolean socketStartAttempted;
    private boolean closed;

    // Both transports share this worker so session, room, and SQLite operations stay ordered.
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final Map<Long, Session> users = new HashMap<>();
    private final Map<String, Session> tokens = new HashMap<>();
    private final Map<SocketIOClient, Session> sockets = new HashMap<>();

    public ChatServer(int socketPort, int rmiPort, Path database) throws SQLException, IOException {
        var config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(socketPort);
        config.setAckMode(AckMode.MANUAL);
        config.setBossThreads(1);
        config.setWorkerThreads(2);
        socketServer = new SocketIOServer(config);
        repository = new ChatRepository(database);
        service = new ChatService(repository);
        rmiEndpoint = new RmiChatEndpoint(this, service);
        this.rmiPort = rmiPort;
        registerSocketEvents();
    }

    public void start() throws RemoteException {
        try {
            socketStartAttempted = true;
            socketServer.start();
            RMIServerSocketFactory loopback =
                    port -> new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"));
            registry = LocateRegistry.createRegistry(rmiPort, null, loopback);
            var stub = UnicastRemoteObject.exportObject(rmiEndpoint, rmiPort, null, loopback);
            registry.rebind(ChatRequests.NAME, stub);
        } catch (RemoteException | RuntimeException error) {
            try {
                close();
            } catch (RuntimeException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private void registerSocketEvents() {
        socketServer.addDisconnectListener(client -> requests.execute(() -> detach(client)));
        on("session:attach", (client, data) -> {
            Session session = requireSession(text(data, "token"));
            var groups = service.findGroups(session.user.id());
            if (session.socket != null && session.socket != client) {
                detach(session.socket);
            }
            if (sockets.containsKey(client)) {
                detach(client);
            }
            for (Group group : groups) {
                client.joinRoom(room(group.id()));
            }
            sockets.put(client, session);
            session.socket = client;
            return session.user;
        });
        on("message:send", (client, data) -> {
            Session session = sockets.get(client);
            if (session == null) {
                throw new IllegalArgumentException("Attach your login session first.");
            }
            Long receiverId = optionalId(data, "receiverId");
            Long groupId = optionalId(data, "groupId");
            var message = service.sendMessage(session.user.id(), receiverId, groupId, text(data, "content"));
            if (groupId != null) {
                // Application-level multicast to the other members in this Socket.IO room.
                socketServer.getRoomOperations(room(groupId)).sendEvent("message:new", client, message);
            } else {
                // Unicast to one user. The sender receives the saved message in the acknowledgment.
                Session receiver = users.get(receiverId);
                if (receiver != null && receiver.socket != null && receiver.socket != client) {
                    receiver.socket.sendEvent("message:new", message);
                }
            }
            return message;
        });
    }

    Login openSession(User user) {
        Session existing = users.get(user.id());
        if (existing != null) {
            closeSession(existing.token);
        }
        String token = UUID.randomUUID().toString();
        Session session = new Session(user, token);
        tokens.put(token, session);
        users.put(user.id(), session);
        return new Login(user, token);
    }

    User requireUser(String token) {
        return requireSession(token).user;
    }

    void closeSession(String token) {
        Session session = requireSession(token);
        tokens.remove(token);
        users.remove(session.user.id());
        if (session.socket != null) {
            detach(session.socket);
        }
    }

    void joinGroup(long userId, long groupId) {
        Session session = users.get(userId);
        if (session != null && session.socket != null) {
            session.socket.joinRoom(room(groupId));
        }
    }

    void memberAdded(Group group, long userId) {
        joinGroup(userId, group.id());
        socketServer.getRoomOperations(room(group.id())).sendEvent(
                "group:member-added", Map.of("group", group, "userId", userId));
    }

    <T> T submit(Callable<T> action) throws RemoteException {
        try {
            return requests.submit(action).get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RemoteException("Request interrupted.");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IllegalArgumentException invalidRequest) {
                throw invalidRequest;
            }
            LOG.log(System.Logger.Level.ERROR, "RMI request failed.", error.getCause());
            throw new RemoteException("The server could not complete the request.");
        } catch (RejectedExecutionException error) {
            throw new RemoteException("Server is shutting down.");
        }
    }

    private void on(String event, SocketHandler handler) {
        socketServer.addEventListener(event, Map.class, (client, data, ack) ->
                requests.execute(() -> {
                    Reply reply;
                    try {
                        reply = Reply.success(handler.handle(client, data == null ? Map.of() : data));
                    } catch (IllegalArgumentException error) {
                        reply = Reply.failure(error.getMessage());
                    } catch (SQLException | RuntimeException error) {
                        LOG.log(System.Logger.Level.ERROR, "Failed to handle " + event, error);
                        reply = Reply.failure("The server could not complete the request.");
                    }
                    ack.sendAckData(reply);
                }));
    }

    private Session requireSession(String token) {
        Session session = tokens.get(token);
        if (session == null) {
            throw new IllegalArgumentException("Log in first.");
        }
        return session;
    }

    private void detach(SocketIOClient client) {
        Session session = sockets.remove(client);
        if (session != null) {
            session.socket = null;
            for (String room : client.getAllRooms()) {
                if (room.startsWith("group:")) {
                    client.leaveRoom(room);
                }
            }
        }
    }

    private static String room(long groupId) {
        return "group:" + groupId;
    }

    private static String text(Map<?, ?> data, String key) {
        if (data.get(key) instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(key + " must be text.");
    }

    private static Long optionalId(Map<?, ?> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException(key + " must be a positive integer.");
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        unexport(rmiEndpoint);
        unexport(registry);
        try {
            if (socketStartAttempted) {
                socketServer.stop();
            }
        } finally {
            requests.close();
            try {
                repository.close();
            } catch (SQLException error) {
                LOG.log(System.Logger.Level.ERROR, "Could not close SQLite.", error);
            }
        }
    }

    private static void unexport(Remote remote) {
        if (remote != null) {
            try {
                UnicastRemoteObject.unexportObject(remote, true);
            } catch (NoSuchObjectException ignored) {
                // Startup may have failed before this object was exported.
            }
        }
    }

    private static final class Session {
        private final User user;
        private final String token;
        private SocketIOClient socket;

        private Session(User user, String token) {
            this.user = user;
            this.token = token;
        }
    }

    @FunctionalInterface
    private interface SocketHandler {
        Object handle(SocketIOClient client, Map<?, ?> data) throws SQLException;
    }
}
