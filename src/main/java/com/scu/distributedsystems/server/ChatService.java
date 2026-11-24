package com.scu.distributedsystems.server;

import com.scu.distributedsystems.protocol.ChatData.Group;
import com.scu.distributedsystems.protocol.ChatData.Message;
import com.scu.distributedsystems.protocol.ChatData.User;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class ChatService {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{3,30}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChatRepository repository;

    ChatService(ChatRepository repository) {
        this.repository = repository;
    }

    User register(String username, String password) throws SQLException {
        username = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Username must be 3–30 letters, digits, or underscores.");
        }
        if (!validPasswordLength(password)) {
            throw new IllegalArgumentException("Password must be 8–128 characters.");
        }
        if (repository.findAccount(username) != null) {
            throw new IllegalArgumentException("That username is already taken.");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        var encoder = Base64.getEncoder();
        String hash = encoder.encodeToString(salt) + ":" + encoder.encodeToString(derive(password, salt));
        return repository.createUser(username, hash, now());
    }

    User login(String username, String password) throws SQLException {
        if (username == null || !validPasswordLength(password)) {
            throw new IllegalArgumentException("Invalid username or password.");
        }
        var account = repository.findAccount(username.strip().toLowerCase(Locale.ROOT));
        if (account == null) {
            throw new IllegalArgumentException("Invalid username or password.");
        }
        var decoder = Base64.getDecoder();
        String[] hash = account.passwordHash().split(":", 2);
        if (!MessageDigest.isEqual(decoder.decode(hash[1]), derive(password, decoder.decode(hash[0])))) {
            throw new IllegalArgumentException("Invalid username or password.");
        }
        return account.user();
    }

    List<User> findUsers(String search) throws SQLException {
        return repository.findUsers(search.strip().toLowerCase(Locale.ROOT));
    }

    List<Group> findGroups(long userId) throws SQLException {
        return repository.findGroups(userId);
    }

    Group createGroup(long userId, String name) throws SQLException {
        return repository.createGroup(userId, checkedText(name, 80, "Group name"), now());
    }

    List<User> findMembers(long groupId, long userId) throws SQLException {
        requireMembership(groupId, userId);
        return repository.findMembers(groupId);
    }

    Group addMember(long groupId, long callerId, long newMemberId) throws SQLException {
        Group group = repository.findGroup(groupId);
        if (group == null || group.createdBy() != callerId) {
            throw new IllegalArgumentException("Only the group creator can add members.");
        }
        requireUser(newMemberId);
        if (!repository.addMember(groupId, newMemberId, now())) {
            throw new IllegalArgumentException("That user is already a member.");
        }
        return group;
    }

    Message sendMessage(long senderId, Long receiverId, Long groupId, String content) throws SQLException {
        checkDestination(receiverId, groupId);
        if (groupId != null) {
            requireMembership(groupId, senderId);
        } else {
            requireUser(receiverId);
        }
        return repository.saveMessage(senderId, receiverId, groupId,
                checkedText(content, 2000, "Message"), now());
    }

    List<Message> history(long userId, Long peerId, Long groupId, long beforeId) throws SQLException {
        checkDestination(peerId, groupId);
        if (beforeId <= 0) {
            throw new IllegalArgumentException("beforeId must be a positive integer.");
        }
        if (groupId != null) {
            requireMembership(groupId, userId);
            return repository.groupHistory(groupId, beforeId);
        }
        requireUser(peerId);
        return repository.privateHistory(userId, peerId, beforeId);
    }

    private void requireMembership(long groupId, long userId) throws SQLException {
        if (!repository.isMember(groupId, userId)) {
            throw new IllegalArgumentException("You are not a member of that group.");
        }
    }

    private void requireUser(long userId) throws SQLException {
        if (!repository.userExists(userId)) {
            throw new IllegalArgumentException("User not found.");
        }
    }

    private static String checkedText(String value, int limit, String label) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > limit) {
            throw new IllegalArgumentException(label + " must be 1–" + limit + " characters.");
        }
        return text;
    }

    private static void checkDestination(Long userId, Long groupId) {
        if ((userId == null) == (groupId == null)) {
            throw new IllegalArgumentException("Choose exactly one user or group.");
        }
        if ((userId != null ? userId : groupId) <= 0) {
            throw new IllegalArgumentException("Destination ID must be a positive integer.");
        }
    }

    private static boolean validPasswordLength(String password) {
        return password != null && password.length() >= 8 && password.length() <= 128;
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing failed.", error);
        } finally {
            spec.clearPassword();
        }
    }
}
