class Player {
    final int    id;
    final boolean isYou;   // client-side flag: true for the locally controlled player
    final String name;

    double x, y;
    double vx, vy;
    double score = 0;

    // Freeze / weapon power state (times stored as seconds from System.nanoTime epoch)
    double frozenUntilSec        = 0;
    double freezeRayUntilSec     = 0;  // > 0 while player holds the freeze-ray weapon
    double freezeCooldownUntilSec = 0;

    Player(int id, boolean isYou, String name, double x, double y) {
        this.id    = id;
        this.isYou = isYou;
        this.name  = name;
        this.x     = x;
        this.y     = y;
    }
}
