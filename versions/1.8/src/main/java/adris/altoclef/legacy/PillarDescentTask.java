package adris.altoclef.legacy;

import net.minecraft.util.math.BlockPos;

/** Mines a temporary support one block at a time to return to nearby ground. */
final class PillarDescentTask implements LegacyTask {
    private int ticks, mined;

    private static boolean safeLanding(LegacyBot bot,PathSearch.Pos feet) {
        // Removing the support can descend at most three blocks, with an
        // entirely clear shaft and a full dry floor at the bottom.
        for(int y=feet.y-2;y>=feet.y-4;y--) {
            if(bot.navigation.floor(feet.x,y,feet.z)) return true;
            if(!bot.navigation.clear(feet.x,y,feet.z)) return false;
        }
        return false;
    }

    static PillarDescentTask plan(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        // Leaves overhead do not turn a temporary pillar into a mine shaft.
        // Compare actual terrain height (which excludes tree canopies).
        if(!bot.mc.player.onGround || SurfaceAccessTask.terrainSurface(bot,feet.x,feet.z)>feet.y) return null;
        int ground=255;
        for(int[] d:new int[][]{{2,0},{-2,0},{0,2},{0,-2},{3,0},{-3,0},{0,3},{0,-3}}) {
            int y=SurfaceAccessTask.terrainSurface(bot,feet.x+d[0],feet.z+d[1]);
            if(y>0) ground=Math.min(ground,y);
        }
        if(ground==255 || feet.y<=ground+1) return null;
        BlockPos support=new BlockPos(feet.x,feet.y-1,feet.z);
        if(bot.mc.world.getBlockEntity(support)!=null || !bot.navigation.floor(feet.x,feet.y-1,feet.z)
                || !safeLanding(bot,feet)
                || bot.hit(support)==null) return null;
        return new PillarDescentTask();
    }

    public String describe() {return "descend temporary pillar ("+mined+" blocks)";}
    public void stop(LegacyBot bot) {bot.cancelMining();bot.releaseMovement();bot.navigation.reset();}
    public boolean tick(LegacyBot bot) {
        if(++ticks>400 || mined>16) throw new TaskFailure("Could not descend temporary pillar.");
        if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
        PillarDescentTask still=plan(bot);
        if(still==null) return true;
        PathSearch.Pos feet=bot.navigation.feet();
        BlockPos support=new BlockPos(feet.x,feet.y-1,feet.z);
        if(bot.mine(support)) mined++;
        return false;
    }
}
