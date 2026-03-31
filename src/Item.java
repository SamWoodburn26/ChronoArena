class Item {
    final int id;
    final String kind; // "energy" or "freeze"

    final double x, y;
    boolean alive = true;

    Item(int id, String kind, double x, double y) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
    }
}

