package adris.altoclef.legacy;

import net.minecraft.block.Block;

/** The 1.7.10 block plus its metadata, in place of 1.8's block state. */
public final class BlockState {
    private final Block block;
    private final int data;

    public BlockState(Block block, int data) {
        this.block = block;
        this.data = data;
    }

    public Block getBlock() { return block; }
    public int getData() { return data; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof BlockState)) return false;
        BlockState that = (BlockState) other;
        return block == that.block && data == that.data;
    }

    @Override public int hashCode() { return 31 * System.identityHashCode(block) + data; }
}
