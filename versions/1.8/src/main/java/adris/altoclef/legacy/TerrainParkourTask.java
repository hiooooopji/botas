package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.util.math.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Plan on a worker while the game runs, then correct movement from live position and velocity. */
final class TerrainParkourTask implements LegacyTask {
    private BlockPos destination;
    private Goal preciseGoal;
    private long planningStarted;
    private int stalePlans,settlingTicks;
    private Future<BlockPos> preparation;
    private volatile boolean cancelled;
    private int settleUntil;
    private Future<ParkourCoursePlan.Tape> planning;
    private ExecutorService worker;
    private List<Solid> geometry;
    private State start;
    private boolean extensionAttempted;
    private ParkourCoursePlan.Placement extension;
    ParkourPlayback playback;
    boolean extensionPlaced,extensionAirborne,complete;
    int jumps,simulatedTicks;
    double maxError;
    TerrainParkourTask(BlockPos destination) {this.destination=destination;}
    TerrainParkourTask(double x,double y,double z,boolean preciseX,boolean preciseZ) {
        destination=new BlockPos(x,y,z);
        preciseGoal=new Goal(x+(preciseX?-.2:.05),x+(preciseX?.2:.95),y,z+(preciseZ?-.2:.05),z+(preciseZ?.2:.95));
    }
    private String destinationLabel() {return preciseGoal==null?String.valueOf(destination):String.format(java.util.Locale.ROOT,"%.3f %.3f %.3f",(preciseGoal.x0+preciseGoal.x1)/2,preciseGoal.y,(preciseGoal.z0+preciseGoal.z1)/2);}
    public String describe() {return "parkour to "+destinationLabel()+(playback==null?" -> rehearsing whole course":" -> live steering tick "+playback.index+"/"+playback.tape.frames.size());}
    public boolean tick(LegacyBot bot) {
        if(destination==null) {
            if(preparation==null) {LegacyTickControl.normal();preparation=ParkourPractice.prepareTriple(bot,()->cancelled);return false;}
            if(!preparation.isDone())return false;
            try {destination=preparation.get();}catch(Exception e) {throw new TaskFailure("Triple neo setup failed: "+e.getMessage());}
            settleUntil=bot.ticks+10;bot.say("Triple neo ready. Destination feet coordinates: "+destination);return false;
        }
        if(bot.ticks<settleUntil)return false;
        if(playback!=null) {
            boolean done=playback.tick(bot);simulatedTicks=playback.simulatedTicks;jumps=playback.jumps;maxError=playback.maxError;
            extensionPlaced=extension!=null && playback.aimedPlacements>0;extensionAirborne=extensionPlaced && playback.airbornePlacements>0;
            if(done) {complete=true;bot.say("TAS_PARKOUR_COMPLETE: "+simulatedTicks+" uninterrupted ticks, "+jumps+" jumps; destination "+destination+"; path deviation="+maxError+"; live corrections="+playback.corrections);}
            return done;
        }
        if(worker==null) {
            LegacyPlayerState.requirePrivate(bot.mc.getServer());
            if(bot.mc.player.abilities.creativeMode)throw new TaskFailure("Switch to Survival before parkour; Creative jump inputs can toggle flight.");
            if(!bot.mc.player.onGround || bot.mc.player.abilities.flying || WaterEscapeTask.wet(bot) || bot.mc.player.getHungerManager().getFoodLevel()<=6)
                throw new TaskFailure("Start rehearsal on dry, solid ground with flight off and enough hunger to sprint.");
            LegacyTickControl.normal();bot.releaseMovement();start=ParkourPlayback.state(bot);
            if(Math.abs(start.vx)+Math.abs(start.vz)>.001) {if(++settlingTicks>30)throw new TaskFailure("Stand still on the starting platform before planning.");return false;}
            geometry=ParkourPlayback.capture(bot,start,destination);
            worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Botas full parkour rehearsal");t.setDaemon(true);return t;});
            submit();return false;
        }
        if(!planning.isDone()) {
            if(System.nanoTime()-planningStarted>60_000_000_000L) {planning.cancel(true);throw new TaskFailure("Route search reached its 60-second limit; game remains at normal speed.");}
            return false;
        }
        ParkourCoursePlan.Tape tape;
        try {tape=planning.get();}
        catch(Exception failure) {
            if(!extensionAttempted) {extensionAttempted=true;if(chooseExtension(bot)) {submit();return false;}}
            throw new TaskFailure("Could not rehearse the whole course: "+failure.getMessage());
        }
        State now=ParkourPlayback.state(bot);
        if(now.error(tape.start)>.01 || ParkourFeedback.velocityError(now,tape.start)>.01) {
            if(!now.ground || ++stalePlans>2)throw new TaskFailure("Starting position changed while planning; stand still and retry.");
            start=now;geometry=ParkourPlayback.capture(bot,start,destination);submit();return false;
        }
        if(extension!=null && !ParkourCoursePlan.addExtension(tape,extension,geometry))throw new TaskFailure("No visible extension placement before landing.");
        worker.shutdown();
        if(!tape.moves.isEmpty())bot.say("Rehearsed course moves: "+String.join(" -> ",tape.moves)+"; "+tape.frames.size()+" continuous input ticks.");
        boolean placements=false;for(ParkourCoursePlan.Frame frame:tape.frames)if(!frame.placements().isEmpty())placements=true;
        playback=new ParkourPlayback(tape,placements?null:finishGoal());
        return tick(bot);
    }
    private Goal finishGoal() {return preciseGoal!=null?preciseGoal:ParkourCoursePlan.goal(new PathSearch.Pos(destination.getX(),destination.getY(),destination.getZ()));}
    private void submit() {
        planningStarted=System.nanoTime();
        final List<Solid> snapshot=new ArrayList<>(geometry);if(extension!=null)snapshot.add(extension.block());
        final PathSearch.Pos to=new PathSearch.Pos(destination.getX(),destination.getY(),destination.getZ());
        planning=worker.submit(()-> {
            if(preciseGoal==null)return ParkourCoursePlan.route(start,to,snapshot);
            ParkourCoursePlan.Tape result=ParkourMoveSearch.route(start,preciseGoal,snapshot,ParkourMoves.recognize(snapshot));
            if(result==null)throw new IllegalStateException("No verified route to the requested feet height "+preciseGoal.y+".");return result;
        });
    }
    private boolean chooseExtension(LegacyBot bot) {
        if(preciseGoal!=null && Math.abs(preciseGoal.y-Math.rint(preciseGoal.y))>1e-6)return false;
        boolean blocks=false;
        for(String id:new String[]{"cobblestone","dirt","stone","planks","netherrack"})if(bot.findInventory(LegacyItems.parse(id))>=0)blocks=true;
        if(!blocks)return false;
        double best=Double.POSITIVE_INFINITY;
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
            BlockPos pos=destination.add(dx,-1,dz);double distance=Math.hypot(pos.getX()+.5-start.x,pos.getZ()+.5-start.z);
            if(distance<3 || distance>=best || !bot.mc.world.isAir(pos) || !bot.mc.world.isAir(pos.up()) || !bot.mc.world.isAir(pos.up(2)))continue;
            for(Direction face:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                BlockPos anchor=pos.offset(face.getOpposite());
                if(bot.mc.world.getBlockState(anchor).getBlock().isFullCube() && !bot.interactiveSupport(anchor)) {
                    extension=new ParkourCoursePlan.Placement(pos.getX(),pos.getY(),pos.getZ(),anchor.getX(),anchor.getY(),anchor.getZ(),false);best=distance;break;
                }
            }
        }
        return extension!=null;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;if(preparation!=null)preparation.cancel(false);if(planning!=null)planning.cancel(true);if(worker!=null)worker.shutdownNow();
        bot.releaseMovement();LegacyTickControl.normal();
    }
}
