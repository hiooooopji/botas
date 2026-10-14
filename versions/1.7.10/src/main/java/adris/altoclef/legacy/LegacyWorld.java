package adris.altoclef.legacy;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.util.math.Box;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;

/** Coordinate adapters for world APIs that predate 1.8's BlockState. */
final class LegacyWorld {
    private LegacyWorld() { }

    static BlockState state(World world, BlockPos pos) {
        return new BlockState(world.getBlock(pos.x, pos.y, pos.z),
                world.getBlockData(pos.x, pos.y, pos.z));
    }

    static BlockState state(ChunkSection section, int x, int y, int z) {
        return new BlockState(section.getBlock(x, y, z), section.getBlockData(x, y, z));
    }

    static boolean isLoaded(World world, BlockPos pos, boolean unused) {
        return world.isPosLoaded(pos.x, pos.y, pos.z);
    }

    static boolean isAir(World world, BlockPos pos) {
        return world.isAir(pos.x, pos.y, pos.z);
    }

    static net.minecraft.block.entity.BlockEntity blockEntity(World world, BlockPos pos) {
        return world.getBlockEntity(pos.x, pos.y, pos.z);
    }

    static Box collisionBox(Block block, World world, BlockPos pos, BlockState state) {
        return block.hasCollision() ? block.getBoundingBox(world, pos.x, pos.y, pos.z) : null;
    }

    static boolean canPlace(World world, Block block, BlockPos pos, boolean ignoreCollision,
                            Direction face, Entity placer, ItemStack stack) {
        return world.method_3602(block, pos.x, pos.y, pos.z, ignoreCollision,
                face.ordinal(), placer, stack);
    }

    static boolean hasDirectSunlight(World world, BlockPos pos) {
        return world.isAboveHighestBlock(pos.x, pos.y, pos.z);
    }

    static BlockPos highestBlock(World world, BlockPos pos) {
        return new BlockPos(pos.x, world.getHighestBlockY(pos.x, pos.z), pos.z);
    }
}
