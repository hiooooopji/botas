package adris.altoclef.legacy;

import net.minecraft.util.math.Direction;

final class LegacyDirection {
    private LegacyDirection() { }
    static Direction opposite(Direction direction) {
        return Direction.getById(direction.ordinal() ^ 1);
    }
}
