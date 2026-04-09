import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Properties;

/**
 * Server-side GUI: spectator game view on the left + control panel on the right.
 * Replaces the console command loop — all admin actions are available as buttons.
 */
public class ServerUI {

    private final Server server;
    private JLabel statusLabel;
    private JLabel playerCountLabel;
    private JPanel playerListPanel;

    public ServerUI(Server server) {
        this.server = server;
    }

    public void show() {
        JFrame frame = new JFrame("ChronoArena — Server Control");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                server.closeAll();
                System.exit(0);
            }
        });
        frame.setResizable(false);
        frame.setLayout(new BorderLayout());

        // Left: spectator game view
        ServerGamePanel gamePanel = new ServerGamePanel();
        gamePanel.setPreferredSize(new Dimension(
                GameState.INSTANCE.MAP_WIDTH,
                GameState.INSTANCE.MAP_HEIGHT));
        frame.add(gamePanel, BorderLayout.CENTER);

        // Right: control sidebar
        frame.add(buildControlPanel(), BorderLayout.EAST);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Refresh sidebar every 500 ms
        new Timer(500, e -> refreshPlayerList()).start();
    }

    // -------------------------------------------------------------------------
    // Control panel
    // -------------------------------------------------------------------------

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(230, 0));
        panel.setBackground(Color.BLACK);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createMatteBorder(0, 2, 0, 0, new Color(214, 7, 29)));

        panel.add(monoLabel("SERVER CONTROL",
                new Font(Font.MONOSPACED, Font.BOLD, 13), new Color(214, 7, 29), 10));
        panel.add(monoLabel("IP: " + getLocalIP(),
                new Font(Font.MONOSPACED, Font.PLAIN, 11), new Color(180, 180, 180), 4));
        panel.add(redSeparator());

        statusLabel = monoLabel("STATUS: RUNNING",
                new Font(Font.MONOSPACED, Font.PLAIN, 11), new Color(50, 220, 80), 6);
        panel.add(statusLabel);

        playerCountLabel = monoLabel("PLAYERS: 0",
                new Font(Font.MONOSPACED, Font.PLAIN, 11), new Color(120, 5, 18), 6);
        panel.add(playerCountLabel);

        panel.add(redSeparator());

        // RESET button
        JButton resetBtn = styledButton("RESET ROUND", new Color(100, 0, 0));
        resetBtn.addActionListener(e -> {
            GameState.INSTANCE.resetRound();
            String notice = "SERVER: New round started — good luck!";
            for (ClientHandler ch : ClientHandler.clientHandlers) {
                ch.sendDirect(notice);
            }
            System.out.println("Round reset via server UI.");
        });
        panel.add(btnRow(resetBtn));
        panel.add(Box.createVerticalStrut(6));

        // STOP button
        JButton stopBtn = styledButton("STOP SERVER", new Color(50, 0, 0));
        stopBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(null,
                    "Stop the server and disconnect all players?",
                    "Confirm Stop", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                server.closeAll();
                System.exit(0);
            }
        });
        panel.add(btnRow(stopBtn));
        panel.add(Box.createVerticalStrut(6));

        // SETTINGS button
        JButton settingsBtn = styledButton("GAME SETTINGS", new Color(20, 20, 60));
        settingsBtn.addActionListener(e -> showSettingsDialog());
        panel.add(btnRow(settingsBtn));

        panel.add(redSeparator());
        panel.add(monoLabel("CONNECTED PLAYERS",
                new Font(Font.MONOSPACED, Font.BOLD, 11), new Color(120, 5, 18), 6));

        // Scrollable player list
        playerListPanel = new JPanel();
        playerListPanel.setBackground(Color.BLACK);
        playerListPanel.setLayout(new BoxLayout(playerListPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(playerListPanel);
        scroll.setBackground(Color.BLACK);
        scroll.getViewport().setBackground(Color.BLACK);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(40, 0, 0)));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(scroll);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // -------------------------------------------------------------------------
    // Settings dialog
    // -------------------------------------------------------------------------

    private void showSettingsDialog() {
        Properties current = GameState.INSTANCE.getConfigAsProperties();

        // Field definitions: [label, propertyKey, currentValue]
        Object[][] fields = {
            // Round
            { "Round Duration (sec)",      "round_duration_sec"        },
            // Zones
            { "Zone Count",                "zone_count"                },
            { "Capture Time (sec)",        "zone_capture_sec"          },
            { "Contest Time (sec)",        "zone_capture_contest_sec"  },
            { "Grace Period (sec)",        "zone_grace_sec"            },
            { "Points / sec",              "zone_points_per_sec"       },
            // Items
            { "Max Active Items",          "item_max_active"           },
            { "Energy Item Points",        "energy_item_points"        },
            { "Spawn Interval (ticks)",    "item_spawn_interval_ticks" },
            // Freeze
            { "Freeze Duration (sec)",     "freeze_duration_sec"       },
            { "Freeze Cooldown (sec)",     "freeze_cooldown_sec"       },
            { "Weapon TTL (sec)",          "freeze_weapon_ttl_sec"     },
            { "Freeze Penalty (pts)",      "freeze_penalty_points"     },
        };

        // Build form panel
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.BLACK);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 8, 3, 8);
        gc.anchor = GridBagConstraints.WEST;

        JTextField[] inputs = new JTextField[fields.length];

        String[] sectionHeaders = {
            "round_duration_sec", "zone_count", "item_max_active", "freeze_duration_sec"
        };
        String[] sectionTitles = { "ROUND", "ZONES", "ITEMS", "FREEZE" };

        int row = 0;
        int sectionIdx = 0;
        for (int i = 0; i < fields.length; i++) {
            String key = (String) fields[i][1];

            // Section header
            if (sectionIdx < sectionHeaders.length && key.equals(sectionHeaders[sectionIdx])) {
                gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
                JLabel header = new JLabel(sectionTitles[sectionIdx]);
                header.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
                header.setForeground(new Color(214, 7, 29));
                header.setBorder(BorderFactory.createEmptyBorder(row == 0 ? 0 : 8, 0, 2, 0));
                form.add(header, gc);
                gc.gridwidth = 1;
                row++;
                sectionIdx++;
            }

            gc.gridx = 0; gc.gridy = row;
            JLabel label = new JLabel((String) fields[i][0] + ":");
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            label.setForeground(new Color(200, 200, 200));
            form.add(label, gc);

            gc.gridx = 1;
            JTextField tf = new JTextField(current.getProperty(key, ""), 8);
            tf.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            tf.setBackground(new Color(15, 15, 15));
            tf.setForeground(Color.WHITE);
            tf.setCaretColor(Color.WHITE);
            tf.setBorder(BorderFactory.createLineBorder(new Color(80, 0, 0)));
            inputs[i] = tf;
            form.add(tf, gc);
            row++;
        }

        // Note label
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
        JLabel note = new JLabel("Changes take effect on next RESET.");
        note.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 10));
        note.setForeground(new Color(120, 5, 18));
        note.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        form.add(note, gc);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBackground(Color.BLACK);
        scroll.getViewport().setBackground(Color.BLACK);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(50, 0, 0)));
        scroll.setPreferredSize(new Dimension(380, 420));

        int result = JOptionPane.showConfirmDialog(
                null, scroll, "Game Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        // Validate and apply
        Properties updated = new Properties();
        for (int i = 0; i < fields.length; i++) {
            String key = (String) fields[i][1];
            String val = inputs[i].getText().trim();
            try {
                Double.parseDouble(val); // accepts both int and double
                updated.setProperty(key, val);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null,
                        "Invalid value for \"" + fields[i][0] + "\": " + val,
                        "Invalid Input", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        GameState.INSTANCE.reconfigure(updated);
        System.out.println("Settings updated via server UI.");
    }

    // -------------------------------------------------------------------------
    // Player list refresh — called every 500 ms by Timer
    // -------------------------------------------------------------------------

    private void refreshPlayerList() {
        boolean gameOver = GameState.INSTANCE.isGameOver();
        statusLabel.setText("STATUS: " + (gameOver ? "GAME OVER" : "RUNNING"));
        statusLabel.setForeground(gameOver ? new Color(214, 7, 29) : new Color(50, 220, 80));
        playerCountLabel.setText("PLAYERS: " + ClientHandler.clientHandlers.size());

        playerListPanel.removeAll();
        for (ClientHandler ch : ClientHandler.clientHandlers) {
            Player p = GameState.INSTANCE.getPlayers().get(ch.playerId);
            String info = (p != null)
                    ? String.format("[%d] %s  %dpts", ch.playerId, p.name, (int) p.score)
                    : "[" + ch.playerId + "] connecting...";

            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setBackground(Color.BLACK);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

            JLabel nameLabel = new JLabel(info);
            nameLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            nameLabel.setForeground(new Color(200, 200, 200));
            row.add(nameLabel, BorderLayout.CENTER);

            JButton killBtn = new JButton("KILL");
            killBtn.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
            killBtn.setForeground(Color.WHITE);
            killBtn.setBackground(new Color(120, 5, 18));
            killBtn.setBorder(BorderFactory.createLineBorder(new Color(214, 7, 29)));
            killBtn.setFocusPainted(false);
            killBtn.setPreferredSize(new Dimension(44, 20));
            final int pid = ch.playerId;
            killBtn.addActionListener(e -> {
                for (ClientHandler handler : ClientHandler.clientHandlers) {
                    if (handler.playerId == pid) {
                        handler.killClient("removed by server administrator");
                        break;
                    }
                }
            });
            row.add(killBtn, BorderLayout.EAST);
            playerListPanel.add(row);
        }

        playerListPanel.revalidate();
        playerListPanel.repaint();
    }

    // -------------------------------------------------------------------------
    // Styling helpers
    // -------------------------------------------------------------------------

    private JLabel monoLabel(String text, Font font, Color color, int vpad) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(vpad, 10, vpad, 10));
        return label;
    }

    private JButton styledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setBackground(bg);
        btn.setBorder(BorderFactory.createLineBorder(new Color(214, 7, 29)));
        btn.setFocusPainted(false);
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setMaximumSize(new Dimension(210, 34));
        return btn;
    }

    private JPanel btnRow(JButton btn) {
        JPanel row = new JPanel();
        row.setBackground(Color.BLACK);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(btn);
        return row;
    }

    private static String getLocalIP() {
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : java.util.Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "unavailable";
    }

    private JSeparator redSeparator() {
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(50, 0, 0));
        sep.setBackground(Color.BLACK);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sep;
    }
}
