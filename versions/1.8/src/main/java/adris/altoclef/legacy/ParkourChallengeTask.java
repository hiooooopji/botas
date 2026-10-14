package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.util.math.BlockPos;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Rehearse every jump and bridge click before beginning one continuous Survival run. */
final class ParkourChallengeTask implements LegacyTask {
    private static final int[] LANDINGS={17,37,57,62,72,76};
    private Future<BlockPos> preparation;
    private Future<ParkourCoursePlan.Tape> planning;
    private ExecutorService worker;
    private volatile boolean cancelled;
    private int settleUntil;
    private final int[] neoColumns=new int[3];
    BlockPos origin;
    ParkourPlayback bridge;
    boolean complete,fourBlockJump;
    int neos,simulatedTicks;
    public String describe() {return "parkour challenge: "+(bridge==null?"rehearsing all 3 triple neos, gap and bridge":"continuous tick "+bridge.index+"/"+bridge.tape.frames.size());}
    public boolean tick(LegacyBot bot) {
        if(origin==null) {
            if(preparation==null) {LegacyTickControl.normal();preparation=ParkourChallenge.prepare(bot,()->cancelled);return false;}
            if(!preparation.isDone())return false;
            try {origin=preparation.get();}catch(Exception e) {throw new TaskFailure("Challenge setup failed: "+e.getMessage());}
            settleUntil=bot.ticks+20;bot.say("Challenge ready: rehearsing three triple neos, the four-block gap and every Andromeda placement before moving.");return false;
        }
        if(bot.ticks<settleUntil)return false;
        if(bridge==null) {
            if(planning==null) {
                LegacyTickControl.beginPlanner(bot);bot.releaseMovement();
                final State start=ParkourPlayback.state(bot);
                final List<Solid> geometry=ParkourPlayback.capture(bot,start,origin.add(100,1,1));
                final List<PathSearch.Pos> goals=new ArrayList<>();
                for(int x:LANDINGS)goals.add(new PathSearch.Pos(origin.getX()+x,origin.getY()+1,origin.getZ()+1));
                worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Botas challenge rehearsal");t.setDaemon(true);return t;});
                planning=worker.submit(()-> {
                    ParkourCoursePlan.Tape tape=ParkourCoursePlan.through(start,goals,geometry);
                    ParkourCoursePlan.bridge(tape,geometry,origin.getX()+ParkourChallenge.BRIDGE_START,origin.getX()+ParkourChallenge.BRIDGE_END,origin.getY(),origin.getZ()+1);
                    return tape;
                });return false;
            }
            if(!planning.isDone())return false;
            try {bridge=new ParkourPlayback(planning.get());}catch(Exception e) {throw new TaskFailure("Full challenge rehearsal failed: "+e.getMessage());}
            worker.shutdown();
        }
        double x=bot.mc.player.x-origin.getX(),z=bot.mc.player.z-origin.getZ();
        if(!bot.mc.player.onGround && bot.mc.player.y>=origin.getY()+1) {
            if(z<.7 || z>2.3)for(int neo=0;neo<3;neo++)for(int col=0;col<3;col++)
                if(x>ParkourChallenge.WALLS[neo]+col && x<ParkourChallenge.WALLS[neo]+col+1)neoColumns[neo]|=1<<col;
            if(x>66 && x<70)fourBlockJump=true;
        }
        neos=0;for(int mask:neoColumns)if(mask==7)neos++;
        boolean done=bridge.tick(bot);simulatedTicks=bridge.simulatedTicks;
        if(!done)return false;
        if(neos!=3 || !fourBlockJump || bridge.floorBlocks!=16 || bridge.roofBlocks!=16 || bridge.airbornePlacements<8)
            throw new TaskFailure("Rehearsed challenge bypassed a required obstacle.");
        complete=true;
        bot.say("PARKOUR_CHALLENGE_PASS: three triple neos, four-block jump, 32 real Andromeda placements; "+simulatedTicks+" uninterrupted ticks, "+String.format(java.util.Locale.ROOT,"%.2f",bridge.elapsedNanos/1e9)+" seconds playback.");return true;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;if(preparation!=null && !preparation.isDone())preparation.cancel(false);
        if(planning!=null)planning.cancel(true);if(worker!=null)worker.shutdownNow();
        if(bridge!=null)bridge.stop(bot);else {bot.releaseMovement();LegacyTickControl.normal();}
    }
}
