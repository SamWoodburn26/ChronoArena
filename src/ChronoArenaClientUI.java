import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Minimal ChronoArena UI + controls scaffold (local simulation mode).
 *
 * Later, you can replace LocalGameModel with authoritative state from your server
 * (TCP for state updates, UDP for action inputs).
 */
public class ChronoArenaClientUI {
    public static void main(String[] args) {
        String propertiesPath = "properties.properties";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--properties".equals(args[i])) {
                propertiesPath = args[i + 1];
            }
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
        } catch (IOException ignored) {
            // Use defaults when the properties file is missing.
        }

        int mapWidth = Integer.parseInt(props.getProperty("map_width", "900"));
        int mapHeight = Integer.parseInt(props.getProperty("map_height", "650"));
        double roundDurationSec = Double.parseDouble(props.getProperty("round_duration_sec", "180"));

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ChronoArena - UI Scaffold");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setResizable(false);

            ChronoArenaLocalGameModel model = new ChronoArenaLocalGameModel(mapWidth, mapHeight, roundDurationSec);
            ChronoArenaGamePanel panel = new ChronoArenaGamePanel(model);
            panel.setPreferredSize(new Dimension(mapWidth, mapHeight));

            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

 }