import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.event.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

/**
 * Entry point for the ChronoArena game client.
 *
 * A name-entry dialog is shown before connecting, so clients never need to
 * edit the properties file.  Network settings (serverIP, TCP_port, UDP_port)
 * and map dimensions are still read from properties.properties.
 */
public class ChronoArenaClientUI {

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        // ---- Load properties ----
        String propertiesPath = "properties.properties";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--properties".equals(args[i])) {
                propertiesPath = args[i + 1];
            }
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
        } catch (IOException e) {
            System.out.println("Warning: could not load '" + propertiesPath + "', using defaults.");
        }

        int    mapWidth = Integer.parseInt(props.getProperty("map_width",  "900"));
        int    mapHeight= Integer.parseInt(props.getProperty("map_height", "650"));
        String serverIP = props.getProperty("serverIP",  "localhost");
        int    tcpPort  = Integer.parseInt(props.getProperty("TCP_port",   "1234"));
        int    udpPort  = Integer.parseInt(props.getProperty("UDP_port",   "1235"));

        // ---- Name / server dialog ----
        String[] joinResult = showJoinDialog(serverIP, tcpPort);
        if (joinResult == null) return;   // user hit Cancel
        String playerName = joinResult[0];
        serverIP          = joinResult[1];

        // ---- Connect to server ----
        Client client;
        try {
            System.out.println("[DEBUG] Attempting connection to " + serverIP + ":" + tcpPort);
            Socket         tcpSocket  = new Socket(serverIP, tcpPort);
            DatagramSocket udpSocket  = new DatagramSocket();
            InetAddress    serverAddr = InetAddress.getByName(serverIP);

            client = new Client(tcpSocket, udpSocket, playerName, serverAddr, udpPort);
            client.join();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "Cannot connect to server at " + serverIP + ":" + tcpPort
                            + "\n\n" + e.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int myPlayerId = client.getPlayerId();
        if (myPlayerId < 0) {
            JOptionPane.showMessageDialog(null,
                    "Server did not assign a player ID.\nThe game may have already ended.",
                    "Join Failed", JOptionPane.ERROR_MESSAGE);
            client.closeEverything();
            return;
        }

        // ---- Wire model and start TCP listener ----
        NetworkGameModel model = new NetworkGameModel(props);
        model.myPlayerId = myPlayerId;

        client.setStateCallback(model::applyStateMessage);
        client.setGameOverCallback(model::applyGameOverMessage);
        client.listenForMessage();

        // ---- Open game window on the EDT ----
        final Client     finalClient = client;
        final String     finalName   = playerName;
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ChronoArena — " + finalName);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    finalClient.closeEverything();
                    System.exit(0);
                }
            });
            frame.setResizable(false);

            NetworkGamePanel panel = new NetworkGamePanel(model, finalClient, myPlayerId);
            panel.setPreferredSize(new Dimension(mapWidth, mapHeight));

            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.requestFocusInWindow();
        });
    }

    // -------------------------------------------------------------------------
    // Name-entry dialog
    // -------------------------------------------------------------------------

    /**
     * Shows a modal dialog asking for the player's name.
     * Returns the trimmed name, or null if the user cancelled.
     */
    /** Returns [playerName, serverIP], or null if the user cancelled. */
    private static String[] showJoinDialog(String serverIP, int tcpPort) {
        JTextField nameField = new JTextField(18);
        nameField.setText("Player" + (1 + (int)(Math.random() * 99)));
        nameField.selectAll();

        JTextField ipField = new JTextField(18);
        ipField.setText(serverIP);

        // Auto-focus the name field when the dialog appears
        nameField.addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent e) {
                nameField.requestFocusInWindow();
                nameField.selectAll();
            }
            @Override public void ancestorMoved(AncestorEvent e) {}
            @Override public void ancestorRemoved(AncestorEvent e) {}
        });

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0;
        panel.add(new JLabel("Server IP:"), c);
        c.gridx = 1;
        panel.add(ipField, c);
        c.gridx = 0; c.gridy = 1;
        panel.add(new JLabel("Port:  " + tcpPort), c);
        c.gridx = 0; c.gridy = 2;
        panel.add(new JLabel("Your name:"), c);
        c.gridx = 1;
        panel.add(nameField, c);

        int choice = JOptionPane.showConfirmDialog(
                null, panel, "Join ChronoArena",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (choice != JOptionPane.OK_OPTION) return null;
        String name = nameField.getText().trim();
        String ip   = ipField.getText().trim();
        if (name.isEmpty() || ip.isEmpty()) return null;
        return new String[]{ name, ip };
    }
}
