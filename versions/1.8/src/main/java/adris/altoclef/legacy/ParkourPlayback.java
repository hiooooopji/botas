package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Normal-clock playback; coordinate routes optionally steer from live position, velocity and collisions. */
final class ParkourPlayback {
    final ParkourCoursePlan.Tape tape;
    private Future<Boolean> confirmation;
    private boolean started;
    private final Goal liveGoal;
    private State predicted;
    int corrections,appliedTicks;
    double maxVelocityError;
    boolean complete;
    int index,simulatedTicks,jumps,floorBlocks,roofBlocks,aimedPlacements,airbornePlacements,extensionBlocks;
    double maxError;
    long startedAt,elapsedNanos;
    ParkourPlayback(ParkourCoursePlan.Tape tape) {this(tape,null);}
    ParkourPlayback(ParkourCoursePlan.Tape tape,Goal goal) {this.tape=tape;this.liveGoal=goal;}
    boolean running() {return started && index<tape.frames.size();}
    boolean tick(LegacyBot bot) {
        State actual=state(bot);
        if(!started) {
            if(actual.error(tape.start)>.01 || Math.abs(actual.vx-tape.start.vx)+Math.abs(actual.vy-tape.start.vy)+Math.abs(actual.vz-tape.start.vz)>.01)
                throw new TaskFailure("Player moved during rehearsal; run parkour again from stable ground.");
            LegacyTickControl.normal();started=true;startedAt=System.nanoTime();
            bot.say("PARKOUR_PLAYBACK_START: rehearsed "+tape.frames.size()+" ticks; running the whole course at normal speed.");
        } else if(index>0 && confirmation==null) {
            State expected=tape.frames.get(index-1).expected;
            double error=actual.error(expected);maxError=Math.max(maxError,error);simulatedTicks=appliedTicks;
            maxVelocityError=Math.max(maxVelocityError,ParkourFeedback.velocityError(actual,expected));
            if(liveGoal==null && (error>.08 || actual.ground!=expected.ground))
                throw new TaskFailure("Course or movement changed at tick "+index+" (error "+error+"); stopped the rehearsed run.");
        }
        if(LegacyTickControl.active())throw new TaskFailure("Playback clock was changed; continuous run stopped.");
        if(liveGoal!=null && predicted!=null && confirmation==null && actual.error(predicted)>2)
            throw new TaskFailure("Live physics prediction diverged at tick "+appliedTicks+"; actual="+motion(actual)+" predicted="+motion(predicted));
        if(confirmation!=null || index==tape.frames.size() && (liveGoal==null || liveGoal.reached(actual))) {
            bot.releaseMovement();
            if(confirmation==null) {
                elapsedNanos=System.nanoTime()-startedAt;
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();final int dimension=bot.mc.player.dimension;
                final String name=bot.mc.player.getGameProfile().getName();final State end=tape.end();
                confirmation=server.method_10815(()-> {
                    for(ParkourCoursePlan.Frame frame:tape.frames)for(ParkourCoursePlan.Placement p:frame.placements()) {
                        if(!server.getWorld(dimension).getBlockState(new BlockPos(p.x,p.y,p.z)).getBlock().isFullCube())return false;
                    }
                    net.minecraft.entity.player.ServerPlayerEntity p=server.getPlayerManager().getPlayer(name);
                    return p!=null && p.isAlive() && !p.abilities.flying && !p.abilities.creativeMode
                            && Math.abs(p.y-end.y)<.2 && Math.hypot(p.x-end.x,p.z-end.z)<2;
                });return false;
            }
            if(!confirmation.isDone())return false;
            try {if(!confirmation.get())throw new TaskFailure("Server did not confirm the rehearsed landing and placements.");}
            catch(java.util.concurrent.ExecutionException | InterruptedException e) {throw new TaskFailure("Could not verify parkour completion: "+e);}
            complete=true;return true;
        }
        if(WaterEscapeTask.wet(bot) || bot.mc.player.abilities.flying || liveGoal!=null && bot.mc.player.abilities.creativeMode || bot.mc.player.getHungerManager().getFoodLevel()<=6)
            throw new TaskFailure("Parkour movement conditions changed during playback.");
        if(appliedTicks>tape.frames.size()+40)throw new TaskFailure("Live steering could not reach the final landing.");
        ParkourCoursePlan.Frame frame=tape.frames.get(Math.min(index,tape.frames.size()-1));
        Input control=frame.input;
        if(liveGoal!=null) {
            List<Solid> live=captureLocal(bot,actual);
            ParkourFeedback.Decision decision=ParkourFeedback.choose(actual,tape,index,live);
            if(decision==null)throw new TaskFailure("No safe live steering trajectory at tick "+appliedTicks+"; "+motion(actual));
            control=decision.input;predicted=decision.predicted;if(decision.corrected)corrections++;
        }
        bot.releaseMovement();
        for(ParkourCoursePlan.Placement placement:frame.placements())click(bot,placement,actual);
        if(frame.placement==null || !frame.placement.lookDuringMotion) {bot.mc.player.yaw=control.yaw;bot.mc.player.pitch=frame.pitch;}
        bot.key(bot.mc.options.leftKey,control.strafe>0);bot.key(bot.mc.options.rightKey,control.strafe<0);bot.key(bot.mc.options.forwardKey,control.forward);bot.sprint(control.forward);bot.key(bot.mc.options.jumpKey,control.jump);
        if(control.jump && actual.ground)jumps++;
        index=Math.min(index+1,tape.frames.size());appliedTicks++;bot.timer.simulationTicks(appliedTicks);return false;
    }
    private static String motion(State s) {return String.format(java.util.Locale.ROOT,"pos=(%.5f,%.5f,%.5f) vel=(%.5f,%.5f,%.5f) grounded=%s",s.x,s.y,s.z,s.vx,s.vy,s.vz,s.ground);}
    static List<Solid> captureLocal(LegacyBot bot,State from) {
        List<Solid> solids=new ArrayList<>();
        for(int x=((int)Math.floor(from.x-8))>>4;x<=((int)Math.floor(from.x+8))>>4;x++)
            for(int z=((int)Math.floor(from.z-8))>>4;z<=((int)Math.floor(from.z+8))>>4;z++)
                if(!bot.mc.world.isLoaded(new BlockPos(x*16,(int)from.y,z*16),false))throw new TaskFailure("Live parkour reached an unloaded chunk.");
        for(Object value:bot.mc.world.doesBoxCollide(bot.mc.player,new Box(from.x-8,from.y-4,from.z-8,from.x+8,from.y+6,from.z+8))) {
            Box b=(Box)value;solids.add(new Solid(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
        }
        return TerrainParkourPlanner.merge(solids);
    }
    private void click(LegacyBot bot,ParkourCoursePlan.Placement place,State actual) {
        BlockPos target=new BlockPos(place.x,place.y,place.z),support=new BlockPos(place.sx,place.sy,place.sz);
        if(!bot.mc.world.isAir(target))throw new TaskFailure("A rehearsed placement space changed: "+target);
        int slot=-1;
        for(String id:new String[]{"cobblestone","dirt","stone","planks","netherrack"}) {slot=bot.findInventory(LegacyItems.parse(id));if(slot>=0)break;}
        if(slot<0)throw new TaskFailure("No building block left for the rehearsed placement.");
        bot.equip(slot);double[] aim=place.aim();bot.lookExact(aim[0],aim[1],aim[2]);
        double yaw=Math.toRadians(bot.mc.player.yaw),pitch=Math.toRadians(bot.mc.player.pitch),reach=Math.min(4.4,bot.mc.interactionManager.getReachDistance());
        Vec3d eye=new Vec3d(actual.x,actual.y+bot.mc.player.getEyeHeight(),actual.z);
        BlockHitResult hit=bot.mc.world.rayTrace(eye,new Vec3d(eye.x-Math.sin(yaw)*Math.cos(pitch)*reach,eye.y-Math.sin(pitch)*reach,eye.z+Math.cos(yaw)*Math.cos(pitch)*reach));
        if(hit==null || !support.equals(hit.getBlockPos()) || !hit.getBlockPos().offset(hit.direction).equals(target))
            throw new TaskFailure("The camera cannot see the rehearsed placement face.");
        LegacyFluidTask.syncAim(bot);
        if(!bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),support,hit.direction,hit.pos)
                || !bot.mc.world.getBlockState(target).getBlock().isFullCube())throw new TaskFailure("Rehearsed placement was rejected: "+target);
        bot.mc.player.swingHand();aimedPlacements++;if(!actual.ground)airbornePlacements++;
        if(index>=tape.bridgeStart && index<tape.bridgeEnd) {if(place.y<actual.y)floorBlocks++;else roofBlocks++;}
        else extensionBlocks++;
    }
    static State state(LegacyBot bot) {
        State s=new State(bot.mc.player.x,bot.mc.player.y,bot.mc.player.z,bot.mc.player.velocityX,bot.mc.player.velocityY,bot.mc.player.velocityZ,bot.mc.player.onGround);
        s.airSpeed=bot.mc.player.flyingSpeed;s.horizontalCollision=bot.mc.player.horizontalCollision;return s;
    }
    static List<Solid> capture(LegacyBot bot,State from,BlockPos to) {
        if(Math.abs(from.x-to.getX())>128 || Math.abs(from.z-to.getZ())>128 || Math.abs(from.y-to.getY())>24)
            throw new TaskFailure("Full-course rehearsal supports loaded courses within 128 blocks horizontally and 24 vertically.");
        // Include lateral run-ups and turns, not only the narrow start-to-goal corridor.
        final int detourMargin=16;
        int x0=(int)Math.floor(Math.min(from.x,to.getX()))-detourMargin,z0=(int)Math.floor(Math.min(from.z,to.getZ()))-detourMargin;
        int x1=(int)Math.ceil(Math.max(from.x,to.getX()))+detourMargin+1,z1=(int)Math.ceil(Math.max(from.z,to.getZ()))+detourMargin+1;
        for(int cx=x0>>4;cx<=x1>>4;cx++)for(int cz=z0>>4;cz<=z1>>4;cz++)
            if(!bot.mc.world.isLoaded(new BlockPos(cx*16,(int)from.y,cz*16),false))throw new TaskFailure("Load the entire course before rehearsing it.");
        Box bounds=new Box(x0,Math.min(from.y,to.getY())-3,z0,x1,Math.max(from.y,to.getY())+6,z1);
        List<Solid> solids=new ArrayList<>();
        for(Object value:bot.mc.world.doesBoxCollide(bot.mc.player,bounds)) {Box b=(Box)value;solids.add(new Solid(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));}
        return TerrainParkourPlanner.merge(solids);
    }
    void stop(LegacyBot bot) {bot.releaseMovement();LegacyTickControl.normal();}
}
