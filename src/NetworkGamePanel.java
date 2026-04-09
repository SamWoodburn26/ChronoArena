import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Swing panel that renders the full game state received from the server.
 *
 * Responsibilities:
 *   - Repaints at ~60 fps using a Swing Timer
 *   - Performs client-side position prediction for the local player so movement
 *     feels immediate even though server state arrives every ~50 ms
 *   - Routes WASD / arrow key input to client.sendMove() via UDP
 *   - Routes F / Space key to client.sendFreeze() via UDP
 */
class NetworkGamePanel extends JPanel {

    private static final double PLAYER_SPEED  = 220.0;  // pixels per second
    private static final int    PLAYER_RADIUS = 16;
    private static final int    HUD_HEIGHT    = 54;     // reserved top bar (px)

    // Tactical palette: local player is always RED; others cycle through these
    private static final Color   RED_ACCENT    = new Color(214,   7,  29);  // #d6071d
    private static final Color   DIM_RED       = new Color(120,   5,  18);
    private static final Color   AMBER         = new Color(255, 180,  30);
    private static final Color   GRID_COLOR    = new Color( 30,   0,   0);

    private static final Color[] PLAYER_COLORS = {
        new Color(214,   7,  29),   // red (slot 0 — local player maps here via id%6)
        new Color(255, 180,  30),   // amber
        new Color(180, 220, 255),   // pale blue
        new Color(200,  80, 200),   // magenta
        new Color( 50, 220,  80),   // green
        new Color(255, 255, 255),   // white
    };

    private final NetworkGameModel model;
    private final Client           client;
    private final int              myPlayerId;

    // Client-side prediction for the local player
    private double  localX      = 0;
    private double  localY      = 0;
    private boolean initialized = false;

    // Held-key input flags (written and read only on EDT)
    private boolean inputUp, inputDown, inputLeft, inputRight;

    private final Font fontTitle = new Font(Font.MONOSPACED, Font.BOLD,  18);
    private final Font fontBig   = new Font(Font.MONOSPACED, Font.BOLD,  24);
    private final Font fontMed   = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private final Font fontSmall = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    private final Font fontMicro = new Font(Font.MONOSPACED, Font.PLAIN,  9);

    private long lastNanos = System.nanoTime();

    // Active freeze-ray beam (null when none; all fields are EDT-only)
    private FreezeRayEffect freezeRayEffect = null;

    private static class FreezeRayEffect {
        final double startX, startY, endX, endY;
        final long   expiresAt;
        FreezeRayEffect(double sx, double sy, double ex, double ey) {
            startX    = sx;  startY    = sy;
            endX      = ex;  endY      = ey;
            expiresAt = System.currentTimeMillis() + 450;
        }
        boolean alive() { return System.currentTimeMillis() < expiresAt; }
        /** 1.0 at the moment of fire, fading to 0.0 at expiry. */
        float progress() {
            return Math.max(0f, (expiresAt - System.currentTimeMillis()) / 450f);
        }
    }

    NetworkGamePanel(NetworkGameModel model, Client client, int myPlayerId) {
        this.model      = model;
        this.client     = client;
        this.myPlayerId = myPlayerId;

        setFocusable(true);
        setBackground(Color.BLACK);
        bindKeys();

        // ~60 fps update + repaint loop
        new Timer(16, e -> {
            long now = System.nanoTime();
            double dt = Math.min((now - lastNanos) / 1_000_000_000.0, 0.05);
            lastNanos = now;
            updateLocalPosition(dt);
            repaint();
        }).start();
    }

    // -------------------------------------------------------------------------
    // Key bindings
    // -------------------------------------------------------------------------

    private void bindKeys() {
        InputMap  im  = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am  = getActionMap();

        bind(im, am, "pressed W",      "uOn",   () -> inputUp    = true);
        bind(im, am, "released W",     "uOff",  () -> inputUp    = false);
        bind(im, am, "pressed S",      "dOn",   () -> inputDown  = true);
        bind(im, am, "released S",     "dOff",  () -> inputDown  = false);
        bind(im, am, "pressed A",      "lOn",   () -> inputLeft  = true);
        bind(im, am, "released A",     "lOff",  () -> inputLeft  = false);
        bind(im, am, "pressed D",      "rOn",   () -> inputRight = true);
        bind(im, am, "released D",     "rOff",  () -> inputRight = false);

        bind(im, am, "pressed UP",     "uOn2",  () -> inputUp    = true);
        bind(im, am, "released UP",    "uOff2", () -> inputUp    = false);
        bind(im, am, "pressed DOWN",   "dOn2",  () -> inputDown  = true);
        bind(im, am, "released DOWN",  "dOff2", () -> inputDown  = false);
        bind(im, am, "pressed LEFT",   "lOn2",  () -> inputLeft  = true);
        bind(im, am, "released LEFT",  "lOff2", () -> inputLeft  = false);
        bind(im, am, "pressed RIGHT",  "rOn2",  () -> inputRight = true);
        bind(im, am, "released RIGHT", "rOff2", () -> inputRight = false);

        // Freeze-ray: F or Space
        bind(im, am, "pressed F",     "frz",   this::fireFreeze);
        bind(im, am, "pressed SPACE", "frz2",  this::fireFreeze);

        // Quit
        bind(im, am, "pressed ESCAPE", "quit",  this::quit);
        bind(im, am, "pressed Q",      "quit2", this::quit);
    }

    private void bind(InputMap im, ActionMap am, String keystroke, String name, Runnable action) {
        im.put(KeyStroke.getKeyStroke(keystroke), name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void quit() {
        client.closeEverything();
        System.exit(0);
    }

    // -------------------------------------------------------------------------
    // Freeze-ray fire
    // -------------------------------------------------------------------------

    /**
     * Called when the server confirms a FREEZE_EVENT for any player (including us).
     * Message format: FREEZE_EVENT|attackerId|targetId
     */
    void onFreezeEvent(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 3) return;
        try {
            int attackerId = Integer.parseInt(parts[1]);
            int targetId   = Integer.parseInt(parts[2]);
            NetworkGameModel.PlayerSnapshot attacker = model.players.get(attackerId);
            NetworkGameModel.PlayerSnapshot target   = model.players.get(targetId);
            if (attacker == null || target == null) return;
            double ax = (attackerId == myPlayerId && initialized) ? localX : attacker.x;
            double ay = (attackerId == myPlayerId && initialized) ? localY : attacker.y;
            SwingUtilities.invokeLater(() ->
                freezeRayEffect = new FreezeRayEffect(ax, ay, target.x, target.y));
        } catch (NumberFormatException ignored) {}
    }

    private void fireFreeze() {
        NetworkGameModel.PlayerSnapshot me = model.players.get(myPlayerId);
        if (me == null || !me.hasWeapon || me.frozen) return;

        // Target the nearest non-frozen enemy
        double bestDist = Double.MAX_VALUE;
        int    bestId   = -1;
        for (NetworkGameModel.PlayerSnapshot other : model.players.values()) {
            if (other.id == myPlayerId || other.frozen) continue;
            double dist = Math.hypot(other.x - localX, other.y - localY);
            if (dist < bestDist) {
                bestDist = dist;
                bestId   = other.id;
            }
        }
        if (bestId != -1) {
            NetworkGameModel.PlayerSnapshot target = model.players.get(bestId);
            if (target != null) {
                freezeRayEffect = new FreezeRayEffect(localX, localY, target.x, target.y);
            }
            client.sendFreeze(bestId);
        }
    }

    // -------------------------------------------------------------------------
    // Client-side position prediction for the local player
    // -------------------------------------------------------------------------

    private void updateLocalPosition(double dt) {
        // Server issued RESET — snap position back to the new spawn point
        if (model.roundReset) {
            model.roundReset = false;
            initialized      = false;
            freezeRayEffect  = null;
        }

        NetworkGameModel.PlayerSnapshot me = model.players.get(myPlayerId);

        // Wait until the server has told us where we are
        if (!initialized) {
            if (me == null) return;
            localX      = me.x;
            localY      = me.y;
            initialized = true;
        }

        // Frozen players cannot move
        if (me != null && me.frozen) return;

        double dx = 0, dy = 0;
        if (inputUp)    dy -= 1;
        if (inputDown)  dy += 1;
        if (inputLeft)  dx -= 1;
        if (inputRight) dx += 1;

        if (dx == 0 && dy == 0) return;

        // Normalize diagonal movement to keep speed consistent
        double mag = Math.hypot(dx, dy);
        dx /= mag;
        dy /= mag;

        localX = clamp(localX + dx * PLAYER_SPEED * dt, 0, model.mapWidth);
        localY = clamp(localY + dy * PLAYER_SPEED * dt, 0, model.mapHeight);

        client.sendMove(localX, localY);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBackground(g2);
        drawZones(g2);
        drawItems(g2);
        drawPlayers(g2);
        drawFreezeRay(g2);
        drawHUD(g2);

        if (model.gameOver) drawGameOver(g2);
    }

    // --- Background ---

    private void drawBackground(Graphics2D g2) {
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // Thin red tactical grid
        g2.setColor(GRID_COLOR);
        g2.setStroke(new BasicStroke(0.5f));
        int grid = 40;
        for (int x = 0; x <= getWidth();  x += grid) g2.drawLine(x, 0, x, getHeight());
        for (int y = 0; y <= getHeight(); y += grid) g2.drawLine(0, y, getWidth(),  y);

        // Corner reticle brackets
        drawCornerReticle(g2, 10, 10, 18, RED_ACCENT);
        drawCornerReticle(g2, getWidth() - 10, 10, 18, RED_ACCENT);
        drawCornerReticle(g2, 10, getHeight() - 10, 18, RED_ACCENT);
        drawCornerReticle(g2, getWidth() - 10, getHeight() - 10, 18, RED_ACCENT);

        // Diagnostic readout in bottom-left corner
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        g2.drawString("SYS:ONLINE  PROTO:UDP+TCP", 28, getHeight() - 28);
        g2.drawString("MAP:" + model.mapWidth + "x" + model.mapHeight
                + "  PLAYERS:" + model.players.size(), 28, getHeight() - 18);
    }

    /** Draws a small L-bracket reticle at (cx,cy). dir adapts each corner. */
    private void drawCornerReticle(Graphics2D g2, int cx, int cy, int sz,  Color c) {
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.5f));
        int sx = (cx < getWidth()  / 2) ?  1 : -1;
        int sy = (cy < getHeight() / 2) ?  1 : -1;
        g2.drawLine(cx, cy, cx + sx * sz, cy);
        g2.drawLine(cx, cy, cx, cy + sy * sz);
    }

    // --- Zones ---

    private void drawZones(Graphics2D g2) {
        for (NetworkGameModel.ZoneSnapshot z : model.zones) {
            Rectangle r = z.rect;

            // Fill + border colours based on state
            Color fill, border;
            if (z.contested) {
                fill   = new Color(60, 10,  5);
                border = AMBER;
            } else if (z.ownerId != -1 && z.graceRemSec > 0) {
                fill   = new Color(50, 35,  0);
                border = AMBER;
            } else if (z.ownerId != -1) {
                Color pc = playerColor(z.ownerId);
                fill   = new Color(pc.getRed() / 6, pc.getGreen() / 6, pc.getBlue() / 6);
                border = pc;
            } else {
                fill   = new Color(10, 0, 0);
                border = DIM_RED;
            }

            // Sharp-cornered fill + border
            g2.setColor(fill);
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(r.x, r.y, r.width, r.height);

            // Corner bracket decorators inside the zone
            int bk = 8;
            g2.setStroke(new BasicStroke(1.5f));
            // top-left
            g2.drawLine(r.x + 1, r.y + 1, r.x + 1 + bk, r.y + 1);
            g2.drawLine(r.x + 1, r.y + 1, r.x + 1, r.y + 1 + bk);
            // top-right
            g2.drawLine(r.x + r.width - 1, r.y + 1, r.x + r.width - 1 - bk, r.y + 1);
            g2.drawLine(r.x + r.width - 1, r.y + 1, r.x + r.width - 1, r.y + 1 + bk);
            // bottom-left
            g2.drawLine(r.x + 1, r.y + r.height - 1, r.x + 1 + bk, r.y + r.height - 1);
            g2.drawLine(r.x + 1, r.y + r.height - 1, r.x + 1, r.y + r.height - 1 - bk);
            // bottom-right
            g2.drawLine(r.x + r.width - 1, r.y + r.height - 1, r.x + r.width - 1 - bk, r.y + r.height - 1);
            g2.drawLine(r.x + r.width - 1, r.y + r.height - 1, r.x + r.width - 1, r.y + r.height - 1 - bk);

            // Zone ID label — top-left
            g2.setFont(fontMicro);
            g2.setColor(DIM_RED);
            g2.drawString("Z-0" + z.id, r.x + 12, r.y + 13);

            // State label — centred
            String stateText;
            Color  stateColor;
            if (z.contested) {
                stateText  = "CONTESTED";
                stateColor = AMBER;
            } else if (z.ownerId != -1 && z.graceRemSec > 0) {
                stateText  = String.format("GRACE %.1fs", z.graceRemSec);
                stateColor = AMBER;
            } else if (z.ownerId != -1) {
                NetworkGameModel.PlayerSnapshot owner = model.players.get(z.ownerId);
                stateText  = (owner != null) ? owner.name.toUpperCase() : "ID-" + z.ownerId;
                stateColor = playerColor(z.ownerId);
            } else {
                stateText  = "UNCLAIMED";
                stateColor = DIM_RED;
            }
            g2.setFont(fontSmall);
            g2.setColor(stateColor);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(stateText,
                    r.x + (r.width  - fm.stringWidth(stateText)) / 2,
                    r.y + (r.height + fm.getAscent()) / 2 - 2);

            // Capture progress bar — red fill at bottom of zone
            if (z.captureProgress > 0) {
                int barW = (int) (r.width * z.captureProgress);
                g2.setColor(new Color(214, 7, 29, 160));
                g2.fillRect(r.x, r.y + r.height - 6, barW, 6);
                g2.setColor(RED_ACCENT);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRect(r.x, r.y + r.height - 6, r.width, 6);
            }
        }
    }

    // --- Items ---

    private void drawItems(Graphics2D g2) {
        for (NetworkGameModel.ItemSnapshot item : model.items) {
            int ix = (int) item.x, iy = (int) item.y;
            if ("energy".equals(item.kind)) {
                // Energy pickup: amber diamond
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
                // Freeze pickup: red square with crosshair
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

    // --- Players ---

    private void drawPlayers(Graphics2D g2) {
        for (NetworkGameModel.PlayerSnapshot ps : model.players.values()) {
            boolean isMe = (ps.id == myPlayerId);
            int px = (int) (isMe && initialized ? localX : ps.x);
            int py = (int) (isMe && initialized ? localY : ps.y);
            int r  = PLAYER_RADIUS;
            // Local player is always drawn in red; others use the colour table
            Color pc = isMe ? RED_ACCENT : playerColor(ps.id);

            // Frozen dim overlay (dark red pulse instead of blue)
            if (ps.frozen) {
                g2.setColor(new Color(60, 0, 0, 80));
                g2.fillOval(px - r - 7, py - r - 7, (r + 7) * 2, (r + 7) * 2);
            }

            // Tactical diamond shape instead of circle
            Color fillColor = ps.frozen ? pc.darker().darker() : new Color(pc.getRed() / 4, pc.getGreen() / 4, pc.getBlue() / 4);
            int[] xp = { px, px + r, px, px - r };
            int[] yp = { py - r, py, py + r, py };
            g2.setColor(fillColor);
            g2.fillPolygon(xp, yp, 4);
            g2.setColor(ps.frozen ? DIM_RED : pc);
            g2.setStroke(new BasicStroke(isMe ? 2.5f : 1.5f));
            g2.drawPolygon(xp, yp, 4);

            // Crosshair tick marks on the local player
            if (isMe) {
                g2.setColor(RED_ACCENT);
                g2.setStroke(new BasicStroke(1f));
                int tick = 5;
                g2.drawLine(px, py - r - 2, px, py - r - 2 - tick);  // top
                g2.drawLine(px, py + r + 2, px, py + r + 2 + tick);  // bottom
                g2.drawLine(px - r - 2, py, px - r - 2 - tick, py);  // left
                g2.drawLine(px + r + 2, py, px + r + 2 + tick, py);  // right
            }

            // Name label above
            g2.setFont(fontSmall);
            g2.setColor(isMe ? RED_ACCENT : new Color(200, 200, 200));
            FontMetrics fm = g2.getFontMetrics();
            String nameLabel = ps.name.toUpperCase();
            g2.drawString(nameLabel, px - fm.stringWidth(nameLabel) / 2, py - r - 8);

            // Weapon indicator
            if (ps.hasWeapon) {
                g2.setFont(fontMicro);
                g2.setColor(RED_ACCENT);
                g2.drawString("[FRZ]", px + r + 4, py + 4);
            }

            // Freeze timer bar
            if (ps.frozen && ps.frozenRemSec > 0) {
                int barW   = r * 2;
                int filled = (int) (barW * Math.min(ps.frozenRemSec / 4.0, 1.0));
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

    // --- HUD ---

    private void drawHUD(Graphics2D g2) {
        // Black top bar with red accent bottom line
        g2.setColor(new Color(0, 0, 0, 230));
        g2.fillRect(0, 0, getWidth(), HUD_HEIGHT);
        g2.setColor(RED_ACCENT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(0, HUD_HEIGHT, getWidth(), HUD_HEIGHT);
        // Thin secondary line for depth
        g2.setColor(DIM_RED);
        g2.setStroke(new BasicStroke(0.5f));
        g2.drawLine(0, HUD_HEIGHT + 2, getWidth(), HUD_HEIGHT + 2);

        // Title (top-left)
        g2.setFont(fontTitle);
        g2.setColor(RED_ACCENT);
        g2.drawString("CHRONO//ARENA", 14, 32);
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        g2.drawString("TACTICAL NET v2.0", 14, 46);

        // Timer (top-centre)
        double t  = model.timeLeftSec;
        int    mm = (int) (t / 60), ss = (int) (t % 60);
        g2.setFont(fontBig);
        Color timerColor = (t < 30) ? RED_ACCENT : Color.WHITE;
        String timerStr = String.format("%02d:%02d", mm, ss);
        FontMetrics fm = g2.getFontMetrics();
        int tx = (getWidth() - fm.stringWidth(timerStr)) / 2;
        g2.setColor(timerColor);
        g2.drawString(timerStr, tx, 36);
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        String timeLabel = "TIME REMAINING";
        fm = g2.getFontMetrics();
        g2.drawString(timeLabel, (getWidth() - fm.stringWidth(timeLabel)) / 2, 46);

        // Score boxes (top-right)
        List<NetworkGameModel.PlayerSnapshot> sorted = new ArrayList<>(model.players.values());
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        int bx = getWidth() - 10;
        for (NetworkGameModel.PlayerSnapshot ps : sorted) {
            g2.setFont(fontSmall);
            fm = g2.getFontMetrics();
            String label = ps.name.toUpperCase() + "  " + (int) ps.score;
            int boxW = fm.stringWidth(label) + 14;
            bx -= boxW;
            boolean isMe = (ps.id == myPlayerId);
            Color pc = isMe ? RED_ACCENT : playerColor(ps.id);
            // Box: black bg, coloured border
            g2.setColor(Color.BLACK);
            g2.fillRect(bx, 10, boxW, 30);
            g2.setColor(pc);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(bx, 10, boxW, 30);
            // Left accent bar for local player
            if (isMe) {
                g2.setColor(RED_ACCENT);
                g2.fillRect(bx, 10, 3, 30);
            }
            g2.setFont(fontSmall);
            g2.setColor(isMe ? Color.WHITE : pc);
            g2.drawString(label, bx + 8, 30);
            bx -= 6;
        }

        // Controls hint — bottom-right
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        String hint = "WASD:MOVE  F/SPC:FREEZE  ESC:QUIT";
        fm = g2.getFontMetrics();
        g2.drawString(hint, getWidth() - fm.stringWidth(hint) - 10, getHeight() - 8);
    }

    // --- Freeze-ray beam ---

    private void drawFreezeRay(Graphics2D g2) {
        if (freezeRayEffect == null || !freezeRayEffect.alive()) return;

        float t  = freezeRayEffect.progress(); // 1.0 → 0.0 as it fades
        int   sx = (int) freezeRayEffect.startX, sy = (int) freezeRayEffect.startY;
        int   ex = (int) freezeRayEffect.endX,   ey = (int) freezeRayEffect.endY;

        // Outer red glow
        g2.setColor(new Color(214, 7, 29, (int)(50 * t)));
        g2.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(sx, sy, ex, ey);

        // Mid glow
        g2.setColor(new Color(255, 60, 60, (int)(120 * t)));
        g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(sx, sy, ex, ey);

        // Bright core
        g2.setColor(new Color(255, 200, 200, (int)(230 * t)));
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(sx, sy, ex, ey);

        // Impact burst — diamond shape at target
        int r = (int)(20 * t);
        if (r > 1) {
            int[] xp = { ex, ex + r, ex, ex - r };
            int[] yp = { ey - r, ey, ey + r, ey };
            g2.setColor(new Color(214, 7, 29, (int)(100 * t)));
            g2.fillPolygon(xp, yp, 4);
            g2.setColor(new Color(255, 100, 100, (int)(200 * t)));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawPolygon(xp, yp, 4);
        }
    }

    // --- Game Over overlay ---

    private void drawGameOver(Graphics2D g2) {
        // Heavy black scanline overlay
        g2.setColor(new Color(0, 0, 0, 200));
        g2.fillRect(0, 0, getWidth(), getHeight());

        int cx = getWidth() / 2, cy = getHeight() / 2;

        // Bordered panel
        int pw = 480, ph = 160;
        int px = cx - pw / 2, py = cy - ph / 2;
        g2.setColor(Color.BLACK);
        g2.fillRect(px, py, pw, ph);
        g2.setColor(RED_ACCENT);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(px, py, pw, ph);
        // Corner brackets on the panel
        int bk = 12;
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(px,      py,      px + bk, py);       g2.drawLine(px, py, px, py + bk);
        g2.drawLine(px + pw, py,      px + pw - bk, py);  g2.drawLine(px + pw, py, px + pw, py + bk);
        g2.drawLine(px,      py + ph, px + bk, py + ph);  g2.drawLine(px, py + ph, px, py + ph - bk);
        g2.drawLine(px + pw, py + ph, px + pw - bk, py + ph); g2.drawLine(px + pw, py + ph, px + pw, py + ph - bk);

        // "SIGNAL LOST" / GAME OVER heading
        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 42));
        g2.setColor(RED_ACCENT);
        FontMetrics fm = g2.getFontMetrics();
        String heading = "// GAME OVER //";
        g2.drawString(heading, cx - fm.stringWidth(heading) / 2, cy - 14);

        // Winner line
        if (!model.winnerLine.isEmpty()) {
            g2.setFont(fontMed);
            g2.setColor(Color.WHITE);
            fm = g2.getFontMetrics();
            String wl = model.winnerLine.toUpperCase();
            g2.drawString(wl, cx - fm.stringWidth(wl) / 2, cy + 22);
        }

        // Instruction
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        String sub = "PRESS ESC / Q TO DISCONNECT";
        fm = g2.getFontMetrics();
        g2.drawString(sub, cx - fm.stringWidth(sub) / 2, cy + 48);

        // Bottom status bar of the panel
        g2.setFont(fontMicro);
        g2.setColor(DIM_RED);
        g2.drawString("STATUS: ROUND COMPLETE", px + 10, py + ph - 8);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Color playerColor(int playerId) {
        return PLAYER_COLORS[(Math.abs(playerId) - 1) % PLAYER_COLORS.length];
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
