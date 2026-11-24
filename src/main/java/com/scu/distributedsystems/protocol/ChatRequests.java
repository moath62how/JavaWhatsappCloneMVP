package com.scu.distributedsystems.protocol;

import com.scu.distributedsystems.protocol.ChatData.Group;
import com.scu.distributedsystems.protocol.ChatData.Login;
import com.scu.distributedsystems.protocol.ChatData.Message;
import com.scu.distributedsystems.protocol.ChatData.User;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface ChatRequests extends Remote {
    String NAME = "ChatRequests";

    User register(String username, String password) throws RemoteException;

    Login login(String username, String password) throws RemoteException;

    void logout(String token) throws RemoteException;

    List<User> listUsers(String token, String query) throws RemoteException;

    List<Group> listGroups(String token) throws RemoteException;

    Group createGroup(String token, String name) throws RemoteException;

    List<User> listMembers(String token, long groupId) throws RemoteException;

    Group addMember(String token, long groupId, long userId) throws RemoteException;

    List<Message> history(String token, Long peerId, Long groupId, Long beforeId) throws RemoteException;
}
