package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.util.math.BlockPos;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Online navigation: live collision checks and one short-horizon control per client tick. */
final class SmoothParkourTask implements LegacyTask {
    private final Goal goal;
    private final BlockPos destination;
    private final ParkourRealtimeNavigator navigator;
    private List<Solid> map;
    private Future<Boolean> confirmation;
    private int idle;
    private State last;
    boolean complete;
    int ticks,jumps,firstMovementTick=-1;
    long maxControlNanos;
    SmoothParkourTask(double x,double y,double z,boolean preciseX,boolean preciseZ){
        destination=new BlockPos(x,y,z);
        goal=new Goal(x+(preciseX?-.2:.05),x+(preciseX?.2:.95),y,z+(preciseZ?-.2:.05),z+(preciseZ?.2:.95));
        navigator=new ParkourRealtimeNavigator(goal);
    }
    public String describe(){return "parkour-smooth to "+destination+" -> live navigation tick "+ticks;}
    public boolean tick(LegacyBot bot){
        if(ticks==0){
            LegacyPlayerState.requirePrivate(bot.mc.getServer());LegacyTickControl.normal();
            if(!bot.mc.player.onGround)throw new TaskFailure("Start parkour-smooth on solid ground.");
        }
        if(bot.mc.player.abilities.creativeMode || bot.mc.player.abilities.flying || WaterEscapeTask.wet(bot) || bot.mc.player.getHungerManager().getFoodLevel()<=6)
            throw new TaskFailure("Parkour-smooth needs dry Survival movement and enough hunger to sprint.");
        if(LegacyTickControl.active())throw new TaskFailure("Parkour-smooth requires the normal game clock.");
        State actual=ParkourPlayback.state(bot);ticks++;
        if(confirmation!=null){
            bot.releaseMovement();if(!confirmation.isDone())return false;
            try{if(!confirmation.get())throw new TaskFailure("Server did not confirm the smooth navigation landing.");}
            catch(java.util.concurrent.ExecutionException | InterruptedException e){throw new TaskFailure("Could not confirm smooth navigation: "+e);}
            complete=true;bot.say("PARKOUR_SMOOTH_COMPLETE: "+ticks+" live ticks, "+jumps+" jumps; no whole-course rehearsal.");return true;
        }
        if(navigator.reached(actual)){
            bot.releaseMovement();final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();final String name=bot.mc.player.getGameProfile().getName();
            confirmation=server.method_10815(()->{
                net.minecraft.entity.player.ServerPlayerEntity p=server.getPlayerManager().getPlayer(name);
                return p!=null && p.isAlive() && !p.abilities.flying && !p.abilities.creativeMode && goal.reached(new State(p.x,p.y,p.z,p.velocityX,p.velocityY,p.velocityZ,p.onGround));
            });return false;
        }
        long began=System.nanoTime();
        if(map==null || ticks%10==0)map=ParkourPlayback.capture(bot,actual,destination);
        List<Solid> live=ParkourPlayback.captureLocal(bot,actual);
        Input input=navigator.next(actual,map,live);
        if(input==null){
            map=ParkourPlayback.capture(bot,actual,destination);navigator.refresh();input=navigator.next(actual,map,live);
            if(input==null)throw new TaskFailure("No safe local move toward the destination; smooth navigation stopped.");
        }
        maxControlNanos=Math.max(maxControlNanos,System.nanoTime()-began);
        if(last!=null && actual.error(last)<.005)idle++;else idle=0;
        if(idle>60 || ticks>6000)throw new TaskFailure("Smooth navigation made no progress or reached its time limit.");last=actual;
        bot.releaseMovement();bot.mc.player.yaw=input.yaw;
        bot.key(bot.mc.options.forwardKey,input.forward);bot.key(bot.mc.options.leftKey,input.strafe>0);bot.key(bot.mc.options.rightKey,input.strafe<0);bot.key(bot.mc.options.jumpKey,input.jump);bot.sprint(input.forward);
        if(firstMovementTick<0 && (input.forward || input.strafe!=0))firstMovementTick=ticks;
        if(input.jump && actual.ground)jumps++;
        bot.timer.simulationTicks(ticks);return false;
    }
    public void stop(LegacyBot bot){bot.releaseMovement();if(confirmation!=null)confirmation.cancel(false);}
}
