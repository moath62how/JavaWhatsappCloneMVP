package com.scu.distributedsystems.client;

import com.scu.distributedsystems.protocol.ChatData.Message;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

final class ChatView {
    static final Color WHITE = Color.WHITE;
    static final Color INK = new Color(0x172131);
    static final Color MUTED = new Color(0x526175);
    static final Color BLUE = new Color(0x065BBE);
    static final Color TINT = new Color(0xE3EFFF);
    static final Color PAPER = new Color(0xF3F5F8);
    static final Color LINE = new Color(0xDCE2EA);
    static final Color ERROR = new Color(0x9D3237);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(ZoneId.systemDefault());

    private final CardLayout pages = new CardLayout();
    final JPanel root = new JPanel(pages);
    final JTextField username = field("Username", 21);
    final JPasswordField password = new JPasswordField(21);
    final JButton login = button("Sign in", true);
    final JButton register = button("Create account", false);
    final JTextArea authNotice = notice();

    final JLabel identity = label("", 14, true);
    final JLabel connection = label("Connecting…", 12, false);
    final JButton logout = button("Sign out", false);
    final JTextField search = field("Search users", 8);
    final DefaultListModel<Conversation> people = new DefaultListModel<>();
    final DefaultListModel<Conversation> groups = new DefaultListModel<>();
    final JList<Conversation> peopleList = conversationList(people, "Users");
    final JList<Conversation> groupList = conversationList(groups, "Groups");
    private final CardLayout directoryCards = new CardLayout();
    private final JPanel directory = new JPanel(directoryCards);
    private final JButton peopleTab = button("People", false);
    private final JButton groupsTab = button("Groups", false);
    final JButton refresh = button("Refresh", false);
    final JButton newGroup = button("New group", true);

    final JLabel title = label("Messages", 20, true);
    final JLabel subtitle = label("Select a user or group", 12, false);
    final JButton members = button("Members", false);
    final JButton older = button("Load earlier", false);
    final JTextArea status = notice();
    final JTextField draft = field("Message", 8);
    final JButton send = button("Send", true);

    private final CardLayout transcriptCards = new CardLayout();
    private final JPanel transcriptPanel = new JPanel(transcriptCards);
    private final JLabel empty = label("Choose a conversation to get started", 14, false);
    private final Transcript transcript = new Transcript();
    private final JScrollPane messageScroll = scroll(transcript);
    private List<Message> messages = List.of();
    private long currentUser;
    private LongFunction<String> nameFor = id -> "";
    private int renderVersion;

    static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatLightLaf");
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException error) {
            throw new IllegalStateException("Could not load FlatLaf. Reload the Maven dependencies.", error);
        }
        UIManager.put("Component.focusColor", BLUE);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 12);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("TextField.placeholderForeground", MUTED);
        UIManager.put("[style]Button.localPrimary", Map.of(
                "background", BLUE, "foreground", WHITE,
                "hoverBackground", new Color(0x0752A7), "pressedBackground", new Color(0x064486),
                "focusedBackground", BLUE, "disabledBackground", LINE, "disabledText", MUTED));
        UIManager.put("[style]Button.localSecondary", Map.of(
                "background", PAPER, "foreground", BLUE, "hoverBackground", TINT, "pressedBackground", LINE));
        UIManager.put("[style]Button.localTab", Map.of(
                "background", PAPER, "foreground", MUTED, "hoverBackground", TINT, "pressedBackground", LINE));
        UIManager.put("[style]Button.localSelectedTab", Map.of(
                "background", TINT, "foreground", BLUE, "hoverBackground", TINT, "pressedBackground", LINE));
    }

    ChatView() {
        root.add(authPanel(), "auth");
        root.add(chatPanel(), "chat");
        showAuth();
    }

    void showAuth() {
        pages.show(root, "auth");
        username.requestFocusInWindow();
    }

    void showChat(String username) {
        identity.setText(username);
        pages.show(root, "chat");
        peopleList.requestFocusInWindow();
    }

    void showGroupList(boolean showGroups) {
        directoryCards.show(directory, showGroups ? "groups" : "people");
        selectTab(peopleTab, !showGroups);
        selectTab(groupsTab, showGroups);
    }

    private static void selectTab(JButton tab, boolean selected) {
        tab.putClientProperty("FlatLaf.styleClass", selected ? "localSelectedTab" : "localTab");
    }

    void showTranscript(List<Message> items, String emptyText, long userId,
                        LongFunction<String> displayName, boolean stickToEnd) {
        currentUser = userId;
        nameFor = displayName;
        empty.setText(emptyText);
        transcriptCards.show(transcriptPanel, items.isEmpty() ? "empty" : "items");
        renderMessages(items, stickToEnd);
    }

    private void renderMessages(List<Message> items, boolean stickToEnd) {
        var bar = messageScroll.getVerticalScrollBar();
        int previousY = bar.getValue();
        Long anchorId = null;
        int offset = 0;
        for (int i = 0; i < messages.size(); i++) {
            Component row = transcript.getComponent(i);
            if (row.getY() + row.getHeight() > previousY) {
                anchorId = messages.get(i).id();
                offset = previousY - row.getY();
                break;
            }
        }

        messages = items;
        int version = ++renderVersion;
        int width = messageScroll.getViewport().getWidth();
        transcript.removeAll();
        Component anchor = null;
        for (Message message : items) {
            JPanel row = bubble(message, Math.max(140, width - 16));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            transcript.add(row);
            if (anchorId != null && message.id() == anchorId) {
                anchor = row;
            }
        }
        transcript.revalidate();
        transcript.repaint();
        Component scrollAnchor = anchor;
        int anchorOffset = offset;
        if (!items.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                if (version != renderVersion) {
                    return;
                }
                if (stickToEnd || scrollAnchor == null) {
                    bar.setValue(bar.getMaximum());
                } else {
                    bar.setValue(scrollAnchor.getY() + anchorOffset);
                }
            });
        }
    }

    boolean isAtLatest() {
        var bar = messageScroll.getVerticalScrollBar();
        return bar.getMaximum() - bar.getValue() - bar.getVisibleAmount() < 48;
    }

    void configureConversationRenderer(java.util.function.Predicate<Conversation> unread) {
        var renderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                putClientProperty("html.disable", Boolean.TRUE);
                Conversation chat = (Conversation) value;
                boolean hasUnread = unread.test(chat);
                setText(chat.name() + (hasUnread ? "  •" : ""));
                setFont(getFont().deriveFont(selected || hasUnread ? Font.BOLD : Font.PLAIN, 14f));
                setForeground(selected ? BLUE : INK);
                setBackground(selected ? TINT : PAPER);
                setBorder(BorderFactory.createCompoundBorder(
                        focus ? BorderFactory.createLineBorder(BLUE) : new EmptyBorder(1, 1, 1, 1),
                        new EmptyBorder(8, 11, 8, 11)));
                return this;
            }
        };
        peopleList.setCellRenderer(renderer);
        groupList.setCellRenderer(renderer);
    }

    private JPanel authPanel() {
        JPanel background = new JPanel(new GridBagLayout());
        background.setBackground(PAPER);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(WHITE);
        form.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(24, 26, 20, 26)));
        var cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.anchor = GridBagConstraints.LINE_START;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.weightx = 1;
        cell.insets = new Insets(0, 0, 12, 0);
        form.add(label("Local Chat", 28, true), cell);
        form.add(label("Sign in to your demo account.", 13, false), cell);
        cell.insets = new Insets(2, 0, 4, 0);
        JLabel usernameLabel = label("Username", 13, true);
        usernameLabel.setLabelFor(username);
        form.add(usernameLabel, cell);
        form.add(username, cell);
        JLabel passwordLabel = label("Password", 13, true);
        passwordLabel.setLabelFor(password);
        form.add(passwordLabel, cell);
        styleField(password, "Password");
        form.add(password, cell);
        cell.insets = new Insets(10, 0, 0, 0);
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        actions.add(login);
        actions.add(register);
        form.add(actions, cell);
        authNotice.setRows(2);
        form.add(authNotice, cell);
        background.add(form);
        return background;
    }

    private JPanel chatPanel() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(WHITE);
        JPanel account = new JPanel(new BorderLayout(12, 0));
        account.setBackground(WHITE);
        account.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(8, 14, 8, 12)));
        JPanel accountInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        accountInfo.setOpaque(false);
        accountInfo.add(identity);
        accountInfo.add(connection);
        account.add(accountInfo, BorderLayout.CENTER);
        account.add(logout, BorderLayout.EAST);
        page.add(account, BorderLayout.NORTH);
        page.add(sidebar(), BorderLayout.WEST);
        page.add(conversationPanel(), BorderLayout.CENTER);
        return page;
    }

    private JPanel sidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 9));
        sidebar.setPreferredSize(new Dimension(188, 300));
        sidebar.setBackground(PAPER);
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, LINE), new EmptyBorder(10, 9, 9, 9)));

        JPanel peoplePage = new JPanel(new BorderLayout(0, 6));
        peoplePage.setBackground(PAPER);
        JLabel searchLabel = label("Search users", 12, false);
        searchLabel.setLabelFor(search);
        JPanel searchInput = new JPanel(new BorderLayout(0, 4));
        searchInput.setOpaque(false);
        searchInput.add(searchLabel, BorderLayout.NORTH);
        searchInput.add(search, BorderLayout.CENTER);
        peoplePage.add(searchInput, BorderLayout.NORTH);
        peoplePage.add(scroll(peopleList), BorderLayout.CENTER);
        JPanel groupPage = new JPanel(new BorderLayout(0, 8));
        groupPage.setBackground(PAPER);
        groupPage.add(scroll(groupList), BorderLayout.CENTER);
        groupPage.add(newGroup, BorderLayout.SOUTH);
        JPanel tabs = new JPanel(new GridLayout(1, 2, 4, 0));
        tabs.setOpaque(false);
        tabs.add(peopleTab);
        tabs.add(groupsTab);
        peopleTab.addActionListener(event -> showGroupList(false));
        groupsTab.addActionListener(event -> showGroupList(true));
        directory.add(peoplePage, "people");
        directory.add(groupPage, "groups");
        showGroupList(false);
        sidebar.add(tabs, BorderLayout.NORTH);
        sidebar.add(directory, BorderLayout.CENTER);
        sidebar.add(refresh, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel conversationPanel() {
        JPanel chat = new JPanel(new BorderLayout());
        chat.setBackground(WHITE);
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(12, 14, 12, 12)));
        JPanel labels = new JPanel(new GridLayout(2, 1, 0, 2));
        labels.setOpaque(false);
        labels.add(title);
        labels.add(subtitle);
        header.add(labels, BorderLayout.CENTER);
        header.add(members, BorderLayout.EAST);
        chat.add(header, BorderLayout.NORTH);

        empty.setHorizontalAlignment(SwingConstants.CENTER);
        transcript.setBackground(WHITE);
        transcript.setBorder(new EmptyBorder(8, 8, 8, 8));
        transcript.getAccessibleContext().setAccessibleName("Message history");
        messageScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                renderMessages(messages, isAtLatest());
            }
        });
        transcriptPanel.setBackground(WHITE);
        transcriptPanel.add(empty, "empty");
        transcriptPanel.add(messageScroll, "items");
        JPanel history = new JPanel(new BorderLayout());
        history.setBackground(WHITE);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 3));
        paging.setOpaque(false);
        paging.add(older);
        history.add(paging, BorderLayout.NORTH);
        history.add(transcriptPanel, BorderLayout.CENTER);
        chat.add(history, BorderLayout.CENTER);

        JPanel compose = new JPanel(new BorderLayout(8, 3));
        compose.setBackground(WHITE);
        compose.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(5, 12, 9, 12)));
        compose.add(status, BorderLayout.NORTH);
        draft.putClientProperty("JTextField.placeholderText", "Write a message…");
        compose.add(draft, BorderLayout.CENTER);
        compose.add(send, BorderLayout.EAST);
        chat.add(compose, BorderLayout.SOUTH);
        return chat;
    }

    private JPanel bubble(Message message, int width) {
        boolean mine = message.senderId() == currentUser;
        var row = new JPanel(new BorderLayout());
        row.setBackground(WHITE);
        row.setBorder(new EmptyBorder(5, 6, 5, 6));

        var stack = new JPanel(new BorderLayout(0, 3));
        stack.setOpaque(false);
        String timestamp = TIME.format(Instant.parse(message.createdAt()));
        JLabel details = label((mine ? "You" : nameFor.apply(message.senderId())) + " · " + timestamp, 11, true);
        details.setHorizontalAlignment(mine ? SwingConstants.RIGHT : SwingConstants.LEFT);
        stack.add(details, BorderLayout.NORTH);

        JPanel bubble = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D pen = (Graphics2D) g.create();
                pen.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                pen.setColor(mine ? BLUE : PAPER);
                pen.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                pen.dispose();
            }
        };
        bubble.setOpaque(false);
        bubble.setBorder(new EmptyBorder(7, 10, 7, 10));
        JTextArea text = new JTextArea(message.content());
        text.setOpaque(false);
        text.setFont(details.getFont().deriveFont(Font.PLAIN, 14f));
        text.setForeground(mine ? WHITE : INK);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setTabSize(4);
        text.setEditable(false);
        int longestLine = message.content().lines().mapToInt(text.getFontMetrics(text.getFont())::stringWidth)
                .max().orElse(0);
        int wrap = Math.max(44, Math.min((int) (width * 0.70) - 20, longestLine + 4));
        text.setSize(wrap, Short.MAX_VALUE);
        text.setPreferredSize(new Dimension(wrap, text.getPreferredSize().height));
        bubble.add(text);
        JPanel alignment = new JPanel(new BorderLayout());
        alignment.setOpaque(false);
        alignment.add(bubble, mine ? BorderLayout.EAST : BorderLayout.WEST);
        stack.add(alignment, BorderLayout.CENTER);
        Dimension preferred = stack.getPreferredSize();
        stack.setPreferredSize(new Dimension(Math.min(width - 12, preferred.width), preferred.height));
        row.add(stack, mine ? BorderLayout.EAST : BorderLayout.WEST);
        return row;
    }

    private static JList<Conversation> conversationList(DefaultListModel<Conversation> model, String label) {
        var list = new JList<>(model);
        list.setBackground(PAPER);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName(label);
        return list;
    }

    private static JScrollPane scroll(JComponent content) {
        var scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(content.getBackground());
        return scroll;
    }

    static JLabel label(String text, float size, boolean strong) {
        JLabel label = new JLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setText(text);
        label.setFont(label.getFont().deriveFont(strong ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(strong ? INK : MUTED);
        return label;
    }

    private static JTextArea notice() {
        JTextArea text = new JTextArea(1, 1);
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(text.getFont().deriveFont(12f));
        text.setForeground(MUTED);
        return text;
    }

    private static JTextField field(String name, int columns) {
        JTextField field = new JTextField(columns);
        styleField(field, name);
        return field;
    }

    private static void styleField(JTextField field, String name) {
        field.getAccessibleContext().setAccessibleName(name);
        field.setFont(field.getFont().deriveFont(14f));
        field.setForeground(INK);
        field.setMargin(new Insets(6, 8, 6, 8));
    }

    private static JButton button(String title, boolean primary) {
        var button = new JButton(title);
        button.putClientProperty("JButton.buttonType", "borderless");
        button.putClientProperty("JComponent.minimumWidth", 0);
        button.putClientProperty("JComponent.minimumHeight", 30);
        button.putClientProperty("FlatLaf.styleClass", primary ? "localPrimary" : "localSecondary");
        button.setMargin(new Insets(5, 10, 5, 10));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static final class Transcript extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        Transcript() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle area, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle area, int orientation, int direction) {
            return Math.max(18, area.height - 18);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    record Conversation(boolean group, long id, String name) {
        String key() {
            return (group ? "group:" : "user:") + id;
        }
    }
}
