package adris.altoclef.legacy;

import java.util.List;
import java.util.concurrent.Future;
import net.minecraft.util.math.BlockPos;

/** Builds the precise fixture, rehearses all three momentum jumps and the neo, then plays once. */
final class HeadNeoTask implements LegacyTask {
    private Future<BlockPos> preparation;
    private volatile boolean cancelled;
    private int settleUntil;
    BlockPos origin;
    ParkourPlayback playback;
    boolean complete;
    public String describe() {return "flat momentum head-to-head neo: "+(origin==null?"preparing":playback==null?"rehearsing":"tick "+playback.index+"/"+playback.tape.frames.size());}
    public boolean tick(LegacyBot bot) {
        if(origin==null) {
            if(preparation==null) {LegacyTickControl.normal();preparation=ParkourMapBuilder.prepareHeadNeo(bot,()->cancelled);return false;}
            if(!preparation.isDone())return false;
            try {origin=preparation.get();}catch(Exception e) {throw new TaskFailure("Could not build head neo: "+e.getMessage());}
            settleUntil=bot.ticks+20;return false;
        }
        if(bot.ticks<settleUntil)return false;
        if(playback==null) {
            if(!bot.mc.player.onGround || bot.mc.player.abilities.flying || WaterEscapeTask.wet(bot))throw new TaskFailure("Head neo must start on dry ground with flight off.");
            ParkourPhysics.State start=ParkourPlayback.state(bot);
            List<ParkourPhysics.Solid> geometry=ParkourPlayback.capture(bot,start,origin.add(3,0,2));
            try {playback=new ParkourPlayback(ParkourHeadNeo.rehearse(start,geometry,origin.getX(),origin.getY(),origin.getZ()));}
            catch(IllegalStateException e) {throw new TaskFailure(e.getMessage());}
        }
        if(!playback.tick(bot))return false;
        complete=true;bot.say("HEAD_NEO_PASS: three flat momentum jumps, diagonal head-to-head neo, stable landing; "+playback.simulatedTicks+" continuous ticks.");return true;
    }
    public void stop(LegacyBot bot) {cancelled=true;if(preparation!=null)preparation.cancel(false);if(playback!=null)playback.stop(bot);else {bot.releaseMovement();LegacyTickControl.normal();}}
}
