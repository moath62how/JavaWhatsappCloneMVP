package com.scu.distributedsystems.client;

import com.scu.distributedsystems.client.ChatConnection.Session;
import com.scu.distributedsystems.client.ChatView.Conversation;
import com.scu.distributedsystems.protocol.ChatData.Group;
import com.scu.distributedsystems.protocol.ChatData.Message;
import com.scu.distributedsystems.protocol.ChatData.ReadReceipt;
import com.scu.distributedsystems.protocol.ChatData.User;

import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class ChatController implements AutoCloseable {
    private static final int PAGE_SIZE = 100;
    private static final int MESSAGE_LIMIT = 2000;

    private final ChatConnection connection;
    private final Consumer<String> windowTitle;
    private final ChatView view = new ChatView();

    private final Map<Long, User> users = new HashMap<>();
    private final Map<Long, Group> groups = new HashMap<>();
    private final Set<String> unread = new HashSet<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<String, String> drafts = new HashMap<>();
    private final TreeMap<Long, Message> transcript = new TreeMap<>();
    private final Map<Long, Integer> receiptCounts = new HashMap<>();
    private final Set<Long> reportedRead = new HashSet<>();
    private final Set<Long> reportingRead = new HashSet<>();
    private final Timer readTimer = new Timer(160, _ -> sendReadReceipts());
    private Session session;
    private Conversation selected;
    private boolean connected;
    private boolean historyLoading;
    private boolean hasEarlier;
    private boolean updatingLists;
    private boolean closed;
    private boolean directoryLoading;
    private boolean directoryNeedsRefresh;
    private boolean membersLoading;
    private int conversationVersion;

    ChatController(ChatConnection connection, Consumer<String> windowTitle) {
        this.connection = connection;
        this.windowTitle = windowTitle;
        view.configureConversationRenderer(chat -> unread.contains(chat.key()));
        view.login.addActionListener(event -> authenticate(false));
        view.register.addActionListener(event -> authenticate(true));
        view.username.addActionListener(event -> view.password.requestFocusInWindow());
        view.password.addActionListener(event -> authenticate(false));
        view.logout.addActionListener(event -> logout());
        view.refresh.addActionListener(event -> refreshDirectory());
        view.newGroup.addActionListener(event -> createGroup());
        view.members.addActionListener(event -> showMembers());
        view.older.addActionListener(event -> loadHistory(true));
        view.send.addActionListener(event -> send());
        view.draft.addActionListener(event -> send());
        watch(view.search.getDocument(), this::updateLists);
        watch(view.draft.getDocument(), this::updateControls);
        listenForSelection(view.peopleList);
        listenForSelection(view.groupList);
        readTimer.setRepeats(false);
        view.onViewportChanged(this::checkReadReceipts);
        updateControls();
    }

    JComponent view() {
        return view.root;
    }

    private void authenticate(boolean register) {
        if (closed || !view.login.isEnabled()) {
            return;
        }
        String username = view.username.getText().strip();
        char[] secret = view.password.getPassword();
        String password = new String(secret);
        Arrays.fill(secret, '\0');
        if (username.isEmpty() || password.isEmpty()) {
            authNotice("Enter your username and password.", true);
            return;
        }
        setAuthEnabled(false);
        authNotice(register ? "Creating your account…" : "Signing in…", false);
        connection.login(username, password, register).whenComplete((next, error) -> ChatConnection.onUi(() -> {
            if (closed) {
                return;
            }
            setAuthEnabled(true);
            if (error != null) {
                authNotice(ChatConnection.describe(error), true);
                view.password.selectAll();
                view.password.requestFocusInWindow();
                return;
            }
            openChat(next);
        }));
    }

    private void openChat(Session next) {
        view.password.setText("");
        authNotice("", false);
        session = next;
        view.showChat(session.user().username());
        windowTitle.accept("Local Chat · " + session.user().username());
        showTranscript(true);
        connection.start(next, new ChatConnection.Observer() {
            @Override
            public void status(boolean ready, String text) {
                connected = ready;
                view.connection.setText(text);
                view.connection.setForeground(ready ? ChatView.BLUE : ChatView.MUTED);
                updateControls();
                if (ready) {
                    refreshDirectory();
                    if (selected != null) {
                        loadHistory(false);
                    }
                    checkReadReceipts();
                }
            }

            @Override
            public void message(Message message) {
                incoming(message);
            }

            @Override
            public void membershipChanged() {
                refreshDirectory();
            }

            @Override
            public void receipt(ReadReceipt receipt) {
                receiptCounts.merge(receipt.messageId(), receipt.readCount(), Math::max);
                Message message = transcript.get(receipt.messageId());
                if (message != null && message.readCount() < receipt.readCount()) {
                    transcript.put(message.id(), message.withReadCount(receipt.readCount()));
                    showTranscript(view.isAtLatest());
                }
            }

            @Override
            public void error(String message) {
                notice(message, true);
            }
        });
        refreshDirectory();
    }

    private void setAuthEnabled(boolean enabled) {
        view.login.setEnabled(enabled);
        view.register.setEnabled(enabled);
        view.username.setEnabled(enabled);
        view.password.setEnabled(enabled);
    }

    private void logout() {
        Session previous = session;
        if (previous == null) {
            return;
        }
        session = null;
        connected = false;
        directoryLoading = false;
        directoryNeedsRefresh = false;
        membersLoading = false;
        users.clear();
        groups.clear();
        unread.clear();
        pending.clear();
        drafts.clear();
        receiptCounts.clear();
        reportedRead.clear();
        reportingRead.clear();
        readTimer.stop();
        clearConversation();
        view.search.setText("");
        view.showGroupList(false);
        view.newGroup.setEnabled(true);
        updateLists();
        view.showAuth();
        windowTitle.accept("Local Chat");
        setAuthEnabled(false);
        authNotice("Signing out…", false);
        connection.signOut(previous).whenComplete((unused, error) -> ChatConnection.onUi(() -> {
            if (!closed && session == null) {
                setAuthEnabled(true);
                authNotice(error == null ? "Signed out." : "Signed out here. The server could not confirm.", error != null);
                view.username.requestFocusInWindow();
            }
        }));
    }

    private void refreshDirectory() {
        if (session == null) {
            return;
        }
        if (directoryLoading) {
            directoryNeedsRefresh = true;
            return;
        }
        directoryLoading = true;
        request((remote, token) -> new Directory(remote.listUsers(token, ""), remote.listGroups(token)),
                result -> {
                    users.clear();
                    result.users().forEach(user -> users.put(user.id(), user));
                    groups.clear();
                    result.groups().forEach(group -> groups.put(group.id(), group));
                    updateLists();
                    if (selected != null) {
                        showTranscript(false);
                    }
                    directoryFinished();
                }, error -> {
                    notice(error, true);
                    directoryFinished();
                });
    }

    private void directoryFinished() {
        directoryLoading = false;
        if (directoryNeedsRefresh) {
            directoryNeedsRefresh = false;
            refreshDirectory();
        }
    }

    private void updateLists() {
        updatingLists = true;
        try {
            String filter = view.search.getText().strip().toLowerCase(Locale.ROOT);
            long me = session == null ? 0 : session.user().id();
            var people = users.values().stream()
                    .filter(user -> user.id() != me && user.username().contains(filter))
                    .map(user -> new Conversation(false, user.id(), user.username()))
                    .sorted(ChatController::compareChats).toList();
            var rooms = groups.values().stream().map(ChatController::asConversation)
                    .sorted(ChatController::compareChats).toList();
            setList(view.people, view.peopleList, people);
            setList(view.groups, view.groupList, rooms);
        } finally {
            updatingLists = false;
        }
    }

    private static int compareChats(Conversation first, Conversation second) {
        int names = first.name().compareToIgnoreCase(second.name());
        return names == 0 ? Long.compare(first.id(), second.id()) : names;
    }

    private void setList(DefaultListModel<Conversation> model, JList<Conversation> list, List<Conversation> chats) {
        model.clear();
        model.addAll(chats);
        if (selected != null) {
            list.setSelectedValue(selected, false);
        }
    }

    private void listenForSelection(JList<Conversation> list) {
        list.addListSelectionListener(event -> {
            if (!updatingLists && !event.getValueIsAdjusting()) {
                Conversation chat = list.getSelectedValue();
                if (chat != null) {
                    select(chat);
                }
            }
        });
    }

    private void select(Conversation conversation) {
        if (session == null || conversation.equals(selected)) {
            return;
        }
        if (selected != null) {
            drafts.put(selected.key(), view.draft.getText());
        }
        clearConversation();
        selected = conversation;
        unread.remove(conversation.key());
        view.title.setText(conversation.name());
        view.subtitle.setText(conversation.group() ? "Group conversation" : "Private conversation");
        view.draft.setText(drafts.getOrDefault(conversation.key(), ""));
        updateLists();
        updateControls();
        loadHistory(false);
    }

    private void clearConversation() {
        selected = null;
        conversationVersion++;
        historyLoading = false;
        hasEarlier = false;
        transcript.clear();
        view.draft.setText("");
        view.title.setText("Messages");
        view.subtitle.setText("Select a user or group");
        notice("", false);
        showTranscript(true);
        updateControls();
    }

    private void loadHistory(boolean earlier) {
        Conversation chat = selected;
        if (chat == null || historyLoading || (earlier && (!hasEarlier || transcript.isEmpty()))) {
            return;
        }
        int version = conversationVersion;
        Long beforeId = earlier ? transcript.firstKey() : null;
        historyLoading = true;
        updateControls();
        showTranscript(!earlier && transcript.isEmpty());
        request((remote, token) -> remote.history(token, chat.group() ? null : chat.id(),
                        chat.group() ? chat.id() : null, beforeId),
                page -> {
                    if (conversationVersion != version) {
                        return;
                    }
                    historyLoading = false;
                    if (earlier || transcript.isEmpty()) {
                        hasEarlier = page.size() == PAGE_SIZE;
                    } else if (!page.isEmpty() && page.getFirst().id() < transcript.firstKey()) {
                        hasEarlier = page.size() == PAGE_SIZE;
                    }
                    boolean stickToEnd = !earlier && view.isAtLatest();
                    page.forEach(this::mergeMessage);
                    showTranscript(stickToEnd);
                    updateControls();
                }, error -> {
                    if (conversationVersion == version) {
                        historyLoading = false;
                        notice(error, true);
                        showTranscript(false);
                        updateControls();
                    }
                });
    }

    private void incoming(Message message) {
        String key = message.groupId() != null ? "group:" + message.groupId() : "user:" + message.senderId();
        if (selected != null && selected.key().equals(key)) {
            addToTranscript(message, view.isAtLatest());
        } else {
            unread.add(key);
            view.peopleList.repaint();
            view.groupList.repaint();
        }
        if (!users.containsKey(message.senderId()) ||
                (message.groupId() != null && !groups.containsKey(message.groupId()))) {
            refreshDirectory();
        }
    }

    private void send() {
        Session owner = session;
        Conversation chat = selected;
        String draft = view.draft.getText();
        String content = draft.strip();
        if (owner == null || chat == null || !connected || pending.contains(chat.key()) || content.isEmpty()) {
            return;
        }
        if (content.length() > MESSAGE_LIMIT) {
            notice("Messages can be up to 2,000 characters.", true);
            return;
        }
        pending.add(chat.key());
        updateControls();
        notice("Sending…", false);
        connection.send(owner, chat.group(), chat.id(), content).whenComplete((message, error) ->
                ChatConnection.onUi(() -> {
                    if (session != owner || closed) {
                        return;
                    }
                    pending.remove(chat.key());
                    if (error == null) {
                        if (draft.equals(drafts.get(chat.key()))) {
                            drafts.remove(chat.key());
                        }
                        if (chat.equals(selected)) {
                            if (draft.equals(view.draft.getText())) {
                                view.draft.setText("");
                            }
                            addToTranscript(message, true);
                            notice("", false);
                            view.draft.requestFocusInWindow();
                        }
                    } else if (chat.equals(selected)) {
                        notice(ChatConnection.describe(error), true);
                    }
                    updateControls();
                }));
    }

    private void addToTranscript(Message message, boolean stickToEnd) {
        if (mergeMessage(message)) {
            showTranscript(stickToEnd);
        }
    }

    private boolean mergeMessage(Message message) {
        Message existing = transcript.get(message.id());
        int count = Math.max(message.readCount(), receiptCounts.getOrDefault(message.id(), 0));
        if (existing != null && count <= existing.readCount()) {
            return false;
        }
        transcript.put(message.id(), message.withReadCount(count));
        return true;
    }

    void checkReadReceipts() {
        if (!closed && connected && selected != null) {
            readTimer.restart();
        }
    }

    private void sendReadReceipts() {
        Session owner = session;
        if (owner == null || selected == null || !connected || closed) {
            return;
        }
        List<Long> ids = view.visibleIncomingIds().stream()
                .filter(id -> !reportedRead.contains(id) && !reportingRead.contains(id))
                .limit(100).toList();
        if (ids.isEmpty()) {
            return;
        }
        reportingRead.addAll(ids);
        connection.markRead(owner, ids).whenComplete((_, error) -> ChatConnection.onUi(() -> {
            if (session == owner && !closed) {
                reportingRead.removeAll(ids);
                if (error == null) {
                    reportedRead.addAll(ids);
                    checkReadReceipts();
                }
            }
        }));
    }

    private void showTranscript(boolean stickToEnd) {
        String empty = selected == null ? "Choose a conversation to get started"
                : historyLoading ? "Loading messages…" : "No messages yet";
        long myId = session == null ? 0 : session.user().id();
        view.showTranscript(List.copyOf(transcript.values()), empty, myId,
                id -> users.containsKey(id) ? users.get(id).username() : "User " + id, stickToEnd);
        checkReadReceipts();
    }

    private void createGroup() {
        if (session == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(view.root, "Group name", "New group", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        view.newGroup.setEnabled(false);
        request((remote, token) -> remote.createGroup(token, name),
                group -> {
                    view.newGroup.setEnabled(true);
                    groups.put(group.id(), group);
                    view.showGroupList(true);
                    select(asConversation(group));
                    refreshDirectory();
                }, error -> {
                    view.newGroup.setEnabled(true);
                    notice(error, true);
                });
    }

    private void showMembers() {
        Conversation chat = selected;
        if (chat == null || !chat.group() || membersLoading) {
            return;
        }
        Session owner = session;
        membersLoading = true;
        updateControls();
        request((remote, token) -> new MemberDirectory(remote.listMembers(token, chat.id()),
                        remote.listUsers(token, "")),
                directory -> {
                    membersLoading = false;
                    updateControls();
                    if (!chat.equals(selected)) {
                        return;
                    }
                    displayMembers(owner, chat, directory);
                }, error -> {
                    membersLoading = false;
                    updateControls();
                    notice(error, true);
                });
    }

    private void displayMembers(Session owner, Conversation chat, MemberDirectory directory) {
        var memberIds = new HashSet<Long>();
        directory.members().forEach(user -> memberIds.add(user.id()));
        String[] members = directory.members().stream().map(User::username).toArray(String[]::new);
        var roster = new JList<>(members);
        roster.getAccessibleContext().setAccessibleName("Group members");
        roster.setVisibleRowCount(Math.min(6, members.length));
        var rosterScroll = new javax.swing.JScrollPane(roster);
        Group group = groups.get(chat.id());
        var available = directory.users().stream().filter(user -> !memberIds.contains(user.id())).toList();
        if (group == null || group.createdBy() != owner.user().id() || available.isEmpty()) {
            JOptionPane.showMessageDialog(view.root, rosterScroll, "Members", JOptionPane.PLAIN_MESSAGE);
            return;
        }
        var picker = new JComboBox<>(available.stream().map(User::username).toArray(String[]::new));
        JLabel pickerLabel = ChatView.label("Add a user", 13, true);
        pickerLabel.setLabelFor(picker);
        Object[] inputs = {rosterScroll, pickerLabel, picker};
        var options = new Object[]{"Add member", "Close"};
        int choice = JOptionPane.showOptionDialog(view.root, inputs, "Members", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
        if (choice == 0 && session == owner) {
            User newMember = available.get(picker.getSelectedIndex());
            this.<Group>request((remote, token) -> remote.addMember(token, chat.id(), newMember.id()),
                    updated -> {
                        if (chat.equals(selected)) {
                            notice(newMember.username() + " was added.", false);
                        }
                    }, error -> notice(error, true));
        }
    }

    private <T> void request(ChatConnection.Request<T> operation, Consumer<T> success, Consumer<String> failure) {
        Session owner = session;
        if (owner == null) {
            return;
        }
        CompletableFuture<T> response = connection.request(owner, operation);
        response.whenComplete((result, error) -> ChatConnection.onUi(() -> {
            if (session == owner && !closed) {
                if (error == null) {
                    success.accept(result);
                } else {
                    failure.accept(ChatConnection.describe(error));
                }
            }
        }));
    }

    private void updateControls() {
        boolean chatOpen = selected != null;
        view.members.setVisible(chatOpen && selected.group());
        view.members.setEnabled(chatOpen && selected.group() && !membersLoading);
        view.older.setVisible(chatOpen && hasEarlier);
        view.older.setEnabled(chatOpen && hasEarlier && !historyLoading);
        view.draft.setEnabled(chatOpen);
        view.send.setEnabled(chatOpen && connected && !pending.contains(selected.key())
                && !view.draft.getText().isBlank());
    }

    private void notice(String text, boolean error) {
        view.status.setForeground(error ? ChatView.ERROR : ChatView.MUTED);
        view.status.setText(text);
    }

    private void authNotice(String text, boolean error) {
        view.authNotice.setForeground(error ? ChatView.ERROR : ChatView.MUTED);
        view.authNotice.setText(text);
    }

    private static Conversation asConversation(Group group) {
        return new Conversation(true, group.id(), group.name());
    }

    private static void watch(Document document, Runnable change) {
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                change.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                change.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                change.run();
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        readTimer.stop();
        connection.close();
    }

    private record Directory(List<User> users, List<Group> groups) {}

    private record MemberDirectory(List<User> members, List<User> users) {}
}
