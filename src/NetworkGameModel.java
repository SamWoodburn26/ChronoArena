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
    volatile boolean killed      = false;
    volatile String killedLine = "";

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
        // Zone rects are now sent by the server in every STATE message —
        // no client-side zone init needed.
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
        // Rebuild the zone list every frame — rects are sent by the server so
        // randomised layouts and zone-count changes are reflected automatically.
        if (zonesIdx != -1) {
            int end = (itemsIdx != -1) ? itemsIdx : parts.length;
            List<ZoneSnapshot> freshZones = new ArrayList<>();
            for (int i = zonesIdx + 1; i < end; i++) {
                if (parts[i].isEmpty()) continue;
                String[] f = parts[i].split(",");
                if (f.length < 9) continue;  // id,x,y,w,h,ownerId,contested,captureProgress,graceRemSec
                try {
                    ZoneSnapshot z    = new ZoneSnapshot();
                    z.id              = Integer.parseInt(f[0]);
                    z.rect            = new Rectangle(
                                            Integer.parseInt(f[1]),
                                            Integer.parseInt(f[2]),
                                            Integer.parseInt(f[3]),
                                            Integer.parseInt(f[4]));
                    z.ownerId         = Integer.parseInt(f[5]);
                    z.contested       = "1".equals(f[6]);
                    z.captureProgress = Double.parseDouble(f[7]);
                    z.graceRemSec     = Double.parseDouble(f[8]);
                    freshZones.add(z);
                } catch (NumberFormatException ignored) {}
            }
            zones.clear();
            zones.addAll(freshZones);
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

    synchronized void applyKilledMessage(String msg) {
        killed = true;
        //String[] parts = msg.split("\\|");
        killedLine = "You were removed from the game by the server.";
        
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
