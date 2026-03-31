import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Local simulation model used by the UI scaffold (no networking yet).
 *
 * Later, replace this with authoritative server-driven state updates.
 */
class ChronoArenaLocalGameModel {
    final int width;
    final int height;
    final double roundDurationSec;

    private final Player you;
    private final List<Zone> zones = new ArrayList<>();

    private double timeLeftSec;

    // Input state (only the "you" player uses these)
    private boolean inputUp, inputDown, inputLeft, inputRight;
    private final int youId = 1;

    ChronoArenaLocalGameModel(int width, int height, double roundDurationSec) {
        this.width = width;
        this.height = height;
        this.roundDurationSec = roundDurationSec;
        this.timeLeftSec = roundDurationSec;
        spawnMap();
        this.you = new Player(youId, true, "You", width * 0.5, height * 0.5);
        this.you.score = 0;
    }

    Player getYou() {
        return you;
    }

    double getTimeLeftSec() {
        return timeLeftSec;
    }

    double nowSec() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    void setInputUp(boolean v) {
        inputUp = v;
    }

    void setInputDown(boolean v) {
        inputDown = v;
    }

    void setInputLeft(boolean v) {
        inputLeft = v;
    }

    void setInputRight(boolean v) {
        inputRight = v;
    }

    List<Player> getPlayers() {
        return java.util.List.of(you);
    }

    void update(double dt) {
        if (timeLeftSec > 0) {
            timeLeftSec -= dt;
        }

        double now = nowSec();

        // Move the single player
        if (timeLeftSec > 0) {
            moveFromInput(you, dt);
            tickZoneScoring(you, dt);
        }
    }

    private void moveFromInput(Player p, double dt) {
        double dx = (inputRight ? 1 : 0) - (inputLeft ? 1 : 0);
        double dy = (inputDown ? 1 : 0) - (inputUp ? 1 : 0);

        double mag = Math.hypot(dx, dy);
        if (mag > 1e-6) {
            dx /= mag;
            dy /= mag;
        }

        double speed = 220.0;
        p.vx = dx * speed;
        p.vy = dy * speed;

        p.x = clamp(p.x + p.vx * dt, 0, width);
        p.y = clamp(p.y + p.vy * dt, 0, height);
    }

    private void tickZoneScoring(Player you, double dt) {
        boolean inAnyZone = false;
        for (Zone z : zones) {
            if (z.rect.contains(you.x, you.y)) {
                inAnyZone = true;
                break;
            }
        }
        if (inAnyZone) {
            you.score += dt * 5.0; // points per second while in a zone
        }
    }

    private void spawnMap() {
        zones.clear();
        int margin = 60;
        int zoneW = 160;
        int zoneH = 120;

        zones.add(new Zone(1, new Rectangle(margin, margin, zoneW, zoneH)));
        zones.add(new Zone(2, new Rectangle(width - margin - zoneW, margin, zoneW, zoneH)));
        zones.add(new Zone(3, new Rectangle(margin, height - margin - zoneH, zoneW, zoneH)));
        zones.add(new Zone(4, new Rectangle(width - margin - zoneW, height - margin - zoneH, zoneW, zoneH)));
    }

    void drawWorld(Graphics2D g2, Font fontSmall, Font fontMono) {
        // Zones
        for (Zone z : zones) {
            Color base = new Color(70, 90, 140);

            // Filled rect with border
            g2.setColor(base);
            g2.fillRoundRect(z.rect.x, z.rect.y, z.rect.width, z.rect.height, 10, 10);
            g2.setColor(new Color(200, 220, 255));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(z.rect.x, z.rect.y, z.rect.width, z.rect.height, 10, 10);

            g2.setFont(fontSmall);
            g2.setColor(new Color(240, 245, 255));
            String label = "Zone " + z.id;
            g2.drawString(label, z.rect.x + 10, z.rect.y + 22);
        }

        // Player
        Player you = getYou();
        if (you != null) {
            int r = 13;
            Color base = Color.WHITE;

            g2.setColor(base);
            g2.fillOval((int) you.x - r, (int) you.y - r, r * 2, r * 2);
            g2.setColor(new Color(40, 60, 90));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval((int) you.x - r, (int) you.y - r, r * 2, r * 2);

            g2.setFont(fontMono);
            g2.setColor(new Color(235, 245, 255));
            g2.drawString("You", (int) you.x - 12, (int) you.y - r - 18);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static double clamp01(double v) { return clamp(v, 0, 1); }
}

