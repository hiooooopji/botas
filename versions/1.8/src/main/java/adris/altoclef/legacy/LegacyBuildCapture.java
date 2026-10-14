package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
                origin, origin.getY() - 8, origin.getY() + 40);
    }

    public static void stop() {
        world = null;
        center = null;
    }

    public static BlockState before(World target, BlockPos pos) {
        BlockPos origin = center;
        if (target != world || origin == null || Math.abs(pos.getX() - origin.getX()) > 32
                || Math.abs(pos.getZ() - origin.getZ()) > 32
                || pos.getY() < origin.getY() - 8 || pos.getY() > origin.getY() + 40) return null;
        return target.getBlockState(pos);
    }

    public static void after(BlockPos pos, BlockState previous, BlockState current, boolean changed) {
        if (!changed || previous == null || previous.equals(current)) return;
        LOGGER.info("BUILD_CHANGE x={} y={} z={} before={} after={}", pos.getX(), pos.getY(), pos.getZ(),
                describe(previous), describe(current));
    }

    private static String describe(BlockState state) {
        Block block = state.getBlock();
        return Block.REGISTRY.getIdentifier(block) + ":" + block.getData(state);
    }
}
