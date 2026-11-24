# Feature Brief

This educational MVP demonstrates real-time messaging over Socket.IO for the Distributed Systems course at Suez Canal University. Follow the [repository guidelines](../../AGENTS.md) for the assignment scope and technology choices.

## Primary Learning Objective

Make Socket.IO the focus of implementation and the demo. Use named Socket.IO events for live messages and membership updates. For each message, show which client emits the event, how the server decides who should receive it, and how the receiving clients update their screens without polling. Prioritize this flow before adding optional features.

## Core Functionality

- Register a user.
- Log in and log out.
- List and search users.
- Send and receive private messages in real time.
- Create groups and add group members.
- Send and receive group messages in real time.
- Save and load message history using SQLite. Use the [DBML ERD](chat-schema.dbml) as the schema reference. Each message has exactly one destination, either a user or a group.

## Additional Functionality

- Disappearing messages.
- Desktop notifications.
- Typing indicators.
- Online and offline status.
- Last seen.
- Light and dark modes.
- Message editing.
- Message search.

## Architecture

Keep one Maven project and one server process. Each Swing client runs separately. It uses RMI for account, user, group, and history requests and Socket.IO for live messages and membership updates.

- **Client** displays the Swing interface and sends requests and receives events asynchronously.
- **Server** handles both transports, authenticates users, manages groups, and routes live messages through Socket.IO.
- **Persistence** uses SQLite through JDBC on the server for users, groups, memberships, and message history.

Keep the flow direct from client to server to database. Use these patterns only where they serve the roles below.

| Pattern | Use in this project |
| --- | --- |
| Repository | Keep SQL and JDBC calls separate from business logic. |
| Service Layer | Organize application logic for accounts, groups, and messages. |
| Observer | Use Socket.IO event listeners to update the Swing UI on the event dispatch thread. |
| DTO | Use simple data objects for requests, responses, and events sent between client and server. |
| MVC | Separate Swing screen state, rendering, and input handling. |

## Demo and UI

Run one Socket.IO server on `localhost` and open three Swing clients on the same machine, each with a separate account. Use private delivery to demonstrate unicast and group delivery to demonstrate application-level multicast. Have two clients send messages concurrently to show asynchronous communication and how the server handles multiple clients. Restart the server and reopen a conversation to show that history persists.

Keep the interface minimal and easy to use. Use simple blue, gray, and white colors inspired by iMessage, with readable text, clear controls, and visible connection feedback. Keep the signed-in username visible so the audience can distinguish client windows during the demo.
