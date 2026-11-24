package com.scu.distributedsystems.protocol;

import java.io.Serializable;

public final class ChatData {
    private ChatData() {}

    public record User(long id, String username) implements Serializable {}

    public record Group(long id, String name, long createdBy, String createdAt) implements Serializable {}

    public record Message(long id, long senderId, Long receiverId, Long groupId,
                          String content, String createdAt) implements Serializable {}

    public record Login(User user, String token) implements Serializable {}

    public record Reply(boolean ok, Object data, String error) {
        public static Reply success(Object data) {
            return new Reply(true, data, null);
        }

        public static Reply failure(String error) {
            return new Reply(false, null, error);
        }
    }
}
