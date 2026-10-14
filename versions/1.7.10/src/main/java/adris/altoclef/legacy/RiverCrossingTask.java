package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;

/** Local surface-water fallback when an ordinary walking route is disconnected. */
final class RiverCrossingTask implements LegacyTask {
    private final List<PathSearch.Pos> route;
    private int next,ticks;
    private WaterEscapeTask recovery;
    private int tracked=-1,lastProgress;
    private double bestDistance;
    private RiverCrossingTask(List<PathSearch.Pos> route) {this.route=route;}
    private static boolean water(LegacyBot bot,int x,int y,int z) {
        BlockPos p=new BlockPos(x,y,z);
        return LegacyWorld.isLoaded(bot.mc.world,p,false) && LegacyWorld.state(bot.mc.world,p).getBlock().getMaterial()==Material.WATER;
    }
    static RiverCrossingTask plan(LegacyBot bot,BlockPos destination) {
        PathSearch.Terrain surface=new PathSearch.Terrain() {
            public boolean clear(int x,int y,int z) {return bot.navigation.clear(x,y,z);}
            public boolean floor(int x,int y,int z) {return bot.navigation.floor(x,y,z) || water(bot,x,y,z);}
            public boolean canStep(PathSearch.Pos from,PathSearch.Pos to) {return !water(bot,from.x,from.y-1,from.z) || to.y<=from.y;}
        };
        PathSearch.Pos start=bot.navigation.feet(),goal=new PathSearch.Pos(destination.x,destination.y,destination.z);
        List<PathSearch.Pos> path=PathSearch.find(surface,start,goal,6000);
        boolean crosses=false;int end=-1;
        for(int i=0;i<path.size();i++) {
            PathSearch.Pos p=path.get(i);
            crosses|=water(bot,p.x,p.y-1,p.z);
            if(crosses && PathSearch.standing(bot.navigation,p.x,p.y,p.z) && p.distance(goal)+8<start.distance(goal)) end=i;
        }
        return end<0 ? null : new RiverCrossingTask(new ArrayList<>(path.subList(0,end+1)));
    }
    public String describe() {return "cross river toward destination";}
    public void stop(LegacyBot bot) {bot.releaseMovement();bot.navigation.reset();}
    public boolean tick(LegacyBot bot) {
        if(recovery!=null) return recovery.tick(bot);
        if(++ticks>1800) throw new TaskFailure("Could not complete the river crossing.");
        PathSearch.Pos p=route.get(next);
        double dx=p.x+.5-bot.mc.field_3805.x,dz=p.z+.5-bot.mc.field_3805.z;
        boolean wet=WaterEscapeTask.wet(bot) || water(bot,bot.navigation.feet().x,bot.navigation.feet().y-1,bot.navigation.feet().z);
        boolean swimming=wet || water(bot,p.x,p.y-1,p.z);
        if(!bot.navigation.clear(p.x,p.y,p.z) || !bot.navigation.clear(p.x,p.y+1,p.z)) {
            if(wet) {recovery=new WaterEscapeTask();return false;}
            throw new TaskFailure("River exit became obstructed");
        }
        double distance=dx*dx+dz*dz+Math.pow(p.y-bot.mc.field_3805.boundingBox.minY,2);
        if(tracked!=next) {tracked=next;bestDistance=distance;lastProgress=ticks;}
        if(distance<bestDistance-.04) {bestDistance=distance;lastProgress=ticks;}
        if(swimming && ticks-lastProgress>60) {recovery=new WaterEscapeTask();return false;}
        if(dx*dx+dz*dz<.12 && (swimming || Math.abs(bot.mc.field_3805.boundingBox.minY-p.y)<.2)) {
            if(next+1<route.size()) {next++;bot.navigation.reset();return false;}
            if(bot.mc.field_3805.onGround && !WaterEscapeTask.wet(bot)) {stop(bot);return true;}
        }
        if(!swimming) {bot.navigation.move(p,.25);return false;}
        bot.cancelMining();bot.releaseMovement();
        bot.mc.field_3805.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));bot.mc.field_3805.pitch=-10;
        bot.key(bot.mc.options.jumpKey,true);bot.key(bot.mc.options.forwardKey,true);
        return false;
    }
}
