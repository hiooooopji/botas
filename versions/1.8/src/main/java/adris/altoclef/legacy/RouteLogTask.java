package adris.altoclef.legacy;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Exactly one nearby log, followed by pickup; never harvest the rest of its tree. */
final class RouteLogTask implements LegacyTask {
    private final BlockPos destination;
    private BlockPos source;
    private final Set<BlockPos> rejected=new HashSet<>();
    private final AcquisitionTask pickup=new AcquisitionTask(LegacyItems.anyLogs(),1,"get");
    private LegacyTask travel;
    private int ticks,brokenAt;
    int mined;
    RouteLogTask(BlockPos destination) {this.destination=destination;}
    public String describe() {return "mine one log on the chest route"+(source==null?"":" at "+source);}
    public boolean tick(LegacyBot bot) {
        if(++ticks>1800) throw new TaskFailure("Could not collect one log beside the chest route.");
        if(bot.count(LegacyItems.anyLogs())>0) return true;
        if(travel!=null) {if(travel.tick(bot)) {travel.stop(bot);travel=null;}return false;}
        if(mined>0) {
            if(bot.ticks-brokenAt>200) throw new TaskFailure("The single mined log was not picked up.");
            pickup.pickup(bot);return false;
        }
        if(source==null) {
            source=bot.nearestBlock(s -> s.getBlock()==Block.get("log") || s.getBlock()==Block.get("log2"),
                    16,rejected,p -> p.getY()<=bot.mc.player.y+3 && p.getY()>=bot.mc.player.y-4);
            if(source==null) {
                double dx=destination.getX()-bot.mc.player.x,dz=destination.getZ()-bot.mc.player.z,len=Math.hypot(dx,dz);
                if(len<20) throw new TaskFailure("No log was found along the route before reaching the village.");
                travel=new StructureTravelTask(new BlockPos(bot.mc.player.x+dx/len*12,bot.mc.player.y,bot.mc.player.z+dz/len*12),3);return false;
            }
        }
        if(bot.mc.world.isAir(source)) {mined++;brokenAt=bot.ticks;bot.cancelMining();return false;}
        try {bot.mine(source);} catch(TaskFailure e) {rejected.add(source);source=null;bot.cancelMining();bot.navigation.reset();}
        return false;
    }
    public void stop(LegacyBot bot) {pickup.stop(bot);if(travel!=null)travel.stop(bot);bot.cancelMining();bot.releaseMovement();}
}
