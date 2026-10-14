package adris.altoclef.legacy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.math.BlockPos;

/** Walks through loaded surface waypoints until a distant generated structure is nearby. */
final class StructureTravelTask implements LegacyTask {
    private final BlockPos target;
    private final int arrivalRadius;
    private final Set<PathSearch.Pos> rejected=new HashSet<>();
    private LegacyTask child;
    private int ticks,failures,descentBlocks;
    private long bestDistance=Long.MAX_VALUE;
    private boolean usedDetour;
    private List<PathSearch.Pos> plannedRoute;
    private int nextBoatScan;
    StructureTravelTask(BlockPos target,int arrivalRadius) {this.target=target;this.arrivalRadius=arrivalRadius;}
    boolean canPauseForCombat() {return child==null || child instanceof MovementTasks.GoTo;}
    public String describe() {return "travel toward "+target.getX()+" "+target.getZ()
            +(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();bot.navigation.reset();}
    private long distanceSq(int x,int z) {long dx=x-target.getX(),dz=z-target.getZ();return dx*dx+dz*dz;}
    private PathSearch.Pos waypoint(LegacyBot bot,PathSearch.Pos from) {
        plannedRoute=null;
        // One search can follow bends below overhangs. Testing many surface
        // columns separately was expensive and discarded those valid corridors.
        PathSearch.Pos goal=new PathSearch.Pos(target.getX(),from.y,target.getZ());
        List<PathSearch.Pos> route=PathSearch.explore(bot.navigation,from,goal,6000);
        if(route.isEmpty()) return null;
        PathSearch.Pos end=route.get(route.size()-1);
        if(end.distance(from)<4 || rejected.contains(end)
                || distanceSq(end.x,end.z)>=distanceSq(from.x,from.z)-16) return null;
        // Surface resources must not draw travel down into a cave beneath them.
        if(SurfaceAccessTask.terrainSurface(bot,end.x,end.z)>end.y) return null;
        // Keep each movement leg local even when the search sees farther ahead.
        int endIndex=Math.min(31,route.size()-1);
        PathSearch.Pos leg=route.get(endIndex);
        if(rejected.contains(leg))return null;
        plannedRoute=new java.util.ArrayList<>(route.subList(0,endIndex+1));
        return leg;
    }
    private boolean descendIsolatedPillar(LegacyBot bot,PathSearch.Pos feet) {
        if(descentBlocks>=10 || !bot.mc.player.onGround) return false;
        int nearby=255;
        for(int[] offset:new int[][]{{3,0},{-3,0},{0,3},{0,-3}}) {
            int y=SurfaceAccessTask.terrainSurface(bot,feet.x+offset[0],feet.z+offset[1]);
            if(y>0) nearby=Math.min(nearby,y);
        }
        if(nearby==255 || feet.y<=nearby+1 || !bot.navigation.floor(feet.x,feet.y-2,feet.z)) return false;
        BlockPos support=new BlockPos(feet.x,feet.y-1,feet.z);
        if(bot.mc.world.getBlockEntity(support)!=null || bot.hit(support)==null) return false;
        if(ticks%100==0) bot.say("Travel descent: feet="+feet+" nearby surface="+nearby
                +" support="+bot.mc.world.getBlockState(support).getBlock());
        if(bot.mine(support)) descentBlocks++;
        return true;
    }
    private PathSearch.Pos localExit(LegacyBot bot,PathSearch.Pos from) {
        java.util.Map<Long,Integer> surfaces=new java.util.HashMap<>();
        List<PathSearch.Pos> route=PathSearch.findExit(bot.navigation,from,
                new PathSearch.Pos(target.getX(),from.y,target.getZ()),6000,p -> {
                    if(p.distance(from)<6 || rejected.contains(p)) return false;
                    long key=((long)p.x<<32) ^ (p.z & 0xffffffffL);
                    int top=surfaces.computeIfAbsent(key,k->SurfaceAccessTask.terrainSurface(bot,p.x,p.z));
                    return top>0 && top<=p.y;
                });
        return route.isEmpty() ? null : route.get(route.size()-1);
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>24000) throw new TaskFailure("Travel to structure timed out near "+bot.navigation.feet());
        PathSearch.Pos feet=bot.navigation.feet();
        long remaining=distanceSq(feet.x,feet.z);
        if(remaining<bestDistance-9) {bestDistance=remaining;usedDetour=false;}
        if(distanceSq(feet.x,feet.z)<=arrivalRadius*arrivalRadius) {stop(bot);return true;}
        if(child!=null) {
            try {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();failures=0;}}
            catch(TaskFailure blocked) {
                child.stop(bot);child=null;bot.navigation.reset();
                bot.say("Travel recovery at "+feet+": "+blocked.getMessage());
                if(++failures>10) throw new TaskFailure("Cannot advance toward structure near "+feet+": "+blocked.getMessage());
            }
            return false;
        }
        if(WaterEscapeTask.wet(bot)) {child=new WaterEscapeTask();return false;}
        if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
        feet=PathSearch.groundedStart(bot.navigation,bot.mc.player.x,feet.y,bot.mc.player.z);
        if(bot.ticks>=nextBoatScan){
            nextBoatScan=bot.ticks+100;
            double dx=target.getX()-bot.mc.player.x,dz=target.getZ()-bot.mc.player.z,length=Math.hypot(dx,dz);
            int waterAhead=0;
            for(int i=1;i<=12 && length>12;i++)if(BoatTravelTask.water(bot,(int)Math.floor(bot.mc.player.x+dx/length*i),feet.y-1,(int)Math.floor(bot.mc.player.z+dz/length*i)))waterAhead++;
            if(waterAhead>=4){
                RiverCrossingTask crossing=RiverCrossingTask.planBoat(bot,target);
                if(crossing!=null){child=crossing;return false;}
            }
        }
        PathSearch.Pos next=waypoint(bot,feet);
        if(next==null) {
            // A disconnected bank needs a crossing. Repeatedly choosing other
            // reachable points on the same bank never brings us to the target.
            RiverCrossingTask crossing=RiverCrossingTask.plan(bot,target);
            if(crossing!=null) {child=crossing;return false;}
            if(!usedDetour) {next=localExit(bot,feet);usedDetour=true;}
        }
        if(next==null) {
            SurfaceStepTask step=SurfaceStepTask.plan(bot);
            if(step!=null) {child=step;return false;}
            PillarDescentTask descent=PillarDescentTask.plan(bot);
            if(descent!=null) {child=descent;return false;}
            if(SurfaceAccessTask.underground(bot) && failures==0) {child=new SurfaceAccessTask();failures++;return false;}
            if(descendIsolatedPillar(bot,feet)) return false;
            if(ticks%100==0) bot.say("Travel has no waypoint from "+feet+" toward "+target
                    +"; adjacent ground="+SurfaceAccessTask.terrainSurface(bot,feet.x-1,feet.z)+","+
                    SurfaceAccessTask.terrainSurface(bot,feet.x,feet.z+1));
            RiverCrossingTask crossing=RiverCrossingTask.plan(bot,target);
            if(crossing!=null) {child=crossing;return false;}
            if(++failures>10) throw new TaskFailure("No loaded surface route toward structure from "+feet);
            bot.releaseMovement();return false;
        }
        rejected.add(next);
        child=new MovementTasks.GoTo(next.x,next.y,next.z,false);
        if(plannedRoute!=null && plannedRoute.get(plannedRoute.size()-1).equals(next))bot.navigation.useWalkingRoute(feet,plannedRoute);
        return false;
    }
}
