package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** A whole skill lane is rehearsed once, then played with real keys at normal speed. */
final class ParkourSkillsTask implements LegacyTask {
    final ParkourSkills.Skill skill;
    private final boolean build,run;
    private Future<BlockPos> preparation;
    private Future<ParkourCoursePlan.Tape> planning;
    private ExecutorService worker;
    private volatile boolean cancelled;
    private int settleUntil,previousDistance=-1;
    BlockPos origin;
    ParkourPlayback playback;
    boolean complete;
    ParkourSkillsTask(ParkourSkills.Skill skill,boolean build,boolean run) {this.skill=skill;this.build=build;this.run=run;}
    public String describe() {return "parkour skill "+skill.id+": "+(origin==null?"preparing arena":playback==null?"rehearsing":"tick "+playback.index+"/"+playback.tape.frames.size());}
    public boolean tick(LegacyBot bot) {
        if(origin==null) {
            LegacyPlayerState.requirePrivate(bot.mc.getServer());
            if(previousDistance<0) {previousDistance=bot.mc.options.viewDistance;bot.mc.options.viewDistance=Math.max(10,previousDistance);settleUntil=bot.ticks+40;return false;}
            if(bot.ticks<settleUntil)return false;
            if(build) {
                if(preparation==null) {preparation=ParkourMapBuilder.prepareSkills(bot,skill,()->cancelled);return false;}
                if(!preparation.isDone())return false;
                try {origin=preparation.get();}catch(Exception e) {throw new TaskFailure("Could not build skills arena: "+e.getMessage());}
            } else {
                BlockPos feet=new BlockPos(bot.mc.player);
                if(bot.mc.world.getBlockState(feet.down()).getBlock()!=Block.get("emerald_block"))throw new TaskFailure("Stand on the selected lane's emerald START block.");
                origin=feet.add(-(int)skill.startX,-1,-4);
            }
            settleUntil=bot.ticks+20;return false;
        }
        if(bot.ticks<settleUntil)return false;
        if(!run) {complete=true;bot.say("16-lane skills arena ready. Stand on a lane's emerald start and use @parkour skills run <name>. Names: "+ParkourSkills.names());return true;}
        if(playback==null) {
            if(planning==null) {
                if(!bot.mc.player.onGround || bot.mc.player.abilities.flying || WaterEscapeTask.wet(bot))throw new TaskFailure("Start on dry ground with flight off.");
                // Verify the selected lane before rehearsal; a wrong lane name must not quietly run another obstacle.
                for(int[] b:skill.boxes)for(int x=b[0];x<=b[3];x++)for(int y=b[1];y<=b[4];y++)for(int z=b[2];z<=b[5];z++)
                    if(!bot.mc.world.getBlockState(origin.add(x,y,z)).getBlock().isFullCube())throw new TaskFailure("Lane geometry does not match "+skill.id+".");
                LegacyTickControl.beginPlanner(bot);bot.releaseMovement();final State start=ParkourPlayback.state(bot);
                final List<Solid> geometry=ParkourPlayback.capture(bot,start,origin.add(25,-1,8));
                worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Botas skill rehearsal");t.setDaemon(true);return t;});
                planning=worker.submit(()->ParkourSkills.rehearse(skill,start,geometry,origin.getX(),origin.getY(),origin.getZ()));return false;
            }
            if(!planning.isDone())return false;
            try {playback=new ParkourPlayback(planning.get());}catch(Exception e) {throw new TaskFailure("Skill rehearsal failed: "+e.getMessage());}
            worker.shutdown();
        }
        if(!playback.tick(bot))return false;
        complete=true;bot.say("PARKOUR_SKILL_PASS: "+skill.id+", "+playback.simulatedTicks+" continuous ticks, real movement keys.");return true;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;if(preparation!=null)preparation.cancel(false);if(planning!=null)planning.cancel(true);if(worker!=null)worker.shutdownNow();
        if(playback!=null)playback.stop(bot);else {bot.releaseMovement();LegacyTickControl.normal();}
        if(previousDistance>=0) {bot.mc.options.viewDistance=previousDistance;previousDistance=-1;}
    }
}
