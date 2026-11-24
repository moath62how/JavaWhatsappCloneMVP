package com.scu.distributedsystems.client;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) {
        int socketPort = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        int rmiPort = args.length > 1 ? Integer.parseInt(args[1]) : 3001;
        if (socketPort < 1 || socketPort > 65535 || rmiPort < 1 || rmiPort > 65535) {
            throw new IllegalArgumentException("Ports must be between 1 and 65535.");
        }
        SwingUtilities.invokeLater(() -> {
            ChatView.installLookAndFeel();
            var frame = new JFrame("Local Chat");
            var controller = new ChatController(new ChatConnection(socketPort, rmiPort), frame::setTitle);
            frame.setContentPane(controller.view());
            frame.setMinimumSize(new Dimension(500, 410));
            frame.setSize(690, 530);
            frame.setLocationByPlatform(true);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    controller.close();
                }
            });
            frame.setVisible(true);
        });
    }
}
