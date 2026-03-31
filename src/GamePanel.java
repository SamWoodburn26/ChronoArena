import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;

/**
 * GamePanel: draws the arena + HUD and handles keyboard controls.
 */
class ChronoArenaGamePanel extends JPanel {
    private final ChronoArenaLocalGameModel model;
    private final Font fontSmall = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    private final Font fontBig = new Font(Font.SANS_SERIF, Font.BOLD, 28);
    private final Font fontMono = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    private long lastNanos = System.nanoTime();

    ChronoArenaGamePanel(ChronoArenaLocalGameModel model) {
        this.model = model;
        setFocusable(true);
        setBackground(new Color(12, 14, 24));
        bindKeys();

        // ~60 FPS update loop
        Timer timer = new Timer(16, (ActionEvent e) -> {
            long now = System.nanoTime();
            double dt = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;

            // Avoid wild dt spikes (e.g., if the window was moved)
            dt = Math.max(0, Math.min(dt, 0.05));
            model.update(dt);
            repaint();
        });
        timer.start();
    }

    private void bindKeys() {
        int condition = JComponent.WHEN_IN_FOCUSED_WINDOW;
        javax.swing.InputMap im = getInputMap(condition);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke("pressed W"), "upPressed");
        im.put(KeyStroke.getKeyStroke("released W"), "upReleased");
        im.put(KeyStroke.getKeyStroke("pressed S"), "downPressed");
        im.put(KeyStroke.getKeyStroke("released S"), "downReleased");
        im.put(KeyStroke.getKeyStroke("pressed A"), "leftPressed");
        im.put(KeyStroke.getKeyStroke("released A"), "leftReleased");
        im.put(KeyStroke.getKeyStroke("pressed D"), "rightPressed");
        im.put(KeyStroke.getKeyStroke("released D"), "rightReleased");

        // Arrow keys
        im.put(KeyStroke.getKeyStroke("pressed UP"), "upPressed");
        im.put(KeyStroke.getKeyStroke("released UP"), "upReleased");
        im.put(KeyStroke.getKeyStroke("pressed DOWN"), "downPressed");
        im.put(KeyStroke.getKeyStroke("released DOWN"), "downReleased");
        im.put(KeyStroke.getKeyStroke("pressed LEFT"), "leftPressed");
        im.put(KeyStroke.getKeyStroke("released LEFT"), "leftReleased");
        im.put(KeyStroke.getKeyStroke("pressed RIGHT"), "rightPressed");
        im.put(KeyStroke.getKeyStroke("released RIGHT"), "rightReleased");

        // Quit
        im.put(KeyStroke.getKeyStroke("pressed ESCAPE"), "quit");
        im.put(KeyStroke.getKeyStroke("pressed Q"), "quit");

        am.put("upPressed", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputUp(true); }
        });
        am.put("upReleased", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputUp(false); }
        });
        am.put("downPressed", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputDown(true); }
        });
        am.put("downReleased", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputDown(false); }
        });
        am.put("leftPressed", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputLeft(true); }
        });
        am.put("leftReleased", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputLeft(false); }
        });
        am.put("rightPressed", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputRight(true); }
        });
        am.put("rightReleased", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { model.setInputRight(false); }
        });

        am.put("quit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                Window win = SwingUtilities.getWindowAncestor(ChronoArenaGamePanel.this);
                if (win != null) win.dispose();
                System.exit(0);
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawBackground(g2);
        model.drawWorld(g2, fontSmall, fontMono);
        drawHUD(g2, fontBig, fontSmall);
    }

    private void drawBackground(Graphics2D g2) {
        g2.setColor(new Color(12, 14, 24));
        g2.fillRect(0, 0, getWidth(), getHeight());

        // Soft grid
        g2.setColor(new Color(18, 20, 35));
        int grid = 30;
        for (int x = 0; x <= getWidth(); x += grid) {
            g2.drawLine(x, 0, x, getHeight());
        }
        for (int y = 0; y <= getHeight(); y += grid) {
            g2.drawLine(0, y, getWidth(), y);
        }
    }

    private void drawHUD(Graphics2D g2, Font fontBig, Font fontSmall) {
        double timeLeft = model.getTimeLeftSec();
        int mm = (int) (timeLeft / 60);
        int ss = (int) (timeLeft % 60);
        g2.setFont(fontBig);
        g2.setColor(new Color(245, 250, 255));
        g2.drawString(String.format("Time: %02d:%02d", mm, ss), 16, 42);

        g2.setFont(fontSmall);
        g2.setColor(new Color(190, 200, 230));
        g2.drawString("Move: WASD/Arrows   Quit: Esc/Q", 16, 66);

        // Score (top-right)
        Player you = model.getYou();
        if (you != null) {
            g2.setFont(fontSmall);
            String label = "Score: " + Math.round(you.score);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(new Color(245, 250, 255));
            g2.drawString(label, getWidth() - 16 - fm.stringWidth(label), 44);
        }

        if (timeLeft <= 0) {
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 42));
            g2.setColor(new Color(230, 240, 255, 220));
            String msg = "Round Over";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        }
    }
}

