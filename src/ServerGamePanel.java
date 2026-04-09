import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the authoritative game state (GameState.INSTANCE) directly.
 * Used by the server-side spectator window — no networking or input handling.
 */
class ServerGamePanel extends JPanel {

    private static final int PLAYER_RADIUS = 16;
    private static final int HUD_HEIGHT    = 54;

    private static final Color RED_ACCENT = new Color(214,  7,  29);
    private static final Color DIM_RED    = new Color(120,  5,  18);
    private static final Color AMBER      = new Color(255, 180,  30);
    private static final Color GRID_COLOR = new Color( 30,  0,   0);

    private static final Color[] PLAYER_COLORS = {
        new Color(214,  7,  29),
        new Color(255, 180,  30),
        new Color(180, 220, 255),
        new Color(200,  80, 200),
        new Color( 50, 220,  80),
        new Color(255, 255, 255),
    };

    private final Font fontTitle = new Font(Font.MONOSPACED, Font.BOLD,  18);
    private final Font fontBig   = new Font(Font.MONOSPACED, Font.BOLD,  24);
    private final Font fontSmall = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    private final Font fontMicro = new Font(Font.MONOSPACED, Font.PLAIN,  9);

    ServerGamePanel() {
        setBackground(Color.BLACK);
        new Timer(16, e -> repaint()).start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBackground(g2);
        drawZones(g2);
        drawItems(g2);
        drawPlayers(g2);
        drawHUD(g2);
        if (GameState.INSTANCE.isGameOver()) drawGameOver(g2);
    }

    // -------------------------------------------------------------------------

    private void drawBackground(Graphics2D g2) {
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, getWidth(), getHeight());

        g2.setColor(GRID_COLOR);
        g2.setStroke(new BasicStroke(0.5f));
        int grid = 40;
        for (int x = 0; x <= getWidth();  x += grid) g2.drawLine(x, 0, x, getHeight());
        for (int y = 0; y <= getHeight(); y += grid) g2.drawLine(0, y, getWidth(), y);

        drawCornerReticle(g2, 10, 10, 18, RED_ACCENT);
        drawCornerReticle(g2, getWidth() - 10, 10, 18, RED_ACCENT);
        drawCornerReticle(g2, 10, getHeight() - 10, 18, RED_ACCENT);
        drawCornerReticle(g2, getWidth() - 10, getHeight() - 10, 18, RED_ACCENT);

        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        g2.drawString("SYS:SERVER  MODE:SPECTATE", 28, getHeight() - 28);
        g2.drawString("MAP:" + GameState.INSTANCE.MAP_WIDTH + "x" + GameState.INSTANCE.MAP_HEIGHT
                + "  PLAYERS:" + GameState.INSTANCE.getPlayers().size(), 28, getHeight() - 18);
    }

    private void drawCornerReticle(Graphics2D g2, int cx, int cy, int sz, Color c) {
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.5f));
        int sx = (cx < getWidth()  / 2) ?  1 : -1;
        int sy = (cy < getHeight() / 2) ?  1 : -1;
        g2.drawLine(cx, cy, cx + sx * sz, cy);
        g2.drawLine(cx, cy, cx, cy + sy * sz);
    }

    private void drawZones(Graphics2D g2) {
        for (Zone z : GameState.INSTANCE.getZones()) {
            Rectangle r = z.rect;
            double grace = GameState.INSTANCE.getGraceRemaining(z.id);

            Color fill, border;
            if (z.contested) {
                fill = new Color(60, 10, 5); border = AMBER;
            } else if (z.ownerId != -1 && grace > 0) {
                fill = new Color(50, 35, 0); border = AMBER;
            } else if (z.ownerId != -1) {
                Color pc = playerColor(z.ownerId);
                fill = new Color(pc.getRed() / 6, pc.getGreen() / 6, pc.getBlue() / 6);
                border = pc;
            } else {
                fill = new Color(10, 0, 0); border = DIM_RED;
            }

            g2.setColor(fill);
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(r.x, r.y, r.width, r.height);

            int bk = 8;
            g2.drawLine(r.x + 1, r.y + 1, r.x + 1 + bk, r.y + 1);
            g2.drawLine(r.x + 1, r.y + 1, r.x + 1, r.y + 1 + bk);
            g2.drawLine(r.x + r.width - 1, r.y + 1, r.x + r.width - 1 - bk, r.y + 1);
            g2.drawLine(r.x + r.width - 1, r.y + 1, r.x + r.width - 1, r.y + 1 + bk);
            g2.drawLine(r.x + 1, r.y + r.height - 1, r.x + 1 + bk, r.y + r.height - 1);
            g2.drawLine(r.x + 1, r.y + r.height - 1, r.x + 1, r.y + r.height - 1 - bk);
            g2.drawLine(r.x + r.width - 1, r.y + r.height - 1, r.x + r.width - 1 - bk, r.y + r.height - 1);
            g2.drawLine(r.x + r.width - 1, r.y + r.height - 1, r.x + r.width - 1, r.y + r.height - 1 - bk);

            g2.setFont(fontMicro);
            g2.setColor(DIM_RED);
            g2.drawString("Z-0" + z.id, r.x + 12, r.y + 13);

            String stateText; Color stateColor;
            if (z.contested) {
                stateText = "CONTESTED"; stateColor = AMBER;
            } else if (z.ownerId != -1 && grace > 0) {
                stateText = String.format("GRACE %.1fs", grace); stateColor = AMBER;
            } else if (z.ownerId != -1) {
                Player owner = GameState.INSTANCE.getPlayers().get(z.ownerId);
                stateText  = (owner != null) ? owner.name.toUpperCase() : "ID-" + z.ownerId;
                stateColor = playerColor(z.ownerId);
            } else {
                stateText = "UNCLAIMED"; stateColor = DIM_RED;
            }
            g2.setFont(fontSmall);
            g2.setColor(stateColor);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(stateText,
                    r.x + (r.width  - fm.stringWidth(stateText)) / 2,
                    r.y + (r.height + fm.getAscent()) / 2 - 2);

            if (z.captureProgress > 0) {
                int barW = (int)(r.width * z.captureProgress);
                g2.setColor(new Color(214, 7, 29, 160));
                g2.fillRect(r.x, r.y + r.height - 6, barW, 6);
                g2.setColor(RED_ACCENT);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(r.x, r.y + r.height - 6, r.width, 6);
            }
        }
    }

    private void drawItems(Graphics2D g2) {
        for (Item item : GameState.INSTANCE.getItems().values()) {
            int ix = (int) item.x, iy = (int) item.y;
            if ("energy".equals(item.kind)) {
                int[] xp = { ix, ix + 10, ix, ix - 10 };
                int[] yp = { iy - 10, iy, iy + 10, iy };
                g2.setColor(new Color(60, 30, 0));
                g2.fillPolygon(xp, yp, 4);
                g2.setColor(AMBER);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawPolygon(xp, yp, 4);
                g2.setFont(fontMicro);
                g2.setColor(AMBER);
                g2.drawString("NRG", ix - 8, iy + 20);
            } else {
                g2.setColor(new Color(40, 0, 5));
                g2.fillRect(ix - 11, iy - 11, 22, 22);
                g2.setColor(RED_ACCENT);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRect(ix - 11, iy - 11, 22, 22);
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(ix, iy - 7, ix, iy + 7);
                g2.drawLine(ix - 7, iy, ix + 7, iy);
                g2.setFont(fontMicro);
                g2.setColor(RED_ACCENT);
                g2.drawString("FRZ", ix - 8, iy + 20);
            }
        }
    }

    private void drawPlayers(Graphics2D g2) {
        double nowSec = System.nanoTime() / 1_000_000_000.0;
        for (Player ps : GameState.INSTANCE.getPlayers().values()) {
            int px = (int) ps.x, py = (int) ps.y, r = PLAYER_RADIUS;
            Color pc = playerColor(ps.id);
            boolean frozen = ps.frozenUntilSec > nowSec;

            if (frozen) {
                g2.setColor(new Color(60, 0, 0, 80));
                g2.fillOval(px - r - 7, py - r - 7, (r + 7) * 2, (r + 7) * 2);
            }

            Color fill = frozen
                    ? pc.darker().darker()
                    : new Color(pc.getRed() / 4, pc.getGreen() / 4, pc.getBlue() / 4);
            int[] xp = { px, px + r, px, px - r };
            int[] yp = { py - r, py, py + r, py };
            g2.setColor(fill);
            g2.fillPolygon(xp, yp, 4);
            g2.setColor(frozen ? DIM_RED : pc);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawPolygon(xp, yp, 4);

            // Name + ID above
            g2.setFont(fontSmall);
            g2.setColor(pc);
            FontMetrics fm = g2.getFontMetrics();
            String label = "[" + ps.id + "] " + ps.name.toUpperCase();
            g2.drawString(label, px - fm.stringWidth(label) / 2, py - r - 8);

            // Score below
            g2.setFont(fontMicro);
            g2.setColor(DIM_RED);
            String scoreLabel = (int) ps.score + "pts";
            fm = g2.getFontMetrics();
            g2.drawString(scoreLabel, px - fm.stringWidth(scoreLabel) / 2, py + r + 14);

            // Weapon indicator
            if (ps.freezeRayUntilSec > nowSec) {
                g2.setFont(fontMicro);
                g2.setColor(RED_ACCENT);
                g2.drawString("[FRZ]", px + r + 4, py + 4);
            }

            // Freeze timer bar
            if (frozen) {
                double rem = ps.frozenUntilSec - nowSec;
                int barW  = r * 2;
                int filled = (int)(barW * Math.min(rem / 4.0, 1.0));
                int barTop = py - r - 22;
                g2.setColor(new Color(30, 0, 0));
                g2.fillRect(px - r, barTop, barW, 5);
                g2.setColor(DIM_RED);
                g2.fillRect(px - r, barTop, filled, 5);
                g2.setColor(RED_ACCENT);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(px - r, barTop, barW, 5);
            }
        }
    }

    private void drawHUD(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 230));
        g2.fillRect(0, 0, getWidth(), HUD_HEIGHT);
        g2.setColor(RED_ACCENT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(0, HUD_HEIGHT, getWidth(), HUD_HEIGHT);
        g2.setColor(DIM_RED);
        g2.setStroke(new BasicStroke(0.5f));
        g2.drawLine(0, HUD_HEIGHT + 2, getWidth(), HUD_HEIGHT + 2);

        g2.setFont(fontTitle);
        g2.setColor(RED_ACCENT);
        g2.drawString("CHRONO//ARENA", 14, 32);
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        g2.drawString("SERVER VIEW", 14, 46);

        double t = GameState.INSTANCE.getTimeLeft();
        int mm = (int)(t / 60), ss = (int)(t % 60);
        g2.setFont(fontBig);
        g2.setColor(t < 30 ? RED_ACCENT : Color.WHITE);
        String timerStr = String.format("%02d:%02d", mm, ss);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(timerStr, (getWidth() - fm.stringWidth(timerStr)) / 2, 36);
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        String timeLabel = "TIME REMAINING";
        fm = g2.getFontMetrics();
        g2.drawString(timeLabel, (getWidth() - fm.stringWidth(timeLabel)) / 2, 46);

        List<Player> sorted = new ArrayList<>(GameState.INSTANCE.getPlayers().values());
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        int bx = getWidth() - 10;
        for (Player ps : sorted) {
            g2.setFont(fontSmall);
            fm = g2.getFontMetrics();
            String label = "[" + ps.id + "] " + ps.name.toUpperCase() + "  " + (int) ps.score;
            int boxW = fm.stringWidth(label) + 14;
            bx -= boxW;
            Color pc = playerColor(ps.id);
            g2.setColor(Color.BLACK);
            g2.fillRect(bx, 10, boxW, 30);
            g2.setColor(pc);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(bx, 10, boxW, 30);
            g2.setColor(pc);
            g2.drawString(label, bx + 8, 30);
            bx -= 6;
        }
    }

    private void drawGameOver(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 200));
        g2.fillRect(0, 0, getWidth(), getHeight());
        int cx = getWidth() / 2, cy = getHeight() / 2;
        int pw = 480, ph = 160, px = cx - pw / 2, py = cy - ph / 2;
        g2.setColor(Color.BLACK);
        g2.fillRect(px, py, pw, ph);
        g2.setColor(RED_ACCENT);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(px, py, pw, ph);
        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 42));
        g2.setColor(RED_ACCENT);
        FontMetrics fm = g2.getFontMetrics();
        String heading = "// GAME OVER //";
        g2.drawString(heading, cx - fm.stringWidth(heading) / 2, cy - 14);
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        String sub = "USE RESET TO START A NEW ROUND";
        fm = g2.getFontMetrics();
        g2.drawString(sub, cx - fm.stringWidth(sub) / 2, cy + 30);
    }

    private static Color playerColor(int id) {
        return PLAYER_COLORS[(Math.abs(id) - 1) % PLAYER_COLORS.length];
    }
}
