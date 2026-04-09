import java.awt.Rectangle;

class Zone {
    final int id;
    final Rectangle rect;

    int ownerId = -1;
    boolean contested = false;

    // Capture progress — lives on the Zone so it resets when the capturing player changes.
    double captureProgress = 0;   // 0.0 – 1.0
    int capturingPlayerId  = -1;  // which player is currently accumulating progress

    Zone(int id, Rectangle rect) {
        this.id = id;
        this.rect = rect;
    }
}
