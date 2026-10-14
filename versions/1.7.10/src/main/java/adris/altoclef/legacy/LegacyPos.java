package adris.altoclef.legacy;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Immutable-style operations for 1.7.10's mutable BlockPos. */
final class LegacyPos {
    private LegacyPos() { }

    // The local 1.7 player position is at eye level. Navigation uses the box base.
    static double eyeHeight(Entity entity) {
        return entity instanceof net.minecraft.entity.player.PlayerEntity ? 1.62 : entity.getEyeHeight();
    }

    static BlockPos of(Entity entity) {
        return new BlockPos((int) Math.floor(entity.x), (int) Math.floor(entity.boundingBox.minY),
                (int) Math.floor(entity.z));
    }

    static BlockPos of(BlockPos pos) { return new BlockPos(pos); }

    static BlockPos hit(BlockHitResult hit) { return new BlockPos(hit.x, hit.y, hit.z); }
    static double squaredDistance(Entity from, Entity to) {
        return squaredDistance(from, to.x, to.boundingBox.minY, to.z);
    }
    static double squaredDistance(Entity from, double x, double y, double z) {
        double dx = from.x - x, dy = from.boundingBox.minY - y, dz = from.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
    static double squaredDistanceToCenter(Entity entity, BlockPos pos) {
        double dx = entity.x - (pos.x + .5), dy = entity.boundingBox.minY - (pos.y + .5), dz = entity.z - (pos.z + .5);
        return dx * dx + dy * dy + dz * dz;
    }
    static BlockPos add(BlockPos pos, int x, int y, int z) {
        return new BlockPos(pos.x + x, pos.y + y, pos.z + z);
    }
    static BlockPos offset(BlockPos pos, Direction direction) {
        return add(pos, direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
    }
    static BlockPos up(BlockPos pos) { return add(pos, 0, 1, 0); }
    static BlockPos down(BlockPos pos) { return add(pos, 0, -1, 0); }
    static BlockPos down(BlockPos pos, int amount) { return add(pos, 0, -amount, 0); }
}
