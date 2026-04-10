import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

// @KFrancis05, help from Claude.ai

public class GameState {
    public static final GameState INSTANCE = new GameState();

    // -------------------------------------------------------------------------
    // Configurable constants — set via configure(Properties) before game starts.
    // All durations in seconds unless noted.
    // -------------------------------------------------------------------------
    private static final double PLAYER_SPEED = 200.0;  // pixels per second
    private double ROUND_DURATION          = 180.0;
    private double ZONE_CAPTURE_TIME       = 3.0;
    private double ZONE_CAPTURE_CONTEST    = 6.0;
    private double GRACE_TIMER             = 5.0;
    private double FREEZE_DURATION         = 4.0;
    private double FREEZE_COOLDOWN         = 8.0;
    private double FREEZE_WEAPON_TTL       = 30.0;
    private double ZONE_POINTS_PER_SEC     = 5.0;
    private double ENERGY_ITEM_POINTS      = 25.0;
    private double FREEZE_PENALTY          = 10.0;
    private double ITEM_PICKUP_RADIUS      = 30.0;
    public  int    MAP_WIDTH               = 900;
    public  int    MAP_HEIGHT              = 650;
    public static final int HUD_HEIGHT     = 54;   // top boundary — matches NetworkGamePanel
    private int    MAX_ITEMS               = 4;
    private int    ZONE_COUNT              = 4;
    private int    ITEM_SPAWN_INTERVAL_TICKS = 20; // every N ticks, try to spawn an item

    // -------------------------------------------------------------------------
    // Authoritative state
    // -------------------------------------------------------------------------
    private final ConcurrentHashMap<Integer, Player> players   = new ConcurrentHashMap<>();
    private final List<Zone>                         zones     = new ArrayList<>();
    private final ConcurrentHashMap<Integer, Item>   items     = new ConcurrentHashMap<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);
    private final AtomicInteger nextItemId   = new AtomicInteger(1);

    // Grace timers: zoneId -> GraceTimer
    private final ConcurrentHashMap<Integer, GraceTimer> graceTimers = new ConcurrentHashMap<>();

    // UDP action queue — drained each tick by the game loop
    public final ConcurrentLinkedQueue<String> actionQueue = new ConcurrentLinkedQueue<>();

    // Events to broadcast to all clients after each tick (e.g. FREEZE_EVENT)
    public final ConcurrentLinkedQueue<String> pendingBroadcasts = new ConcurrentLinkedQueue<>();

    private double  timeLeftSec = 0;
    private boolean gameOver    = false;
    private int     tickCount   = 0;

    private final Random rng = new Random();

    private static class GraceTimer {
        int    playerId;
        double secondsLeft;
        GraceTimer(int pid, double sec) { this.playerId = pid; this.secondsLeft = sec; }
    }

    // Private constructor — callers use INSTANCE.
    // configure() must be called before the game loop starts.
    private GameState() {}

    // -------------------------------------------------------------------------
    // Configuration — call once from Server.main() after loading properties.
    // -------------------------------------------------------------------------

    public synchronized void configure(Properties props) {
        ROUND_DURATION            = Double.parseDouble(props.getProperty("round_duration_sec",        "180"));
        ZONE_CAPTURE_TIME         = Double.parseDouble(props.getProperty("zone_capture_sec",          "3"));
        ZONE_CAPTURE_CONTEST      = Double.parseDouble(props.getProperty("zone_capture_contest_sec",  "6"));
        GRACE_TIMER               = Double.parseDouble(props.getProperty("zone_grace_sec",            "5"));
        ZONE_POINTS_PER_SEC       = Double.parseDouble(props.getProperty("zone_points_per_sec",       "5"));
        FREEZE_DURATION           = Double.parseDouble(props.getProperty("freeze_duration_sec",       "4"));
        FREEZE_COOLDOWN           = Double.parseDouble(props.getProperty("freeze_cooldown_sec",       "8"));
        FREEZE_WEAPON_TTL         = Double.parseDouble(props.getProperty("freeze_weapon_ttl_sec",     "30"));
        FREEZE_PENALTY            = Double.parseDouble(props.getProperty("freeze_penalty_points",     "10"));
        ENERGY_ITEM_POINTS        = Double.parseDouble(props.getProperty("energy_item_points",        "25"));
        ITEM_PICKUP_RADIUS        = Double.parseDouble(props.getProperty("item_pickup_radius",        "30"));
        MAP_WIDTH                 = Integer.parseInt  (props.getProperty("map_width",                 "900"));
        MAP_HEIGHT                = Integer.parseInt  (props.getProperty("map_height",                "650"));
        MAX_ITEMS                 = Integer.parseInt  (props.getProperty("item_max_active",           "4"));
        ZONE_COUNT                = Integer.parseInt  (props.getProperty("zone_count",                "4"));
        ITEM_SPAWN_INTERVAL_TICKS = Integer.parseInt  (props.getProperty("item_spawn_interval_ticks", "20"));

        timeLeftSec = ROUND_DURATION;
        gameOver    = false;
        tickCount   = 0;

        // Clear any leftover state in case configure() is called more than once
        players.clear();
        items.clear();
        zones.clear();
        graceTimers.clear();
        actionQueue.clear();
        nextPlayerId.set(1);
        nextItemId.set(1);

        initZones();
        spawnItem("energy");
        spawnItem("freeze");
    }

    // -------------------------------------------------------------------------
    // Player lifecycle
    // -------------------------------------------------------------------------

    /** Called when a client successfully sends JOIN. Returns the assigned player ID. */
    public synchronized int addPlayer(String name) {
        int id = nextPlayerId.getAndIncrement();
        // Spread starting positions so players don't spawn on top of each other
        double startX = 150 + (id % 4) * 180;
        double startY = 150 + (id % 3) * 160;
        players.put(id, new Player(id, false, name, startX, startY));
        return id;
    }

    /**
     * Called when a client disconnects or is killed.
     * Owned zones enter the grace period rather than being released immediately,
     * consistent with the leave-zone rule.
     */
    public synchronized void removePlayer(int id) {
        players.remove(id);

        for (Zone z : zones) {
            if (z.ownerId == id) {
                // Start (or keep) the grace timer so the zone releases after the
                // usual delay.  If the player reconnects as a different ID the timer
                // will still expire and release the zone normally.
                if (!graceTimers.containsKey(z.id)) {
                    graceTimers.put(z.id, new GraceTimer(id, GRACE_TIMER));
                }
                z.contested = false;
            }
            // If the departing player was capturing this zone, reset that progress
            if (z.capturingPlayerId == id) {
                z.capturingPlayerId = -1;
                z.captureProgress   = 0;
            }
        }

        // Cancel any grace timers that this player's owned zones are waiting on
        // (their zones are now handled above; clean up any stale entries where the
        //  player was the grace-timer holder but no longer owns a zone)
        graceTimers.entrySet().removeIf(entry -> {
            // Keep timers for zones this player still "owned" (set just above)
            int zoneId = entry.getKey();
            boolean zoneStillHeld = zones.stream().anyMatch(z -> z.id == zoneId && z.ownerId == id);
            if (!zoneStillHeld && entry.getValue().playerId == id) {
                // This was an old grace timer for a zone that's already unclaimed
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Main tick — called by the server game loop every ~50 ms
    // -------------------------------------------------------------------------

    public synchronized void tick(double dt) {
        if (gameOver) return;

        timeLeftSec -= dt;
        if (timeLeftSec <= 0) {
            timeLeftSec = 0;
            gameOver    = true;
            return;
        }

        tickCount++;
        drainActionQueue();
        applyPlayerMovement(dt);
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

    /** Moves every non-frozen player by their current input direction × speed × dt. */
    private void applyPlayerMovement(double dt) {
        for (Player p : players.values()) {
            if (isFrozen(p)) { p.vx = 0; p.vy = 0; continue; }
            if (p.vx == 0 && p.vy == 0) continue;
            p.x = clamp(p.x + p.vx * PLAYER_SPEED * dt, 0, MAP_WIDTH);
            p.y = clamp(p.y + p.vy * PLAYER_SPEED * dt, HUD_HEIGHT, MAP_HEIGHT);
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
        if (p == null || isFrozen(p)) return;   // frozen players' actions are silently dropped

        String type = parts[0];

        if ("MOVE".equals(type) && parts.length >= 5) {
            try {
                double dx = Double.parseDouble(parts[3]);
                double dy = Double.parseDouble(parts[4]);
                // Normalise to unit vector so diagonal isn't faster
                double mag = Math.hypot(dx, dy);
                if (mag > 1e-6) { p.vx = dx / mag; p.vy = dy / mag; }
                else            { p.vx = 0;         p.vy = 0; }
            } catch (NumberFormatException ignored) {}

        } else if ("ACTION".equals(type) && parts.length >= 5 && "FREEZE".equals(parts[3])) {
            applyFreezeAction(p, parts);
        }
    }

    private void applyFreezeAction(Player attacker, String[] parts) {
        double now = nowSec();
        if (attacker.freezeRayUntilSec <= now)       return; // no weapon
        if (attacker.freezeCooldownUntilSec > now)    return; // on cooldown

        int targetId;
        try { targetId = Integer.parseInt(parts[4]); }
        catch (NumberFormatException e) { return; }

        Player target = players.get(targetId);
        if (target == null || isFrozen(target)) return;

        // Apply freeze
        target.frozenUntilSec          = now + FREEZE_DURATION;
        target.score                   = Math.max(0, target.score - FREEZE_PENALTY);

        // Notify all clients so they can render the beam
        pendingBroadcasts.add("FREEZE_EVENT|" + attacker.id + "|" + targetId);

        // Weapon is consumed; attacker enters cooldown
        attacker.freezeRayUntilSec      = 0;
        attacker.freezeCooldownUntilSec = now + FREEZE_COOLDOWN;

        // The frozen player's owned zones immediately enter grace period
        for (Zone z : zones) {
            if (z.ownerId == targetId && !graceTimers.containsKey(z.id)) {
                graceTimers.put(z.id, new GraceTimer(targetId, GRACE_TIMER));
            }
        }
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
                // Nobody present — start grace timer if zone is owned
                if (z.ownerId != -1 && !graceTimers.containsKey(z.id)) {
                    graceTimers.put(z.id, new GraceTimer(z.ownerId, GRACE_TIMER));
                }
                // Reset any in-progress capture attempt
                z.contested         = false;
                z.captureProgress   = 0;
                z.capturingPlayerId = -1;

            } else if (inZone.size() == 1) {
                Player p = inZone.get(0);
                z.contested = false;

                if (z.ownerId == p.id) {
                    // Owner returned — cancel grace timer
                    graceTimers.remove(z.id);
                    z.captureProgress   = 0;
                    z.capturingPlayerId = -1;
                } else {
                    // A non-owner is attempting to capture
                    graceTimers.remove(z.id);

                    // Reset progress if a different player started capturing
                    if (z.capturingPlayerId != p.id) {
                        z.capturingPlayerId = p.id;
                        z.captureProgress   = 0;
                    }

                    z.captureProgress += dt / ZONE_CAPTURE_TIME;
                    if (z.captureProgress >= 1.0) {
                        z.ownerId           = p.id;
                        z.captureProgress   = 0;
                        z.capturingPlayerId = -1;
                    }
                }

            } else {
                // Multiple players present — contested
                // Fairness rule: lowest player ID is the "winner" (deterministic tiebreak)
                z.contested = true;
                graceTimers.remove(z.id);

                inZone.sort(Comparator.comparingInt(pl -> pl.id));
                Player winner = inZone.get(0);

                if (z.ownerId == winner.id) {
                    // The winner already owns this zone; just hold it
                    z.captureProgress   = 0;
                    z.capturingPlayerId = -1;
                } else {
                    // Winner is capturing; reset progress if the capturing player changed
                    if (z.capturingPlayerId != winner.id) {
                        z.capturingPlayerId = winner.id;
                        z.captureProgress   = 0;
                    }
                    z.captureProgress += dt / ZONE_CAPTURE_CONTEST;
                    if (z.captureProgress >= 1.0) {
                        z.ownerId           = winner.id;
                        z.captureProgress   = 0;
                        z.capturingPlayerId = -1;
                        z.contested         = false;
                    }
                }
            }
        }

        // --- Items ---
        for (Iterator<Item> it = items.values().iterator(); it.hasNext(); ) {
            Item item = it.next();

            // Conflict resolution: closest non-frozen player within pickup radius wins.
            // Tiebreak by lowest player ID (deterministic).
            Player collector = null;
            double bestDist  = ITEM_PICKUP_RADIUS;

            for (Player p : players.values()) {
                if (isFrozen(p)) continue;
                double dist = Math.hypot(p.x - item.x, p.y - item.y);
                if (dist < bestDist || (dist == bestDist && collector != null && p.id < collector.id)) {
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
                        z.ownerId           = -1;
                        z.contested         = false;
                        z.captureProgress   = 0;
                        z.capturingPlayerId = -1;
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
            if (p.freezeRayUntilSec > 0 && now > p.freezeRayUntilSec) {
                p.freezeRayUntilSec = 0; // weapon expired
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
        if (items.size() < MAX_ITEMS && tickCount % ITEM_SPAWN_INTERVAL_TICKS == 0) {
            spawnItem(rng.nextBoolean() ? "energy" : "freeze");
        }
    }

    // -------------------------------------------------------------------------
    // Serialization  (wire format sent to every client each tick via TCP)
    //
    // STATE|timeLeft|
    //   id,name,x,y,score,frozen,hasWeapon,frozenRemSec|   (one segment per player)
    //   ...
    // ZONES|
    //   id,x,y,w,h,ownerId,contested,captureProgress,graceRemSec|  (one segment per zone)
    //   ...
    // ITEMS|
    //   id,kind,x,y|                                       (one segment per active item)
    //   ...
    // -------------------------------------------------------------------------

    public synchronized String serialize() {
        double now = nowSec();
        StringBuilder sb = new StringBuilder("STATE|");
        sb.append(String.format("%.1f", timeLeftSec)).append("|");

        List<Player> sorted = new ArrayList<>(players.values());
        sorted.sort(Comparator.comparingInt(p -> p.id));
        for (Player p : sorted) {
            double frozenRem = Math.max(0, p.frozenUntilSec - now);
            sb.append(p.id).append(",")
              .append(sanitizeName(p.name)).append(",")
              .append(String.format("%.1f", p.x)).append(",")
              .append(String.format("%.1f", p.y)).append(",")
              .append(String.format("%.1f", p.score)).append(",")
              .append(isFrozen(p) ? 1 : 0).append(",")
              .append(p.freezeRayUntilSec > now ? 1 : 0).append(",")
              .append(String.format("%.2f", frozenRem)).append("|");
        }

        sb.append("ZONES|");
        for (Zone z : zones) {
            double graceRem = 0;
            GraceTimer gt = graceTimers.get(z.id);
            if (gt != null) graceRem = Math.max(0, gt.secondsLeft);

            sb.append(z.id).append(",")
              .append(z.rect.x).append(",")
              .append(z.rect.y).append(",")
              .append(z.rect.width).append(",")
              .append(z.rect.height).append(",")
              .append(z.ownerId).append(",")
              .append(z.contested ? 1 : 0).append(",")
              .append(String.format("%.2f", z.captureProgress)).append(",")
              .append(String.format("%.2f", graceRem)).append("|");
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
     * GAMEOVER|winnerId,winnerName,winnerScore|p1id,p1name,p1score|p2id,...
     * Full scoreboard follows the winner announcement.
     */
    public synchronized String serializeGameOver() {
        List<Player> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> Double.compare(b.score, a.score));

        Player winner = ranked.isEmpty() ? null : ranked.get(0);
        StringBuilder sb = new StringBuilder("GAMEOVER|");
        if (winner == null) {
            sb.append("-1,unknown,0");
        } else {
            sb.append(winner.id).append(",")
              .append(sanitizeName(winner.name)).append(",")
              .append((int) winner.score);
        }

        for (Player p : ranked) {
            sb.append("|").append(p.id).append(",")
              .append(sanitizeName(p.name)).append(",")
              .append((int) p.score);
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Live reconfiguration — updates settings without resetting game state.
    // Call resetRound() afterwards for changes to take full effect.
    // -------------------------------------------------------------------------

    public synchronized void reconfigure(Properties props) {
        ROUND_DURATION            = Double.parseDouble(props.getProperty("round_duration_sec",        String.valueOf(ROUND_DURATION)));
        ZONE_CAPTURE_TIME         = Double.parseDouble(props.getProperty("zone_capture_sec",          String.valueOf(ZONE_CAPTURE_TIME)));
        ZONE_CAPTURE_CONTEST      = Double.parseDouble(props.getProperty("zone_capture_contest_sec",  String.valueOf(ZONE_CAPTURE_CONTEST)));
        GRACE_TIMER               = Double.parseDouble(props.getProperty("zone_grace_sec",            String.valueOf(GRACE_TIMER)));
        ZONE_POINTS_PER_SEC       = Double.parseDouble(props.getProperty("zone_points_per_sec",       String.valueOf(ZONE_POINTS_PER_SEC)));
        FREEZE_DURATION           = Double.parseDouble(props.getProperty("freeze_duration_sec",       String.valueOf(FREEZE_DURATION)));
        FREEZE_COOLDOWN           = Double.parseDouble(props.getProperty("freeze_cooldown_sec",       String.valueOf(FREEZE_COOLDOWN)));
        FREEZE_WEAPON_TTL         = Double.parseDouble(props.getProperty("freeze_weapon_ttl_sec",     String.valueOf(FREEZE_WEAPON_TTL)));
        FREEZE_PENALTY            = Double.parseDouble(props.getProperty("freeze_penalty_points",     String.valueOf(FREEZE_PENALTY)));
        ENERGY_ITEM_POINTS        = Double.parseDouble(props.getProperty("energy_item_points",        String.valueOf(ENERGY_ITEM_POINTS)));
        MAX_ITEMS                 = Integer.parseInt  (props.getProperty("item_max_active",           String.valueOf(MAX_ITEMS)));
        ITEM_SPAWN_INTERVAL_TICKS = Integer.parseInt  (props.getProperty("item_spawn_interval_ticks", String.valueOf(ITEM_SPAWN_INTERVAL_TICKS)));
        ZONE_COUNT                = Integer.parseInt  (props.getProperty("zone_count",                String.valueOf(ZONE_COUNT)));
    }

    /** Returns current config values so UI fields can be pre-populated. */
    public synchronized Properties getConfigAsProperties() {
        Properties p = new Properties();
        p.setProperty("round_duration_sec",        String.valueOf(ROUND_DURATION));
        p.setProperty("zone_capture_sec",          String.valueOf(ZONE_CAPTURE_TIME));
        p.setProperty("zone_capture_contest_sec",  String.valueOf(ZONE_CAPTURE_CONTEST));
        p.setProperty("zone_grace_sec",            String.valueOf(GRACE_TIMER));
        p.setProperty("zone_points_per_sec",       String.valueOf(ZONE_POINTS_PER_SEC));
        p.setProperty("freeze_duration_sec",       String.valueOf(FREEZE_DURATION));
        p.setProperty("freeze_cooldown_sec",       String.valueOf(FREEZE_COOLDOWN));
        p.setProperty("freeze_weapon_ttl_sec",     String.valueOf(FREEZE_WEAPON_TTL));
        p.setProperty("freeze_penalty_points",     String.valueOf(FREEZE_PENALTY));
        p.setProperty("energy_item_points",        String.valueOf(ENERGY_ITEM_POINTS));
        p.setProperty("item_max_active",           String.valueOf(MAX_ITEMS));
        p.setProperty("item_spawn_interval_ticks", String.valueOf(ITEM_SPAWN_INTERVAL_TICKS));
        p.setProperty("zone_count",                String.valueOf(ZONE_COUNT));
        return p;
    }

    // -------------------------------------------------------------------------
    // Round reset — keeps connected players, resets everything else
    // -------------------------------------------------------------------------

    /**
     * Resets the round without disconnecting anyone.
     * Player scores, positions, and power states are cleared; zones and items
     * are re-initialised with current config; the timer restarts from ROUND_DURATION.
     */
    public synchronized void resetRound() {
        timeLeftSec = ROUND_DURATION;
        gameOver    = false;
        tickCount   = 0;

        // Re-initialise zones so zone count / layout changes take effect
        initZones();

        // Clear timers and queued actions
        graceTimers.clear();
        actionQueue.clear();

        // Reset items and re-spawn the starting pair
        items.clear();
        nextItemId.set(1);
        spawnItem("energy");
        spawnItem("freeze");

        // Reset every connected player to their original spawn point
        int i = 0;
        for (Player p : players.values()) {
            p.x                      = 150 + (i % 4) * 180;
            p.y                      = 150 + (i % 3) * 160;
            p.score                  = 0;
            p.vx                     = 0;
            p.vy                     = 0;
            p.frozenUntilSec         = 0;
            p.freezeRayUntilSec      = 0;
            p.freezeCooldownUntilSec = 0;
            i++;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Places ZONE_COUNT zones at randomized positions using a grid layout.
     *
     * The playable area is divided into a cols×rows grid; each zone is assigned one
     * cell and placed at a random position within it.  This guarantees:
     *   - No overlapping zones
     *   - Even coverage of the map regardless of ZONE_COUNT
     *   - A different layout every round
     */
    private void initZones() {
        zones.clear();
        final int ZW     = 145;   // zone width
        final int ZH     = 105;   // zone height
        final int MARGIN = 50;    // distance from map edges
        final int PAD    = 12;    // minimum gap between zone and cell boundary

        // Playable area starts below the HUD
        int areaX = MARGIN;
        int areaY = HUD_HEIGHT + MARGIN;
        int areaW = MAP_WIDTH  - 2 * MARGIN;
        int areaH = MAP_HEIGHT - HUD_HEIGHT - 2 * MARGIN;

        // Grid dimensions: favour wider grids so zones spread horizontally
        int cols = (int) Math.ceil(Math.sqrt(ZONE_COUNT));
        int rows = (int) Math.ceil((double) ZONE_COUNT / cols);

        int cellW = areaW / cols;
        int cellH = areaH / rows;

        int id = 1;
        outer:
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (id > ZONE_COUNT) break outer;

                // Random top-left corner inside this cell, keeping the zone fully within bounds
                int minX = areaX + c * cellW + PAD;
                int maxX = areaX + c * cellW + cellW - ZW - PAD;
                int minY = areaY + r * cellH + PAD;
                int maxY = areaY + r * cellH + cellH - ZH - PAD;

                int x = (maxX > minX) ? minX + rng.nextInt(maxX - minX) : minX;
                int y = (maxY > minY) ? minY + rng.nextInt(maxY - minY) : minY;

                zones.add(new Zone(id++, new Rectangle(x, y, ZW, ZH)));
            }
        }
    }

    private void spawnItem(String kind) {
        int id = nextItemId.getAndIncrement();
        double x = 80 + rng.nextDouble() * (MAP_WIDTH  - 160);
        double y = 80 + rng.nextDouble() * (MAP_HEIGHT - 160);
        items.put(id, new Item(id, kind, x, y));
    }

    public boolean isGameOver()  { return gameOver; }
    public double  getTimeLeft() { return timeLeftSec; }
    public ConcurrentHashMap<Integer, Player> getPlayers() { return players; }
    public List<Zone> getZones() { return zones; }
    public ConcurrentHashMap<Integer, Item>   getItems()  { return items; }

    /** Returns seconds remaining on the grace timer for a zone, or 0 if none. */
    public double getGraceRemaining(int zoneId) {
        GraceTimer gt = graceTimers.get(zoneId);
        return (gt != null) ? Math.max(0, gt.secondsLeft) : 0;
    }

    private boolean isFrozen(Player p) {
        return p.frozenUntilSec > nowSec();
    }

    private double nowSec() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    /** Strip pipe/comma characters from player names so they can't corrupt the wire format. */
    private static String sanitizeName(String name) {
        if (name == null) return "Player";
        return name.replaceAll("[|,]", "_");
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

}
