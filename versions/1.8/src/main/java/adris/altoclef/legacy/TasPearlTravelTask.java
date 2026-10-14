package adris.altoclef.legacy;

import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;
import java.util.*;

/** Real pearl projectiles between broad dry landing areas; ordinary walking handles obstructed sections. */
final class TasPearlTravelTask implements LegacyTask {
    private final BlockPos target;
    private final int radius;
    private LegacyTask child;
    private EndermanCombatTask combat;
    private int nextCombatCheck,walkSince,flightSince,ticks;
    private double launchX,launchZ;
    private BlockPos landing;
    private boolean flying;
    private TasPearlFlight.Flight flight;
    private int nextThrowAfter;
    int throwsMade,landings;
    boolean collectedPearl;
    TasPearlTravelTask(BlockPos target,int radius) {this.target=target;this.radius=radius;}
    public String describe() {return "pearl travel to "+target.getX()+" "+target.getZ()+" ("+throwsMade+" throws)"
            +(child==null?flying?" -> verify landing":"":" -> "+child.describe());}
    boolean canPauseForCombat() {return !flying && child==null;}
    public boolean tick(LegacyBot bot) {
        if(++ticks>18000)throw new TaskFailure("Pearl route timed out.");
        if(combat!=null) {
            if(combat.tick(bot)) {collectedPearl|=combat.pearlCollected;combat.stop(bot);combat=null;nextCombatCheck=bot.ticks+80;}
            return false;
        }
        if(flying) {
            bot.releaseMovement();
            double moved=Math.hypot(bot.mc.player.x-launchX,bot.mc.player.z-launchZ);
            if(flight!=null && flight.impacted && bot.mc.player.onGround
                    && Math.hypot(bot.mc.player.x-flight.x,bot.mc.player.z-flight.z)<2) {
                flying=false;landings++;bot.tasPearlLandings++;bot.navigation.reset();
                if(!flight.damagePrevented || flight.spreadRolls!=3)throw new TaskFailure("TAS pearl damage protection and deterministic launch were not confirmed.");
                bot.tasPearlDamageAvoided++;flight.close();flight=null;
                if(moved<6 || Math.hypot(bot.mc.player.x-landing.getX()-.5,bot.mc.player.z-landing.getZ()-.5)>4) {
                    nextThrowAfter=bot.ticks+12;
                    bot.say("Pearl hit terrain before the planned landing; moving to a clearer launch point.");
                }
                bot.say("TAS_PEARL_LANDED: "+new BlockPos(bot.mc.player)+" health="+bot.mc.player.getHealth());
            }
            if(flying && bot.ticks-flightSince>180)throw new TaskFailure("Pearl landing was not confirmed; stopped before spending another pearl.");
            if(flying)return false;
        }
        PearlTrajectory.Shot rechecked=null;
        if(child!=null) {
            if(child instanceof StructureTravelTask && bot.ticks-walkSince>=4
                    && ((StructureTravelTask)child).canPauseForCombat() && bot.mc.player.onGround && !WaterEscapeTask.wet(bot)) {
                walkSince=bot.ticks;
                double tx=target.getX()+.5-bot.mc.player.x,tz=target.getZ()+.5-bot.mc.player.z,dist=Math.hypot(tx,tz);
                int ammo=bot.count(LegacyItems.parse("ender_pearl"));
                if(ammo==0 && bot.ticks>=nextCombatCheck) {
                    // A missing Enderman must not discard a perfectly usable
                    // walking path every four ticks. Resume this same child
                    // after the asynchronous loaded-mob check or fight.
                    bot.releaseMovement();combat=new EndermanCombatTask(false);return false;
                }
                if(ammo>2 && dist>radius && bot.ticks>=nextThrowAfter)rechecked=findShot(bot,tx/dist,tz/dist,dist);
                if(rechecked!=null || ammo>0 && ammo<=2 || dist<=radius) {child.stop(bot);child=null;}
            }
            if(child!=null) {
                if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}
                return false;
            }
        }
        double dx=target.getX()+.5-bot.mc.player.x,dz=target.getZ()+.5-bot.mc.player.z,d=Math.hypot(dx,dz);
        if(d<=radius) {bot.releaseMovement();return true;}
        int pearls=bot.count(LegacyItems.parse("ender_pearl"));
        if(pearls==0 && bot.mc.player.onGround && !WaterEscapeTask.wet(bot) && bot.ticks>=nextCombatCheck) {
            combat=new EndermanCombatTask(false);return false;
        }
        if(pearls>0 && pearls<=2 && bot.mc.player.onGround && !WaterEscapeTask.wet(bot)) {
            child=new ResourceDupeTask(LegacyItems.parse("ender_pearl"),16);return false;
        }
        bot.key(bot.mc.options.useKey,false);
        if(pearls>0 && bot.ticks>=nextThrowAfter && d>=8 && bot.mc.player.onGround && !WaterEscapeTask.wet(bot)) {
            bot.releaseMovement();bot.cancelMining();
            PearlTrajectory.Shot shot=rechecked!=null?rechecked:findShot(bot,dx/d,dz/d,d);
            if(shot!=null) {
                bot.equip(bot.findInventory(LegacyItems.parse("ender_pearl")));
                bot.mc.player.yaw=shot.yaw;bot.mc.player.pitch=shot.pitch;LegacyFluidTask.syncAim(bot);
                launchX=bot.mc.player.x;launchZ=bot.mc.player.z;
                flight=TasPearlFlight.begin(bot);
                bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                flightSince=bot.ticks;flying=true;throwsMade++;bot.tasPearlThrows++;
                bot.say("TAS_PEARL_THROW: landing="+landing+" yaw="+shot.yaw+" pitch="+shot.pitch);return false;
            }
        }
        // Keep walking legs short so new landing areas and newly loaded Endermen are reconsidered.
        double step=Math.min(d,16);
        child=new StructureTravelTask(new BlockPos(bot.mc.player.x+dx/d*step,bot.mc.player.y,bot.mc.player.z+dz/d*step),Math.min(radius,3));
        walkSince=bot.ticks;return false;
    }
    private PearlTrajectory.Shot findShot(LegacyBot bot,double dx,double dz,double distance) {
        boolean chestDestination=bot.mc.world.getBlockEntity(target) instanceof net.minecraft.block.entity.ChestBlockEntity;
        Map<Long,Integer> heights=new HashMap<>();
        PearlTrajectory.Shot best=null;BlockPos bestLanding=null;double bestScore=0;
        for(int length=(int)Math.min(64,distance);length>=8;length-=4)for(int side:new int[]{0,-3,3,-7,7,-14,14,-22,22}) {
            int x=(int)Math.floor(bot.mc.player.x+dx*length-dz*side),z=(int)Math.floor(bot.mc.player.z+dz*length+dx*side);
            int y=surface(bot,heights,x,z);
            if(y<1 || y-bot.mc.player.y>30 || bot.mc.player.y-y>64)continue;
            // A blacksmith roof is a broad dry landing, but leaves the chest
            // underneath inaccessible before the route has crafted a pickaxe.
            // Approach the building at entrance height instead.
            if(chestDestination && Math.hypot(x-target.getX(),z-target.getZ())<16 && y>target.getY()+1)continue;
            if(!PathSearch.standing(bot.navigation,x,y,z))continue;
            double progress=distance-Math.hypot(x-target.getX(),z-target.getZ());
            for(PearlTrajectory.Shot shot:PearlTrajectory.solveCandidates(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z,x+.5,y,z+.5)) {
                // Reject near-vertical lobs that spend several seconds making less
                // progress than a short walk. Rank all useful arcs by time saved.
                double score=progress/.28-shot.ticks()-4;
                if(!shot.useful(progress) || score<=bestScore)continue;
                BlockPos goal=new BlockPos(x,y,z);
                if(clearArc(bot,shot,goal)) {best=shot;bestLanding=goal;bestScore=score;}
            }

        }
        landing=bestLanding;return best;
    }
    private static int surface(LegacyBot bot,Map<Long,Integer> cache,int x,int z) {
        long key=((long)x<<32)^(z&0xffffffffL);
        return cache.computeIfAbsent(key,k->{
            // Tree canopies are valid pearl landings; the walking surface scan
            // deliberately ignores them and used to force long forest detours.
            for(int y=255;y>0;y--) {
                BlockPos p=new BlockPos(x,y,z);
                if(!bot.mc.world.isLoaded(p,false))return -1;
                net.minecraft.block.BlockState state=bot.mc.world.getBlockState(p);
                if(state.getBlock().getMaterial().isFluid() || state.getBlock().getCollisionBox(bot.mc.world,p,state)!=null)return y+1;
            }
            return -1;
        });
    }
    private boolean clearArc(LegacyBot bot,PearlTrajectory.Shot shot,BlockPos goal) {
        if(!clearArc(bot,shot,goal,0,0))return false;
        double[] end=shot.points.get(shot.points.size()-1);
        if(!PathSearch.walkStraight(bot.navigation,end[0],end[2],new PathSearch.Pos(goal.getX(),goal.getY(),goal.getZ())))return false;
        // Small collision margin for numerical error; the TAS launch hook
        // removes random spread without changing speed, gravity or drag.
        for(int ox=-1;ox<=1;ox++)for(int oz=-1;oz<=1;oz++)
            if((ox!=0 || oz!=0) && !clearArc(bot,shot,goal,ox,oz))return false;
        return true;
    }
    private boolean clearArc(LegacyBot bot,PearlTrajectory.Shot shot,BlockPos goal,int ox,int oz) {
        for(int i=1;i<shot.points.size();i++) {
            double[] a=shot.points.get(i-1),b=shot.points.get(i);
            double marginA=.03,marginB=.03;
            Vec3d from=new Vec3d(a[0]+ox*marginA,a[1],a[2]+oz*marginA),to=new Vec3d(b[0]+ox*marginB,b[1]-.02,b[2]+oz*marginB);
            if(!bot.mc.world.isLoaded(new BlockPos(to.x,to.y,to.z),false))return false;
            for(Object entity:bot.mc.world.loadedEntities)if(entity instanceof net.minecraft.entity.LivingEntity && entity!=bot.mc.player) {
                Box box=((net.minecraft.entity.Entity)entity).getBoundingBox().expand(.5,.5,.5);
                if(box.contains(from) || box.method_585(from,to)!=null)return false;
            }
            BlockHitResult hit=bot.mc.world.rayTrace(from,to);
            if(hit!=null) {
                BlockPos floor=hit.getBlockPos();
                return i==shot.points.size()-1 && hit.direction==Direction.UP && floor.getY()==goal.getY()-1
                        && Math.abs(floor.getX()-goal.getX())<=2 && Math.abs(floor.getZ()-goal.getZ())<=2
                        && PathSearch.standing(bot.navigation,floor.getX(),floor.getY()+1,floor.getZ());
            }
        }
        return false;
    }
    public void stop(LegacyBot bot) {if(flight!=null)flight.close();if(child!=null)child.stop(bot);if(combat!=null)combat.stop(bot);bot.releaseMovement();bot.key(bot.mc.options.useKey,false);bot.navigation.reset();}
}
