package com.scu.distributedsystems.server;

import com.scu.distributedsystems.protocol.ChatData.Group;
import com.scu.distributedsystems.protocol.ChatData.Message;
import com.scu.distributedsystems.protocol.ChatData.User;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class ChatRepository implements AutoCloseable {
    record Account(User user, String passwordHash) {}

    private final Connection connection;

    ChatRepository(Path database) throws SQLException, IOException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (var statement = connection.createStatement();
             InputStream schema = ChatRepository.class.getResourceAsStream("/chat-schema.sql")) {
            if (schema == null) {
                throw new IOException("Missing chat-schema.sql.");
            }
            statement.execute("PRAGMA foreign_keys = ON");
            for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (SQLException | IOException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    User createUser(String username, String passwordHash, String now) throws SQLException {
        long id = insert("INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                username, passwordHash, now);
        return new User(id, username);
    }

    Account findAccount(String username) throws SQLException {
        return query("SELECT id, username, password_hash FROM users WHERE username = ?",
                row -> new Account(readUser(row), row.getString("password_hash")), username)
                .stream().findFirst().orElse(null);
    }

    boolean userExists(long id) throws SQLException {
        return !query("SELECT id FROM users WHERE id = ?", row -> row.getLong("id"), id).isEmpty();
    }

    List<User> findUsers(String search) throws SQLException {
        return query("SELECT id, username FROM users WHERE instr(username, ?) > 0 ORDER BY username",
                ChatRepository::readUser, search);
    }

    Group createGroup(long creatorId, String name, String now) throws SQLException {
        connection.setAutoCommit(false);
        try {
            long id = insert("INSERT INTO groups (name, created_by, created_at) VALUES (?, ?, ?)",
                    name, creatorId, now);
            addMember(id, creatorId, now);
            connection.commit();
            return new Group(id, name, creatorId, now);
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    Group findGroup(long groupId) throws SQLException {
        return query("SELECT * FROM groups WHERE id = ?", ChatRepository::readGroup, groupId)
                .stream().findFirst().orElse(null);
    }

    List<Group> findGroups(long userId) throws SQLException {
        return query("""
                SELECT g.* FROM groups g JOIN group_members gm ON gm.group_id = g.id
                WHERE gm.user_id = ? ORDER BY g.name, g.id
                """, ChatRepository::readGroup, userId);
    }

    List<User> findMembers(long groupId) throws SQLException {
        return query("""
                SELECT u.id, u.username FROM users u JOIN group_members gm ON gm.user_id = u.id
                WHERE gm.group_id = ? ORDER BY u.username
                """, ChatRepository::readUser, groupId);
    }

    boolean isMember(long groupId, long userId) throws SQLException {
        return !query("SELECT user_id FROM group_members WHERE group_id = ? AND user_id = ?",
                row -> row.getLong("user_id"), groupId, userId).isEmpty();
    }

    boolean addMember(long groupId, long userId, String now) throws SQLException {
        try (var statement = prepare("""
                INSERT INTO group_members (group_id, user_id, joined_at) VALUES (?, ?, ?)
                ON CONFLICT (group_id, user_id) DO NOTHING
                """, groupId, userId, now)) {
            return statement.executeUpdate() == 1;
        }
    }

    Message saveMessage(long senderId, Long receiverId, Long groupId, String content, String now)
            throws SQLException {
        long id = insert("""
                INSERT INTO messages (sender_id, receiver_id, group_id, content, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, senderId, receiverId, groupId, content, now);
        return new Message(id, senderId, receiverId, groupId, content, now);
    }

    List<Message> privateHistory(long userId, long peerId, long beforeId) throws SQLException {
        return query("""
                SELECT * FROM messages WHERE id < ? AND
                    ((sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?))
                ORDER BY id DESC LIMIT 100
                """, ChatRepository::readMessage, beforeId, userId, peerId, peerId, userId).reversed();
    }

    List<Message> groupHistory(long groupId, long beforeId) throws SQLException {
        return query("SELECT * FROM messages WHERE group_id = ? AND id < ? ORDER BY id DESC LIMIT 100",
                ChatRepository::readMessage, groupId, beforeId).reversed();
    }

    private long insert(String sql, Object... values) throws SQLException {
        try (var statement = prepare(sql + " RETURNING id", values);
             var rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Insert returned no ID.");
            }
            return rows.getLong("id");
        }
    }

    private <T> List<T> query(String sql, RowReader<T> reader, Object... values) throws SQLException {
        try (var statement = prepare(sql, values); var rows = statement.executeQuery()) {
            var results = new ArrayList<T>();
            while (rows.next()) {
                results.add(reader.read(rows));
            }
            return results;
        }
    }

    private PreparedStatement prepare(String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private static User readUser(ResultSet row) throws SQLException {
        return new User(row.getLong("id"), row.getString("username"));
    }

    private static Group readGroup(ResultSet row) throws SQLException {
        return new Group(row.getLong("id"), row.getString("name"), row.getLong("created_by"),
                row.getString("created_at"));
    }

    private static Message readMessage(ResultSet row) throws SQLException {
        return new Message(row.getLong("id"), row.getLong("sender_id"),
                nullableId(row, "receiver_id"), nullableId(row, "group_id"),
                row.getString("content"), row.getString("created_at"));
    }

    private static Long nullableId(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }
}
