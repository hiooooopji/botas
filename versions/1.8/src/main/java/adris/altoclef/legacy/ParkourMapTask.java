package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Builds or replays the marathon map. Only the initial map setup edits server blocks. */
final class ParkourMapTask implements LegacyTask {
    private final boolean build,run;
    private Future<BlockPos> preparation;
    private Future<ParkourCoursePlan.Tape> planning;
    private ExecutorService worker;
    private volatile boolean cancelled;
    private int settleUntil,chunkDeadline,previousDistance=-1;
    private final int[] neoColumns=new int[3];
    BlockPos origin;
    ParkourPlayback playback;
    boolean complete,firstGap,secondGap,upNeo;
    int neos,simulatedTicks;
    ParkourMapTask(boolean build,boolean run) {this.build=build;this.run=run;}
    public String describe() {return "parkour marathon: "+(origin==null?"creating map":!run?"map ready":playback==null?"rehearsing all stages":"continuous tick "+playback.index+"/"+playback.tape.frames.size());}
    public boolean tick(LegacyBot bot) {
        if(origin==null) {
            LegacyPlayerState.requirePrivate(bot.mc.getServer());
            if(previousDistance<0) {
                previousDistance=bot.mc.options.viewDistance;bot.mc.options.viewDistance=Math.max(10,previousDistance);
                settleUntil=bot.ticks+40;return false;
            }
            if(bot.ticks<settleUntil)return false;
            if(build) {
                if(preparation==null) {LegacyTickControl.normal();preparation=ParkourMapBuilder.prepare(bot,()->cancelled);return false;}
                if(!preparation.isDone())return false;
                try {origin=preparation.get();}catch(Exception e) {throw new TaskFailure("Could not create parkour map: "+e.getMessage());}
            } else {
                origin=new BlockPos(bot.mc.player).add(0,-1,-1);
                if(bot.mc.world.getBlockState(origin.add(0,0,1)).getBlock()!=Block.get("emerald_block")
                        || bot.mc.world.getBlockState(origin.add(1,0,1)).getBlock()!=Block.get("stone"))
                    throw new TaskFailure("Stand on the emerald START block before @parkour runmap.");
            }
            settleUntil=bot.ticks+20;chunkDeadline=bot.ticks+400;
            bot.say("Marathon map at "+origin+": 3 triple neos -> 7-block gap + 3-block extension -> Andromeda -> 7-block gap + 3-block extension -> up-one neo -> 5 normal jumps. Use @parkour runmap from the emerald start.");return false;
        }
        if(bot.ticks<settleUntil)return false;
        if(!run) {complete=true;return true;}
        if(playback==null) {
            if(planning==null) {
                boolean loaded=true;
                for(int cx=(origin.getX()-5)>>4;cx<=(origin.getX()+130)>>4;cx++)for(int cz=(origin.getZ()-5)>>4;cz<=(origin.getZ()+52)>>4;cz++)
                    loaded&=bot.mc.world.isLoaded(new BlockPos(cx*16,origin.getY(),cz*16),false);
                if(!loaded) {if(bot.ticks>chunkDeadline)throw new TaskFailure("The full map did not load in time.");bot.releaseMovement();return false;}
                if(!bot.mc.player.onGround || bot.mc.player.abilities.flying || WaterEscapeTask.wet(bot))throw new TaskFailure("Start the map on dry ground with flight off.");
                if(bot.count(LegacyItems.parse("cobblestone"))<38)throw new TaskFailure("The map needs 38 cobblestone: 6 extension blocks and 32 bridge blocks.");
                LegacyTickControl.beginPlanner(bot);bot.releaseMovement();final State from=ParkourPlayback.state(bot);
                final List<Solid> geometry=ParkourPlayback.capture(bot,from,origin.add(124,3,46));
                worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Botas marathon rehearsal");t.setDaemon(true);return t;});
                planning=worker.submit(()->ParkourMarathonLayout.rehearse(from,geometry,origin.getX(),origin.getY(),origin.getZ()));return false;
            }
            if(!planning.isDone())return false;
            try {playback=new ParkourPlayback(planning.get());}catch(Exception e) {throw new TaskFailure("Marathon rehearsal failed: "+e.getMessage());}
            worker.shutdown();
        }
        double x=bot.mc.player.x-origin.getX(),y=bot.mc.player.y-origin.getY(),z=bot.mc.player.z-origin.getZ();
        if(!bot.mc.player.onGround && y>=1) {
            if(z<.7 || z>2.3)for(int neo=0;neo<3;neo++)for(int col=0;col<3;col++)
                if(x>ParkourMarathonLayout.NEOS[neo]+col && x<ParkourMarathonLayout.NEOS[neo]+col+1)neoColumns[neo]|=1<<col;
            if(x>66 && x<73)firstGap=true;if(x>104 && x<111)secondGap=true;
            if(x>118 && x<119 && (z<.7 || z>2.3))upNeo=true;
        }
        neos=0;for(int mask:neoColumns)if(mask==7)neos++;
        boolean done=playback.tick(bot);simulatedTicks=playback.simulatedTicks;if(!done)return false;
        if(neos!=3 || !firstGap || !secondGap || !upNeo || playback.extensionBlocks!=6 || playback.floorBlocks!=16 || playback.roofBlocks!=16)
            throw new TaskFailure("Marathon did not complete every required obstacle and placement.");
        complete=true;bot.say("PARKOUR_MAP_PASS: 3 triple neos, two 7-block gaps with 3-block extensions, Andromeda bridge, up-one neo, 5 normal jumps; "+simulatedTicks+" uninterrupted ticks.");return true;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;if(preparation!=null)preparation.cancel(false);if(planning!=null)planning.cancel(true);if(worker!=null)worker.shutdownNow();
        if(playback!=null)playback.stop(bot);else {bot.releaseMovement();LegacyTickControl.normal();}
        if(previousDistance>=0) {bot.mc.options.viewDistance=previousDistance;previousDistance=-1;}
    }
}
