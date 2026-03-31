import java.awt.Rectangle;

class Zone {
    final int id;
    final Rectangle rect;

    int ownerId = -1;
    boolean contested = false;

    Zone(int id, Rectangle rect) {
        this.id = id;
        this.rect = rect;
    }
}

