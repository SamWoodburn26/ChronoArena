import java.awt.Rectangle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe snapshot of authoritative server state.
 *
 * Written by the TCP listener thread (via applyStateMessage / applyGameOverMessage).
 * Read by the Swing EDT for rendering in NetworkGamePanel.
 *
 * Collections are concurrent-safe; applyStateMessage is synchronized so the
 * renderer never sees a half-updated frame.
 */
class NetworkGameModel {

    // Scalars — volatile so EDT always reads the latest value
    volatile double  timeLeftSec = 0;
    volatile boolean gameOver    = false;
    volatile String  winnerLine  = "";

    // Set to true for one frame when the server resets the round mid-session.
    // NetworkGamePanel reads and clears this to re-sync the local player position.
    volatile boolean roundReset  = false;

    // Main state collections
    final ConcurrentHashMap<Integer, PlayerSnapshot> players = new ConcurrentHashMap<>();
    final CopyOnWriteArrayList<ZoneSnapshot>         zones   = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<ItemSnapshot>         items   = new CopyOnWriteArrayList<>();

    final int mapWidth, mapHeight;
    int myPlayerId = -1;  // set by ChronoArenaClientUI after join

    // -------------------------------------------------------------------------
    // Data holders
    // -------------------------------------------------------------------------

    static class PlayerSnapshot {
        int    id;
        String name        = "";
        double x, y, score;
        boolean frozen, hasWeapon;
        double  frozenRemSec;
    }

    static class ZoneSnapshot {
        int       id;
        Rectangle rect;        // computed from properties — matches server initZones()
        int       ownerId        = -1;
        boolean   contested;
        double    captureProgress;
        double    graceRemSec;
    }

    static class ItemSnapshot {
        int    id;
        String kind;           // "energy" or "freeze"
        double x, y;
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    NetworkGameModel(Properties props) {
        mapWidth  = Integer.parseInt(props.getProperty("map_width",  "900"));
        mapHeight = Integer.parseInt(props.getProperty("map_height", "650"));
        int zoneCount = Integer.parseInt(props.getProperty("zone_count", "4"));
        initZones(zoneCount);
    }

    /**
     * Mirrors GameState.initZones() exactly so zone rectangles match server placement.
     * Both sides read the same properties file, so the layout is always in sync.
     */
    private void initZones(int zoneCount) {
        zones.clear();
        int margin = 60, zw = 160, zh = 120;

        // Four corner zones (always present)
        addZone(1, margin,                  margin);
        addZone(2, mapWidth  - margin - zw, margin);
        addZone(3, margin,                  mapHeight - margin - zh);
        addZone(4, mapWidth  - margin - zw, mapHeight - margin - zh);

        // Extra central zones when zone_count > 4
        for (int i = 5; i <= zoneCount; i++) {
            int cx = (int) ((i - 4) * (mapWidth / (zoneCount - 3.0)));
            int cy = mapHeight / 2 - zh / 2;
            addZone(i, clamp(cx - zw / 2, 0, mapWidth - zw), cy);
        }
    }

    private void addZone(int id, int x, int y) {
        ZoneSnapshot z = new ZoneSnapshot();
        z.id   = id;
        z.rect = new Rectangle(x, y, 160, 120);
        zones.add(z);
    }

    // -------------------------------------------------------------------------
    // Message parsing  (called from TCP listener thread)
    // -------------------------------------------------------------------------

    /**
     * Parses the pipe-delimited STATE message broadcast by the server each tick.
     *
     * Format (from GameState.serialize()):
     *   STATE|timeLeft|
     *     id,name,x,y,score,frozen,hasWeapon,frozenRemSec|  (one per player)
     *     ...
     *   ZONES|
     *     id,ownerId,contested,captureProgress,graceRemSec| (one per zone)
     *     ...
     *   ITEMS|
     *     id,kind,x,y|                                      (one per active item)
     *     ...
     */
    synchronized void applyStateMessage(String msg) {
        // If a STATE arrives while we thought the game was over, the server
        // issued a RESET — clear the overlay and signal the panel to re-sync.
        if (gameOver) {
            gameOver   = false;
            winnerLine = "";
            roundReset = true;
        }

        String[] parts = msg.split("\\|");
        if (parts.length < 2) return;

        try { timeLeftSec = Double.parseDouble(parts[1]); }
        catch (NumberFormatException ignored) {}

        // Locate section markers
        int zonesIdx = -1, itemsIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if      ("ZONES".equals(parts[i])) zonesIdx = i;
            else if ("ITEMS".equals(parts[i])) itemsIdx = i;
        }

        // ---- Players ----
        int playerEnd = (zonesIdx != -1) ? zonesIdx : parts.length;
        Set<Integer> seen = new HashSet<>();
        for (int i = 2; i < playerEnd; i++) {
            if (parts[i].isEmpty()) continue;
            String[] f = parts[i].split(",");
            if (f.length < 8) continue;
            try {
                int id = Integer.parseInt(f[0]);
                PlayerSnapshot ps = players.computeIfAbsent(id, k -> new PlayerSnapshot());
                ps.id           = id;
                ps.name         = f[1];
                ps.x            = Double.parseDouble(f[2]);
                ps.y            = Double.parseDouble(f[3]);
                ps.score        = Double.parseDouble(f[4]);
                ps.frozen       = "1".equals(f[5]);
                ps.hasWeapon    = "1".equals(f[6]);
                ps.frozenRemSec = Double.parseDouble(f[7]);
                seen.add(id);
            } catch (NumberFormatException ignored) {}
        }
        players.keySet().retainAll(seen);  // remove players no longer in state

        // ---- Zones ----
        if (zonesIdx != -1) {
            int end = (itemsIdx != -1) ? itemsIdx : parts.length;
            for (int i = zonesIdx + 1; i < end; i++) {
                if (parts[i].isEmpty()) continue;
                String[] f = parts[i].split(",");
                if (f.length < 5) continue;
                try {
                    int id = Integer.parseInt(f[0]);
                    for (ZoneSnapshot z : zones) {
                        if (z.id == id) {
                            z.ownerId         = Integer.parseInt(f[1]);
                            z.contested       = "1".equals(f[2]);
                            z.captureProgress = Double.parseDouble(f[3]);
                            z.graceRemSec     = Double.parseDouble(f[4]);
                            break;
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // ---- Items ----
        List<ItemSnapshot> fresh = new ArrayList<>();
        if (itemsIdx != -1) {
            for (int i = itemsIdx + 1; i < parts.length; i++) {
                if (parts[i].isEmpty()) continue;
                String[] f = parts[i].split(",");
                if (f.length < 4) continue;
                try {
                    ItemSnapshot is = new ItemSnapshot();
                    is.id   = Integer.parseInt(f[0]);
                    is.kind = f[1];
                    is.x    = Double.parseDouble(f[2]);
                    is.y    = Double.parseDouble(f[3]);
                    fresh.add(is);
                } catch (NumberFormatException ignored) {}
            }
        }
        items.clear();
        items.addAll(fresh);
    }

    /**
     * Parses the GAMEOVER message and stores the winner line for display.
     *
     * Format (from GameState.serializeGameOver()):
     *   GAMEOVER|winnerId,winnerName,winnerScore|p1id,p1name,p1score|...
     */
    synchronized void applyGameOverMessage(String msg) {
        gameOver = true;
        String[] parts = msg.split("\\|");
        if (parts.length >= 2) {
            String[] info = parts[1].split(",");
            if (info.length >= 3) {
                winnerLine = info[1] + " wins with " + info[2] + " pts!";
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
