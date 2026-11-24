# Distributed Systems Chat MVP

The Java 26 server runs Socket.IO, Java RMI, and SQLite in one process. The Swing client runs separately and uses FlatLaf for its light theme. Socket.IO handles live private and group messages. RMI handles accounts, the user directory, groups, and history.

## Run the server

Open this folder as a Maven project in IntelliJ IDEA, reload the Maven dependencies, and select the shared **Chat Server** run configuration with JDK 26. If you use an existing `Main` configuration, set its VM options to `-Dio.netty.noUnsafe=true --enable-native-access=ALL-UNNAMED`. Netty uses its standard Java memory path and SQLite can load its native driver.

From a Bash terminal in this folder, run:

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -Dio.netty.noUnsafe=true --enable-native-access=ALL-UNNAMED \
  -cp "target/classes:$(cat target/classpath.txt)" com.scu.distributedsystems.Main
```

The Socket.IO server listens at `http://127.0.0.1:3000`, and the RMI registry listens at `127.0.0.1:3001`. Both listeners are local to the demo machine. SQLite creates `chat.db` in the working directory. To override the defaults, append the Socket.IO port, RMI port, and database path in that order (for example, `3100 3101 demo.db`). Stop the server with `Ctrl+C`.

## Run the Swing client

In IntelliJ IDEA, run `com.scu.distributedsystems.client.ClientMain` with JDK 26. Enable **Allow multiple instances** in the client run configuration to open more than one window. From a Bash terminal after the Maven command above, run:

```bash
java -cp "target/classes:$(cat target/classpath.txt)" com.scu.distributedsystems.client.ClientMain
```

Run that command in three terminals to open three client windows. The client uses Socket.IO port `3000` and RMI port `3001` on `127.0.0.1`. If you changed the server ports, append the matching Socket.IO and RMI ports in that order.

Enter a username and password, then choose **Create account** or **Sign in**. In **People**, search for a user and select their name to open a private chat. Choose **Refresh** to see accounts created since you opened the window. In **Groups**, choose **New group**, then open the group and choose **Members** to add users. The signed-in username and connection status are visible in each window. Use **Load earlier** when a conversation has older history available.

## Client protocol

1. Look up `ChatRequests.NAME` in the RMI registry and use `ChatRequests` to register and log in. Make RMI calls off Swing’s event dispatch thread. A username has 3–30 letters, digits, or underscores and is normalized to lowercase. A password has 8–128 characters.
2. Save the login token in memory. Connect to Socket.IO and emit `session:attach` with `{"token":"…"}` and an acknowledgment callback. Attach again after a socket reconnect. A new login replaces the account’s previous session. Logging out through RMI revokes the token and detaches its socket from group rooms.
3. Use RMI to search for users, list groups, and manage membership. The group creator joins automatically and can add users. Group members can list members, send messages, and read group history.

| Socket.IO event | Direction | Payload and behavior |
| --- | --- | --- |
| `session:attach` | Client → server | `{token}` attaches an authenticated user and joins their group rooms. |
| `message:send` | Client → server | `{receiverId, content}` or `{groupId, content}` saves one message. The sender receives the saved message in the acknowledgment. |
| `message:new` | Server → recipients | Carries the saved message. Private delivery goes to the recipient socket. Group delivery goes to the other connected sockets in the group room. |
| `message:read` | Client → server | `{messageIds}` records up to 100 visible incoming messages in the focused conversation. |
| `message:read-receipt` | Server → sender | `{messageId, readCount}` updates the sender after a recipient reads the message. |
| `group:member-added` | Server → group | `{group, userId}` tells members to update their group view after an RMI membership change. |

Socket.IO requests acknowledge with `{ok, data, error}`. Messages carry `id`, `senderId`, `receiverId`, `groupId`, `content`, `createdAt`, and `readCount`. The server saves messages and read receipts in SQLite. The sender sees **Read** in a private chat or **Read by N** in a group. RMI history returns up to 100 messages in chronological order. Pass the oldest returned ID as `beforeId` to fetch an earlier page, or pass `null` for the latest page.

## Explain the demo

Open three client instances on one machine. Send a private message between two accounts to demonstrate unicast. Have those accounts join a group and send a group message to demonstrate application-level multicast using a Socket.IO room. Watch the third account to verify it receives neither message, then add it to the group and send again to demonstrate multicast to two recipients. Have two clients send at the same time. Restart the server, sign in again, and reopen the conversations to show that history survived. Socket.IO delivers events asynchronously while a single server worker orders membership changes, message writes, and delivery. See the [feature brief](docs/FEATURES.md) and [database ERD](docs/chat-schema.dbml) for the assignment scope.
