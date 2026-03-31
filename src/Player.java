class Player {
    final int id;
    final boolean isYou;
    final String name;

    double x, y;
    double vx, vy;
    double score = 0;

    // Freeze / weapon power state
    double frozenUntilSec = 0;
    double freezeRayUntilSec = 0;
    double freezeCooldownUntilSec = 0;

    // Zone capture state (for local demo)
    int captureZoneId = -1;
    double captureProgress = 0; // 0..1

    Player(int id, boolean isYou, String name, double x, double y) {
        this.id = id;
        this.isYou = isYou;
        this.name = name;
        this.x = x;
        this.y = y;
    }
}

