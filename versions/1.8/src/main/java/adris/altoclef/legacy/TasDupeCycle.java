package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;

/** Ten simulation ticks per automated TAS batch, including authoritative pickup/withdrawal. */
final class TasDupeCycle {
    private final int started;
    private final boolean tas;
    private Future<Boolean> verified;
    TasDupeCycle(LegacyBot bot) {started=bot.ticks;tas=bot.tasTravel();}
    boolean finish(LegacyBot bot,Map<LegacyItems.Target,Integer> expected) {
        if(verified==null) {
            final Map<LegacyItems.Target,Integer> targets=new LinkedHashMap<>(expected);
            final String name=bot.mc.player.getGameProfile().getName();
            final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
            verified=server.method_10815(()-> {
                ServerPlayerEntity p=server.getPlayerManager().getPlayer(name);
                if(p==null || p.inventory.getCursorStack()!=null)return false;
                for(Map.Entry<LegacyItems.Target,Integer> goal:targets.entrySet()) {
                    int count=0;for(ItemStack stack:p.inventory.main)if(goal.getKey().matches(stack))count+=stack.count;
                    if(count<goal.getValue())return false;
                }
                return true;
            });return false;
        }
        if(!verified.isDone())return false;
        try {if(!verified.get())throw new TaskFailure("Server did not confirm the duplicated inventory.");}
        catch(TaskFailure e){throw e;}catch(Exception e){throw new TaskFailure("Duplication verification failed: "+e);}
        int elapsed=bot.ticks-started;
        if(tas && elapsed<10)return false;
        if(tas) {
            bot.tasDupeCycles++;bot.maxTasDupeTicks=Math.max(bot.maxTasDupeTicks,elapsed);
            bot.say("TAS_DUPE_CYCLE: "+elapsed+" ticks, server verified "+expected);
        }
        return true;
    }
}
