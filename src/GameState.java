import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.FileWriter;
import java.io.IOException;

// @KFrancis05, help from Claude.ai

public class GameState {
    public static final GameState INSTANCE = new GameState();

    // --- Tunable constants ---
    public static final double ROUND_DURATION       = 180.0; // seconds
    public static final double ZONE_CAPTURE_TIME    = 3.0;   // seconds to capture uncontested
    public static final double ZONE_CAPTURE_CONTEST = 6.0;   // seconds to capture when contested
    public static final double GRACE_TIMER          = 5.0;   // seconds before zone is lost after leaving
    public static final double FREEZE_DURATION      = 4.0;   // seconds target stays frozen
    public static final double FREEZE_COOLDOWN      = 8.0;   // seconds before attacker can freeze again
    public static final double FREEZE_WEAPON_TTL    = 30.0;  // seconds freeze weapon lasts after pickup
    public static final double ZONE_POINTS_PER_SEC  = 5.0;
    public static final double ENERGY_ITEM_POINTS   = 25.0;
    public static final double FREEZE_PENALTY       = 10.0;
    public static final double ITEM_PICKUP_RADIUS   = 30.0;
    public static final int    MAP_WIDTH            = 900;
    public static final int    MAP_HEIGHT           = 650;
    public static final int    MAX_ITEMS            = 4;

    // Authoritative state
    private final ConcurrentHashMap<Integer, Player> players   = new ConcurrentHashMap<>();
    private final List<Zone>                         zones     = new ArrayList<>();
    private final ConcurrentHashMap<Integer, Item>   items     = new ConcurrentHashMap<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);
    private final AtomicInteger nextItemId   = new AtomicInteger(1);

    // Grace timers: zoneId -> GraceTimer
    private final ConcurrentHashMap<Integer, GraceTimer> graceTimers = new ConcurrentHashMap<>();

    // UDP action queue — drained each tick
    public final ConcurrentLinkedQueue<String> actionQueue = new ConcurrentLinkedQueue<>();

    private double  timeLeftSec = ROUND_DURATION;
    private boolean gameOver    = false;

    private final Random rng = new Random();

    private static class GraceTimer {
        int    playerId;
        double secondsLeft;
        GraceTimer(int pid, double sec) { this.playerId = pid; this.secondsLeft = sec; }
    }

    private GameState() {
        initZones();
        spawnItem("energy");
        spawnItem("freeze");
    }

    // -------------------------------------------------------------------------
    // Player lifecycle
    // -------------------------------------------------------------------------

    /** Called when a client successfully sends JOIN. Returns assigned player ID. */
    public synchronized int addPlayer(String name) {
        int id = nextPlayerId.getAndIncrement();
        double startX = 100 + (id % 4) * 180;
        double startY = 100 + (id % 3) * 180;
        players.put(id, new Player(id, false, name, startX, startY));
        return id;
    }

    /** Called when a client disconnects or is killed. */
    public synchronized void removePlayer(int id) {
        players.remove(id);
        // Release zones owned by this player
        for (Zone z : zones) {
            if (z.ownerId == id) {
                z.ownerId   = -1;
                z.contested = false;
            }
        }
        // Cancel any grace timers for this player
        graceTimers.entrySet().removeIf(e -> e.getValue().playerId == id);
    }

    // -------------------------------------------------------------------------
    // Main tick — called by server game loop every ~50ms
    // -------------------------------------------------------------------------

    public synchronized void tick(double dt) {
        if (gameOver) return;

        timeLeftSec -= dt;
        if (timeLeftSec <= 0) {
            timeLeftSec = 0;
            gameOver    = true;
            return;
        }

        drainActionQueue();
        tickZoneCaptureAndItems(dt);
        tickGraceTimers(dt);
        tickFreezeTimers();
        awardZonePoints(dt);
        maybeSpawnItem();
    }

    // -------------------------------------------------------------------------
    // Action queue
    // -------------------------------------------------------------------------

    private void drainActionQueue() {
        String action;
        while ((action = actionQueue.poll()) != null) {
            applyAction(action);
        }
    }

    private void applyAction(String raw) {
        // MOVE|seqNum|playerId|x|y
        // ACTION|seqNum|playerId|FREEZE|targetId
        String[] parts = raw.split("\\|");
        if (parts.length < 3) return;

        int playerId;
        try { playerId = Integer.parseInt(parts[2]); }
        catch (NumberFormatException e) { return; }

        Player p = players.get(playerId);
        if (p == null || isFrozen(p)) return;

        String type = parts[0];

        if ("MOVE".equals(type) && parts.length >= 5) {
            try {
                double x = Double.parseDouble(parts[3]);
                double y = Double.parseDouble(parts[4]);
                p.x = clamp(x, 0, MAP_WIDTH);
                p.y = clamp(y, 0, MAP_HEIGHT);
            } catch (NumberFormatException ignored) {}

        } else if ("ACTION".equals(type) && parts.length >= 5 && "FREEZE".equals(parts[3])) {
            applyFreezeAction(p, parts);
        }
    }

    private void applyFreezeAction(Player attacker, String[] parts) {
        // Must have the weapon and not be on cooldown
        double now = nowSec();
        if (attacker.freezeRayUntilSec <= now) return; // no weapon
        if (attacker.freezeCooldownUntilSec > now) return; // on cooldown

        int targetId;
        try { targetId = Integer.parseInt(parts[4]); }
        catch (NumberFormatException e) { return; }

        Player target = players.get(targetId);
        if (target == null || isFrozen(target)) return;

        target.frozenUntilSec     = now + FREEZE_DURATION;
        target.score              = Math.max(0, target.score - FREEZE_PENALTY);
        attacker.freezeRayUntilSec    = 0;              // weapon consumed
        attacker.freezeCooldownUntilSec = now + FREEZE_COOLDOWN;
    }

    // -------------------------------------------------------------------------
    // Zone capture + item collection
    // -------------------------------------------------------------------------

    private void tickZoneCaptureAndItems(double dt) {
        // --- Zones ---
        for (Zone z : zones) {
            List<Player> inZone = new ArrayList<>();
            for (Player p : players.values()) {
                if (!isFrozen(p) && z.rect.contains((int) p.x, (int) p.y)) {
                    inZone.add(p);
                }
            }

            if (inZone.isEmpty()) {
                // Nobody in zone — start grace timer if owned
                if (z.ownerId != -1 && !graceTimers.containsKey(z.id)) {
                    graceTimers.put(z.id, new GraceTimer(z.ownerId, GRACE_TIMER));
                }
                z.contested = false;

            } else if (inZone.size() == 1) {
                Player p = inZone.get(0);
                z.contested = false;

                if (z.ownerId == p.id) {
                    // Owner is back — cancel grace timer
                    graceTimers.remove(z.id);
                } else {
                    // Different player capturing
                    graceTimers.remove(z.id);
                    p.captureProgress += dt / ZONE_CAPTURE_TIME;
                    if (p.captureProgress >= 1.0) {
                        z.ownerId         = p.id;
                        p.captureProgress = 0;
                    }
                }

            } else {
                // Multiple players — contested
                // Fairness rule: lowest player ID captures (deterministic tiebreak)
                z.contested = true;
                graceTimers.remove(z.id);
                inZone.sort(Comparator.comparingInt(pl -> pl.id));
                Player winner = inZone.get(0);

                if (z.ownerId != winner.id) {
                    winner.captureProgress += dt / ZONE_CAPTURE_CONTEST;
                    if (winner.captureProgress >= 1.0) {
                        z.ownerId              = winner.id;
                        winner.captureProgress = 0;
                    }
                }
            }
        }

        // --- Items ---
        for (Iterator<Item> it = items.values().iterator(); it.hasNext(); ) {
            Item item = it.next();

            // Closest non-frozen player within pickup radius collects (deterministic)
            Player collector  = null;
            double bestDist   = ITEM_PICKUP_RADIUS;

            for (Player p : players.values()) {
                if (isFrozen(p)) continue;
                double dist = Math.hypot(p.x - item.x, p.y - item.y);
                if (dist < bestDist || (dist == bestDist && (collector == null || p.id < collector.id))) {
                    bestDist  = dist;
                    collector = p;
                }
            }

            if (collector != null) {
                if ("energy".equals(item.kind)) {
                    collector.score += ENERGY_ITEM_POINTS;
                } else if ("freeze".equals(item.kind)) {
                    collector.freezeRayUntilSec = nowSec() + FREEZE_WEAPON_TTL;
                }
                it.remove();
            }
        }
    }

    private void tickGraceTimers(double dt) {
        for (Iterator<Map.Entry<Integer, GraceTimer>> it = graceTimers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, GraceTimer> entry = it.next();
            GraceTimer gt = entry.getValue();
            gt.secondsLeft -= dt;
            if (gt.secondsLeft <= 0) {
                int zoneId = entry.getKey();
                for (Zone z : zones) {
                    if (z.id == zoneId) {
                        z.ownerId   = -1;
                        z.contested = false;
                        break;
                    }
                }
                it.remove();
            }
        }
    }

    private void tickFreezeTimers() {
        double now = nowSec();
        for (Player p : players.values()) {
            if (p.frozenUntilSec > 0 && now > p.frozenUntilSec) {
                p.frozenUntilSec = 0;
            }
            if (p.freezeCooldownUntilSec > 0 && now > p.freezeCooldownUntilSec) {
                p.freezeCooldownUntilSec = 0;
            }
            // Weapon expiry
            if (p.freezeRayUntilSec > 0 && now > p.freezeRayUntilSec) {
                p.freezeRayUntilSec = 0;
            }
        }
    }

    private void awardZonePoints(double dt) {
        for (Zone z : zones) {
            if (z.ownerId == -1 || z.contested) continue;
            Player owner = players.get(z.ownerId);
            if (owner != null) {
                owner.score += ZONE_POINTS_PER_SEC * dt;
            }
        }
    }

    private void maybeSpawnItem() {
        if (items.size() < MAX_ITEMS && rng.nextDouble() < 0.005) {
            spawnItem(rng.nextBoolean() ? "energy" : "freeze");
        }
    }

    // -------------------------------------------------------------------------
    // Serialization (wire format)
    // -------------------------------------------------------------------------

    /**
     * Produces a STATE message:
     * STATE|timeLeft|p1id,p1name,p1x,p1y,p1score,p1frozen|...|ZONES|z1id,z1owner,z1contested|...|ITEMS|itemid,kind,x,y|...
     */
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder("STATE|");
        sb.append(String.format("%.1f", timeLeftSec)).append("|");

        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort(Comparator.comparingInt(p -> p.id));
        for (Player p : sorted) {
            sb.append(p.id).append(",")
              .append(p.name).append(",")
              .append(String.format("%.1f", p.x)).append(",")
              .append(String.format("%.1f", p.y)).append(",")
              .append(String.format("%.1f", p.score)).append(",")
              .append(isFrozen(p) ? 1 : 0).append("|");
        }

        sb.append("ZONES|");
        for (Zone z : zones) {
            sb.append(z.id).append(",")
              .append(z.ownerId).append(",")
              .append(z.contested ? 1 : 0).append("|");
        }

        sb.append("ITEMS|");
        for (Item item : items.values()) {
            sb.append(item.id).append(",")
              .append(item.kind).append(",")
              .append(String.format("%.1f", item.x)).append(",")
              .append(String.format("%.1f", item.y)).append("|");
        }

        return sb.toString();
    }

    /**
     * Produces a GAMEOVER message:
     * GAMEOVER|winnerId,winnerName,winnerScore
     */
    public synchronized String serializeGameOver() {
        Player winner = players.values().stream()
                .max(Comparator.comparingDouble(p -> p.score))
                .orElse(null);
        if (winner == null) return "GAMEOVER|-1,unknown,0";
        return "GAMEOVER|" + winner.id + "," + winner.name + "," + (int) winner.score;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initZones() {
        int margin = 60, zw = 160, zh = 120;
        zones.add(new Zone(1, new Rectangle(margin,             margin,              zw, zh)));
        zones.add(new Zone(2, new Rectangle(MAP_WIDTH  - margin - zw, margin,              zw, zh)));
        zones.add(new Zone(3, new Rectangle(margin,             MAP_HEIGHT - margin - zh, zw, zh)));
        zones.add(new Zone(4, new Rectangle(MAP_WIDTH  - margin - zw, MAP_HEIGHT - margin - zh, zw, zh)));
    }

    private void spawnItem(String kind) {
        int id = nextItemId.getAndIncrement();
        double x = 100 + rng.nextDouble() * (MAP_WIDTH  - 200);
        double y = 100 + rng.nextDouble() * (MAP_HEIGHT - 200);
        items.put(id, new Item(id, kind, x, y));
    }

    public boolean isGameOver()  { return gameOver; }
    public double  getTimeLeft() { return timeLeftSec; }
    public ConcurrentHashMap<Integer, Player> getPlayers() { return players; }
    public List<Zone> getZones() { return zones; }

    private boolean isFrozen(Player p) {
        return p.frozenUntilSec > nowSec();
    }

    private double nowSec() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static void logError(String context, Exception e) {
        try (FileWriter fw = new FileWriter("error.log", true)) {
            fw.write("[" + new Date() + "] " + context + ": " + e.getMessage() + "\n");
        } catch (IOException ignored) {}
    }
}
