package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

/** Break one real oak/dark-oak leaf, select its apple RNG roll, then collect the drop. */
final class TasLeafAppleTask implements LegacyTask {
    private final BlockPos destination;
    private BlockPos source;
    private Future<?> armed;
    private volatile TasLootRng.LeafSession session;
    private volatile boolean cancelled;
    private LegacyTask travel;
    private LoadedResourceScan scan;
    private final ResourceExploration exploration=new ResourceExploration();
    private int exploringUntil;
    private final AcquisitionTask pickup=new AcquisitionTask(LegacyItems.parse("apple"),1,"get");
    private int ticks,before=-1,brokenAt;
    boolean complete;
    TasLeafAppleTask(BlockPos destination) {this.destination=destination;}
    public String describe() {return "harvest guaranteed leaf apple"+(source==null?"":" at "+source);}
    public boolean tick(LegacyBot bot) {
        if(before<0) {LegacyPlayerState.requirePrivate(bot.mc.getServer());before=bot.count(LegacyItems.parse("apple"));}
        if(++ticks>6000)throw new TaskFailure("No reachable oak leaf was harvested on this route.");
        if(travel!=null) {if(travel.tick(bot)) {travel.stop(bot);travel=null;}return false;}
        if(source==null) {
            if(bot.ticks<exploringUntil) {exploration.tick(bot,"oak/dark-oak leaves");return false;}
            if(scan==null)scan=new LoadedResourceScan(bot.navigation.feet(),12);
            source=scan.poll(bot,(p,s)->appleLeaf(s) && reachableHeight(bot,p));
            if(source==null) {
                bot.releaseMovement();
                if(scan.exhausted()) {scan=null;exploringUntil=bot.ticks+160;bot.say("Looking beyond the village for an apple-bearing oak tree.");}
                return false;
            }
            exploration.suspend();bot.say("TAS_APPLE_TREE: reachable apple-bearing leaf at "+source);
            if(bot.mc.player.squaredDistanceToCenter(source)>36) {travel=new StructureTravelTask(source,3);return false;}
        }
        if(armed==null) {
            final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();final String name=bot.mc.player.getGameProfile().getName();
            final BlockPos pos=source;
            armed=server.submit(()-> {if(cancelled)return;session=new TasLootRng.LeafSession(server.getWorld(0),server.getPlayerManager().getPlayer(name),pos);TasLootRng.leaf=session;});return false;
        }
        if(!armed.isDone())return false;
        try {armed.get();}catch(Exception e) {throw new TaskFailure("Could not arm leaf RNG: "+e);}
        if(bot.mc.world.isAir(source)) {
            bot.cancelMining();
            if(brokenAt==0)brokenAt=bot.ticks;
            if(session.rolled && bot.count(LegacyItems.parse("apple"))>before) {complete=true;bot.say("TAS_LEAF_APPLE: real leaf broken and its apple collected.");return true;}
            if(bot.ticks-brokenAt>160)throw new TaskFailure("Leaf broke but its apple was not collected.");
            pickup.pickup(bot);return false;
        }
        // Shears and Silk Touch would bypass the apple table; break with an ordinary non-tool slot.
        int slot=-1;
        for(int i=0;i<36;i++) {ItemStack s=bot.mc.player.inventory.main[i];if(s==null || !s.isDamageable()) {slot=i;break;}}
        if(slot<0)throw new TaskFailure("Free a slot to harvest a leaf without shears/Silk Touch.");
        bot.mine(source,slot);return false;
    }
    private static boolean appleLeaf(BlockState s) {
        return s.getBlock()==Block.get("leaves") && (s.getBlock().getData(s)&3)==0
                || s.getBlock()==Block.get("leaves2") && (s.getBlock().getData(s)&3)==1;
    }
    private static boolean reachableHeight(LegacyBot bot,BlockPos leaf) {
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=0;dy<=3;dy++)
            if(PathSearch.standing(bot.navigation,leaf.getX()+dx,leaf.getY()-dy,leaf.getZ()+dz))return true;
        return false;
    }
    public void stop(LegacyBot bot) {
        cancelled=true;if(session!=null)session.close();if(travel!=null)travel.stop(bot);exploration.suspend();pickup.stop(bot);bot.cancelMining();bot.releaseMovement();
    }
}
