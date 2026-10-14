package adris.altoclef.legacy;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Harvest a spotted local patch; never explore when it cannot supply twenty. */
final class CarrotDetourTask implements LegacyTask {
    private final BlockPos patch;
    private final LegacyItems.Target carrot=LegacyItems.parse("carrot");
    private final AcquisitionTask drops=new AcquisitionTask(carrot,20,"get");
    private final Set<BlockPos> rejected=new HashSet<>();
    private BlockPos crop;
    private int ticks,waitUntil;
    CarrotDetourTask(BlockPos patch) {this.patch=patch;}
    public String describe() {return "collect nearby carrots x20";}
    public void stop(LegacyBot bot) {drops.stop(bot);bot.cancelMining();bot.releaseMovement();}
    public boolean tick(LegacyBot bot) {
        if(bot.count(carrot)>=20 || ++ticks>1200) return true;
        if(drops.pickup(bot)) return false;
        if(bot.ticks<waitUntil) return false;
        if(crop!=null && (!LegacyWorld.isLoaded(bot.mc.world,crop,false)
                || LegacyWorld.state(bot.mc.world,crop).getBlock()!=Block.get("carrots")
                || LegacyWorld.state(bot.mc.world,crop).getData()<7)) {
            crop=null;bot.cancelMining();waitUntil=bot.ticks+8;return false;
        }
        if(crop==null) {
            crop=bot.nearestBlock(state -> state.getBlock()==Block.get("carrots") && state.getData()>=7,
                    20,rejected,p -> Math.abs(p.x-patch.x)<=20 && Math.abs(p.z-patch.z)<=20 && Math.abs(p.y-patch.y)<=4);
            if(crop==null) return true;
        }
        try {bot.mine(crop);}
        catch(TaskFailure inaccessible) {rejected.add(crop);crop=null;bot.cancelMining();bot.navigation.reset();}
        return false;
    }
}
