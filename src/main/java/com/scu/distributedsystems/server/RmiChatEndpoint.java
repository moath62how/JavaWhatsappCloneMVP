package com.scu.distributedsystems.server;

import com.scu.distributedsystems.protocol.ChatData.Group;
import com.scu.distributedsystems.protocol.ChatData.Login;
import com.scu.distributedsystems.protocol.ChatData.Message;
import com.scu.distributedsystems.protocol.ChatData.User;
import com.scu.distributedsystems.protocol.ChatRequests;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

final class RmiChatEndpoint implements ChatRequests {
    private final ChatServer server;
    private final ChatService service;

    RmiChatEndpoint(ChatServer server, ChatService service) {
        this.server = server;
        this.service = service;
    }

    @Override
    public User register(String username, String password) throws RemoteException {
        return server.submit(() -> service.register(username, password));
    }

    @Override
    public Login login(String username, String password) throws RemoteException {
        return server.submit(() -> server.openSession(service.login(username, password)));
    }

    @Override
    public void logout(String token) throws RemoteException {
        server.submit(() -> {
            server.closeSession(token);
            return null;
        });
    }

    @Override
    public List<User> listUsers(String token, String query) throws RemoteException {
        return server.submit(() -> {
            server.requireUser(token);
            return service.findUsers(query == null ? "" : query);
        });
    }

    @Override
    public List<Group> listGroups(String token) throws RemoteException {
        return server.submit(() -> service.findGroups(server.requireUser(token).id()));
    }

    @Override
    public Group createGroup(String token, String name) throws RemoteException {
        return server.submit(() -> {
            long userId = server.requireUser(token).id();
            Group group = service.createGroup(userId, name);
            server.joinGroup(userId, group.id());
            return group;
        });
    }

    @Override
    public List<User> listMembers(String token, long groupId) throws RemoteException {
        return server.submit(() -> service.findMembers(groupId, server.requireUser(token).id()));
    }

    @Override
    public Group addMember(String token, long groupId, long userId) throws RemoteException {
        return server.submit(() -> {
            Group group = service.addMember(groupId, server.requireUser(token).id(), userId);
            server.memberAdded(group, userId);
            return group;
        });
    }

    @Override
    public List<Message> history(String token, Long peerId, Long groupId, Long beforeId)
            throws RemoteException {
        return server.submit(() -> new ArrayList<>(service.history(server.requireUser(token).id(),
                peerId, groupId, beforeId == null ? Long.MAX_VALUE : beforeId)));
    }
}
