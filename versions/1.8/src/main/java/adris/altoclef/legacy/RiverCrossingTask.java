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
    private BoatTravelTask boat;
    private boolean triedBoat;
    boolean usedBoat;
    private RiverCrossingTask(List<PathSearch.Pos> route) {this.route=route;}
    private static boolean water(LegacyBot bot,int x,int y,int z) {
        BlockPos p=new BlockPos(x,y,z);
        return bot.mc.world.isLoaded(p,false) && bot.mc.world.getBlockState(p).getBlock().getMaterial()==Material.WATER;
    }
    static RiverCrossingTask planBoat(LegacyBot bot,BlockPos destination){
        RiverCrossingTask crossing=plan(bot,destination);
        if(crossing==null)return null;
        crossing.boat=BoatTravelTask.plan(bot,crossing.route);crossing.triedBoat=true;
        return crossing.boat==null?null:crossing;
    }
    static RiverCrossingTask plan(LegacyBot bot,BlockPos destination) {
        PathSearch.Terrain surface=new PathSearch.Terrain() {
            public boolean clear(int x,int y,int z) {return bot.navigation.clear(x,y,z);}
            public boolean floor(int x,int y,int z) {return bot.navigation.floor(x,y,z) || water(bot,x,y,z);}
            public boolean canStep(PathSearch.Pos from,PathSearch.Pos to) {return !water(bot,from.x,from.y-1,from.z) || to.y<=from.y;}
        };
        PathSearch.Pos start=bot.navigation.feet(),goal=new PathSearch.Pos(destination.getX(),destination.getY(),destination.getZ());
        List<PathSearch.Pos> path=PathSearch.findWithin(surface,start,goal,8000,96);
        boolean crosses=false;int end=-1;
        for(int i=0;i<path.size();i++) {
            PathSearch.Pos p=path.get(i);
            crosses|=water(bot,p.x,p.y-1,p.z);
            if(crosses && PathSearch.standing(bot.navigation,p.x,p.y,p.z) && p.distance(goal)+8<start.distance(goal)) end=i;
        }
        return end<0 ? null : new RiverCrossingTask(new ArrayList<>(path.subList(0,end+1)));
    }
    public String describe() {return boat!=null?boat.describe():recovery!=null ? "river shore recovery" : "cross river step "+next+"/"+route.size()+" to "+route.get(next)+" idle="+(ticks-lastProgress);}
    public void stop(LegacyBot bot) {if(boat!=null)boat.stop(bot);bot.releaseMovement();bot.navigation.reset();}
    public boolean tick(LegacyBot bot) {
        if(!triedBoat){triedBoat=true;boat=BoatTravelTask.plan(bot,route);}
        if(boat!=null){
            try{
                if(boat.tick(bot)){usedBoat=boat.boarded;next=boat.riverExit;boat.stop(bot);boat=null;tracked=-1;}
            }catch(TaskFailure failed){
                boat.stop(bot);boat=null;
                bot.say("Boat recovery: "+failed.getMessage());
                double best=Double.MAX_VALUE;
                for(int i=next;i<route.size();i++){PathSearch.Pos p=route.get(i);double d=bot.mc.player.squaredDistanceTo(p.x+.5,p.y,p.z+.5);if(d<best){best=d;next=i;}}
                tracked=-1;
            }
            return false;
        }
        if(bot.mc.player.vehicle!=null){bot.releaseMovement();bot.key(bot.mc.options.sneakKey,true);return false;}
        if(recovery!=null) return recovery.tick(bot);
        if(++ticks>1800) throw new TaskFailure("Could not complete the river crossing.");
        PathSearch.Pos p=route.get(next);
        double dx=p.x+.5-bot.mc.player.x,dz=p.z+.5-bot.mc.player.z;
        boolean wet=WaterEscapeTask.wet(bot) || water(bot,bot.navigation.feet().x,bot.navigation.feet().y-1,bot.navigation.feet().z);
        boolean swimming=wet || water(bot,p.x,p.y-1,p.z);
        if(!bot.navigation.clear(p.x,p.y,p.z) || !bot.navigation.clear(p.x,p.y+1,p.z)) {
            if(wet) {recovery=new WaterEscapeTask();return false;}
            throw new TaskFailure("River exit became obstructed");
        }
        double distance=dx*dx+dz*dz+Math.pow(p.y-bot.mc.player.y,2);
        if(tracked!=next) {tracked=next;bestDistance=distance;lastProgress=ticks;}
        if(distance<bestDistance-.04) {bestDistance=distance;lastProgress=ticks;}
        if(swimming && ticks-lastProgress>60) {recovery=new WaterEscapeTask();return false;}
        if(!swimming && ticks-lastProgress>80) throw new TaskFailure("River bank approach made no progress.");
        if(dx*dx+dz*dz<.12 && (swimming || Math.abs(bot.mc.player.y-p.y)<.2)) {
            if(next+1<route.size()) {next++;bot.navigation.reset();return false;}
            if(bot.mc.player.onGround && !WaterEscapeTask.wet(bot)) {stop(bot);return true;}
        }
        if(!swimming) {bot.navigation.move(p,.25);return false;}
        bot.cancelMining();bot.releaseMovement();
        bot.mc.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));bot.mc.player.pitch=-10;
        bot.key(bot.mc.options.jumpKey,true);bot.key(bot.mc.options.forwardKey,true);
        return false;
    }
}
