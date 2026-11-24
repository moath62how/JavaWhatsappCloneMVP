package com.scu.distributedsystems.client;

import com.scu.distributedsystems.protocol.ChatData.Login;
import com.scu.distributedsystems.protocol.ChatData.Message;
import com.scu.distributedsystems.protocol.ChatData.User;
import com.scu.distributedsystems.protocol.ChatRequests;
import io.socket.client.AckWithTimeout;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChatConnection implements AutoCloseable {
    private final URI socketAddress;
    private final int rmiPort;
    private final ExecutorService calls = Executors.newFixedThreadPool(3,
            Thread.ofPlatform().name("chat-rmi-", 0).daemon().factory());
    private Session session;
    private boolean closed;

    ChatConnection(int socketPort, int rmiPort) {
        this.socketAddress = URI.create("http://127.0.0.1:" + socketPort);
        this.rmiPort = rmiPort;
    }

    CompletableFuture<Session> login(String username, String password, boolean register) {
        return background(() -> {
            var registry = LocateRegistry.getRegistry("127.0.0.1", rmiPort);
            var remote = (ChatRequests) registry.lookup(ChatRequests.NAME);
            var options = new IO.Options();
            options.forceNew = true;
            options.timeout = 5_000;
            Socket socket = IO.socket(socketAddress, options);
            if (register) {
                remote.register(username, password);
            }
            return new Session(remote, remote.login(username, password), socket);
        });
    }

    void start(Session next, Observer observer) {
        if (closed) {
            signOut(next);
            return;
        }
        session = next;
        next.observer = observer;
        next.socket.on(Socket.EVENT_CONNECT, args -> onUi(() -> attach(next)));
        next.socket.on(Socket.EVENT_DISCONNECT, args -> onUi(() -> {
            if (session == next) {
                next.generation++;
                next.status(false, "Reconnecting…");
            }
        }));
        next.socket.on(Socket.EVENT_CONNECT_ERROR, args -> onUi(() -> {
            if (session == next) {
                next.status(false, "Server unavailable");
            }
        }));
        next.socket.on("message:new", args -> onUi(() -> {
            if (session == next) {
                try {
                    observer.message(readMessage(object(args)));
                } catch (IllegalStateException error) {
                    observer.error(error.getMessage());
                }
            }
        }));
        next.socket.on("group:member-added", args -> onUi(() -> {
            if (session == next) {
                observer.membershipChanged();
            }
        }));
        next.status(false, "Connecting…");
        next.socket.connect();
    }

    private void attach(Session next) {
        if (session != next) {
            return;
        }
        int attempt = ++next.generation;
        next.status(false, "Connecting…");
        emit(next.socket, "session:attach", Map.of("token", next.login.token()))
                .whenComplete((reply, error) -> onUi(() -> {
                    if (session == next && attempt == next.generation && next.socket.connected()) {
                        if (error == null) {
                            next.status(true, "Connected");
                        } else {
                            next.status(false, "Sign in again");
                            next.observer.error("The server could not restore your session. Sign out and back in.");
                        }
                    }
                }));
    }

    <T> CompletableFuture<T> request(Session owner, Request<T> request) {
        return background(() -> request.call(owner.remote, owner.login.token()));
    }

    CompletableFuture<Message> send(Session owner, boolean group, long recipientId, String content) {
        if (session != owner || !owner.ready || !owner.socket.connected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Wait for the connection before sending."));
        }
        return emit(owner.socket, "message:send",
                Map.of(group ? "groupId" : "receiverId", recipientId, "content", content))
                .thenApply(ChatConnection::readMessage);
    }

    CompletableFuture<Void> signOut(Session owner) {
        if (session == owner) {
            session = null;
        }
        owner.socket.off();
        owner.socket.disconnect();
        return background(() -> {
            owner.remote.logout(owner.login.token());
            return null;
        });
    }

    private static CompletableFuture<JSONObject> emit(Socket socket, String event, Map<String, ?> data) {
        var result = new CompletableFuture<JSONObject>();
        socket.emit(event, new JSONObject(data), new AckWithTimeout(5_000) {
            @Override
            public void onTimeout() {
                result.completeExceptionally(new IllegalStateException(event.equals("message:send")
                        ? "No confirmation received. Check the chat before sending again."
                        : "The server did not confirm the connection."));
            }

            @Override
            public void onSuccess(Object... args) {
                try {
                    JSONObject reply = object(args);
                    if (reply.optBoolean("ok")) {
                        JSONObject response = reply.optJSONObject("data");
                        if (response == null) {
                            throw new IllegalStateException("The server returned an invalid reply.");
                        }
                        result.complete(response);
                    } else {
                        result.completeExceptionally(new IllegalArgumentException(
                                reply.optString("error", "The request failed.")));
                    }
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            }
        });
        return result;
    }

    private static JSONObject object(Object[] args) {
        if (args.length > 0 && args[0] instanceof JSONObject value) {
            return value;
        }
        throw new IllegalStateException("The server returned an invalid event.");
    }

    static Message readMessage(JSONObject data) {
        try {
            return new Message(data.getLong("id"), data.getLong("senderId"),
                    data.isNull("receiverId") ? null : data.getLong("receiverId"),
                    data.isNull("groupId") ? null : data.getLong("groupId"),
                    data.getString("content"), data.getString("createdAt"));
        } catch (JSONException error) {
            throw new IllegalStateException("The server returned an invalid message.", error);
        }
    }

    private <T> CompletableFuture<T> background(Callable<T> task) {
        if (calls.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("The client is closed."));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, calls);
    }

    static void onUi(Runnable task) {
        SwingUtilities.invokeLater(task);
    }

    static String describe(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof RemoteException) {
            return "The server did not respond. Check that it is running.";
        }
        return error.getMessage() == null ? "The request failed." : error.getMessage();
    }

    @Override
    public void close() {
        closed = true;
        if (session != null) {
            signOut(session);
        }
        calls.shutdown();
    }

    static final class Session {
        private final ChatRequests remote;
        private final Login login;
        private final Socket socket;
        private Observer observer;
        private boolean ready;
        private int generation;

        private Session(ChatRequests remote, Login login, Socket socket) {
            this.remote = remote;
            this.login = login;
            this.socket = socket;
        }

        User user() {
            return login.user();
        }

        private void status(boolean connected, String text) {
            ready = connected;
            observer.status(connected, text);
        }
    }

    @FunctionalInterface
    interface Request<T> {
        T call(ChatRequests remote, String token) throws RemoteException;
    }

    interface Observer {
        void status(boolean connected, String text);

        void message(Message message);

        void membershipChanged();

        void error(String message);
    }
}
