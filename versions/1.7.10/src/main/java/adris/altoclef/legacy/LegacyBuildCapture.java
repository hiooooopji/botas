package adris.altoclef.legacy;

import net.minecraft.block.Block;
import adris.altoclef.legacy.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Records real world edits in an isolated interactive build fixture. */
public final class LegacyBuildCapture {
    private static final Logger LOGGER = LogManager.getLogger("Botas/BuildCapture");
    private static volatile World world;
    private static volatile BlockPos center;

    private LegacyBuildCapture() { }

    public static void start(World target, BlockPos origin) {
        center = origin;
        world = target;
        LOGGER.info("BUILD_CAPTURE_READY center={} horizontal_radius=32 y_range={}..{}",
                origin, origin.y - 8, origin.y + 40);
    }

    public static void stop() {
        world = null;
        center = null;
    }

    public static BlockState before(World target, BlockPos pos) {
        BlockPos origin = center;
        if (target != world || origin == null || Math.abs(pos.x - origin.x) > 32
                || Math.abs(pos.z - origin.z) > 32
                || pos.y < origin.y - 8 || pos.y > origin.y + 40) return null;
        return LegacyWorld.state(target,pos);
    }

    public static void after(BlockPos pos, BlockState previous, BlockState current, boolean changed) {
        if (!changed || previous == null || previous.equals(current)) return;
        LOGGER.info("BUILD_CHANGE x={} y={} z={} before={} after={}", pos.x, pos.y, pos.z,
                describe(previous), describe(current));
    }

    private static String describe(BlockState state) {
        Block block = state.getBlock();
        return Block.REGISTRY.getId(block) + ":" + state.getData();
    }
}
