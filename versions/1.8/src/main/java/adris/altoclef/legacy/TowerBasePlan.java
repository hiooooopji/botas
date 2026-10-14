package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Relative coordinates recorded from the player's first Enderman tower section. */
final class TowerBasePlan {
    static final class Cell {
        final int x, y, z;
        final String block;
        Cell(int x, int y, int z, String block) { this.x=x; this.y=y; this.z=z; this.block=block; }
        BlockPos at(BlockPos origin) { return origin.add(x,y,z); }
    }

    static final List<Cell> PLACEMENTS;
    static {
        List<Cell> cells=new ArrayList<>();
        // Ground-level slab ring, then the lower leaf ring. The center stays open.
        for(int y=0;y<=1;y++) for(int z=-1;z<=1;z++) for(int x=-1;x<=1;x++)
            if(x!=0 || z!=0) cells.add(new Cell(x,y,z,y==0 ? "wooden_slab" : "leaves"));
        // Finish ground-level details before climbing onto the lower ring.
        cells.add(new Cell(0,0,-3,"chest"));
        cells.add(new Cell(3,0,-2,"leaves"));
        cells.add(new Cell(0,2,-1,"leaves"));
        cells.add(new Cell(0,3,-1,"leaves"));
        // Begin the upper ring above the demonstrated column, then extend it
        // through adjacent leaves so each placement has an existing face.
        for(int[] xz:new int[][]{{0,-1},{-1,-1},{-1,0},{-1,1},{0,1},{1,1},{1,0},{1,-1}})
            cells.add(new Cell(xz[0],4,xz[1],"leaves"));
        PLACEMENTS=Collections.unmodifiableList(cells);
    }

    static boolean matches(World world, BlockPos origin, Cell cell) {
        BlockState state=world.getBlockState(cell.at(origin));
        Block block=state.getBlock();
        return cell.block.equals("leaves") ? block==Block.get("leaves") || block==Block.get("leaves2")
                : block==Block.get(cell.block);
    }

    static int leaves() {
        int count=0;
        for(Cell cell:PLACEMENTS) if(cell.block.equals("leaves")) count++;
        return count;
    }

    private TowerBasePlan() { }
}
