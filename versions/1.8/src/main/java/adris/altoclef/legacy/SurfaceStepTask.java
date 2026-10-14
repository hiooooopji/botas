package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Cut one nearby step out of a shallow pocket, clearing loose sand from the top. */
final class SurfaceStepTask implements LegacyTask {
    private final PathSearch.Pos landing;
    private final List<BlockPos> clearance;
    private int ticks,index;
    private SurfaceStepTask(PathSearch.Pos landing,List<BlockPos> clearance) {
        this.landing=landing;this.clearance=clearance;
    }
    public String describe() {return "cut surface exit step at "+landing;}
    public void stop(LegacyBot bot) {bot.cancelMining();bot.releaseMovement();bot.navigation.reset();}
    private static boolean removable(LegacyBot bot,BlockPos p) {
        if(!bot.mc.world.isLoaded(p,false) || bot.mc.world.getBlockEntity(p)!=null || bot.protectsTowerBase(p)) return false;
        if(bot.navigation.clear(p.getX(),p.getY(),p.getZ())) return true;
        String id=String.valueOf(Block.REGISTRY.getIdentifier(bot.mc.world.getBlockState(p).getBlock()));
        if(!Arrays.asList("minecraft:sand","minecraft:gravel","minecraft:dirt","minecraft:grass").contains(id)) return false;
        for(Direction d:Direction.values()) {
            BlockPos q=p.offset(d);
            if(!bot.mc.world.isLoaded(q,false) || bot.mc.world.getBlockState(q).getBlock().getMaterial().isFluid()) return false;
        }
        return true;
    }
    static SurfaceStepTask plan(LegacyBot bot) {
        PathSearch.Pos from=bot.navigation.feet();
        if(!bot.mc.player.onGround || !PathSearch.standing(bot.navigation,from.x,from.y,from.z)) return null;
        // Never release falling blocks above the player.
        if(!bot.navigation.clear(from.x,from.y+2,from.z)) return null;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            int x=from.x+d[0],z=from.z+d[1];
            if(!bot.navigation.floor(x,from.y,z)) continue;
            int top=from.y+2;
            while(top<from.y+5 && bot.mc.world.getBlockState(new BlockPos(x,top+1,z)).getBlock() instanceof FallingBlock) top++;
            if(top>from.y+3 || bot.mc.world.getBlockState(new BlockPos(x,top+1,z)).getBlock() instanceof FallingBlock) continue;
            List<BlockPos> blocks=new ArrayList<>();boolean safe=true;
            for(int y=top;y>=from.y+1;y--) {
                BlockPos p=new BlockPos(x,y,z);
                if(!removable(bot,p)) {safe=false;break;}
                if(!bot.navigation.clear(x,y,z)) blocks.add(p);
            }
            if(safe && !blocks.isEmpty() && bot.hit(blocks.get(0))!=null)
                return new SurfaceStepTask(new PathSearch.Pos(x,from.y+1,z),blocks);
        }
        return null;
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>300) throw new TaskFailure("Surface exit step could not be completed.");
        if(index<clearance.size()) {
            BlockPos p=clearance.get(index);
            if(bot.navigation.clear(p.getX(),p.getY(),p.getZ())) {index++;return false;}
            if(!removable(bot,p) || bot.mc.world.getBlockState(p.up()).getBlock() instanceof FallingBlock)
                throw new TaskFailure("Surface exit changed while clearing it.");
            bot.releaseMovement();bot.mine(p);return false;
        }
        bot.cancelMining();
        return bot.navigation.move(landing,.3);
    }
}
