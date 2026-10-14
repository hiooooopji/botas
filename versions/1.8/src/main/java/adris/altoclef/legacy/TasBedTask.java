package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Collect an existing bed first, otherwise obtain one real wool and duplicate the recipe supply. */
final class TasBedTask implements LegacyTask {
    private LoadedResourceScan scan;
    private BlockPos bed,wool,source;
    private final Set<BlockPos> rejected=new HashSet<>();
    private LegacyTask child;
    private AcquisitionTask pickup;
    private boolean scanned,broken;
    private int sourceSince,brokenAt,phase;
    public String describe() {return "get one bed -> "+(child!=null?child.describe():source!=null?(broken?"collect drop at ":"break ")+source:"look for an existing bed or wool");}
    public boolean tick(LegacyBot bot) {
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(bot.count(LegacyItems.parse("bed"))>0) {stop(bot);return true;}
        if(phase==0) {
            if(!scanned) {
                bot.releaseMovement();
                if(scan==null)scan=new LoadedResourceScan(bot.navigation.feet(),4);
                BlockPos found=scan.poll(bot,(p,s)->!rejected.contains(p) && Math.abs(p.getY()-bot.mc.player.y)<=12
                        && (s.getBlock()==Block.get("bed") || s.getBlock()==Block.get("wool")));
                if(found!=null) {
                    if(bot.mc.world.getBlockState(found).getBlock()==Block.get("bed")) {
                        if(bed==null || bot.mc.player.squaredDistanceToCenter(found)<bot.mc.player.squaredDistanceToCenter(bed))bed=found;
                    } else if(wool==null || bot.mc.player.squaredDistanceToCenter(found)<bot.mc.player.squaredDistanceToCenter(wool))wool=found;
                }
                if(!scan.exhausted())return false;
                scanned=true;source=bed!=null?bed:wool;sourceSince=bot.ticks;
                if(source!=null) {
                    pickup=new AcquisitionTask(LegacyItems.parse(bed!=null?"bed":"wool"),1,"get");
                    bot.say(bed!=null?"Collecting an existing bed at "+source:"Collecting nearby wool for the bed at "+source);
                }
            }
            if(source!=null && (bed!=null || bot.count(LegacyItems.parse("wool"))==0)) {
                if(!broken && bot.mc.world.getBlockState(source).getBlock()!=(bed!=null?Block.get("bed"):Block.get("wool"))) {
                    broken=true;brokenAt=bot.ticks;bot.cancelMining();
                }
                if(broken) {
                    if(bot.ticks-brokenAt>160)throw new TaskFailure("The bed/wool drop was not collected.");
                    if(clearLampSupport(bot))return false;
                    pickup.pickup(bot);return false;
                }
                try {
                    if(bot.ticks-sourceSince>240)throw new TaskFailure("Bed material approach timed out.");
                    bot.mine(source);
                } catch(TaskFailure blocked) {
                    rejected.add(source);source=bed=wool=null;scan=null;scanned=false;
                    bot.cancelMining();bot.navigation.reset();
                }
                return false;
            }
            phase=1;
            if(bot.count(LegacyItems.parse("wool"))==0) {child=new AcquisitionTask(LegacyItems.parse("wool"),1,"get");return false;}
        }
        if(phase==1) {phase=2;child=new ResourceDupeTask(LegacyItems.parse("wool"),3);return false;}
        if(phase==2) {phase=3;child=new AcquisitionTask(LegacyItems.parse("bed"),1,"craft");return false;}
        throw new TaskFailure("The bed was not crafted or collected.");
    }
    private boolean clearLampSupport(LegacyBot bot) {
        if(bed!=null || bot.ticks-brokenAt<5)return false;
        for(Object entity:bot.mc.world.loadedEntities)if(entity instanceof net.minecraft.entity.ItemEntity) {
            net.minecraft.entity.ItemEntity item=(net.minecraft.entity.ItemEntity)entity;
            if(item.removed || !LegacyItems.parse("wool").matches(item.getItemStack())
                    || item.squaredDistanceToCenter(source)>16 || item.y<bot.mc.player.y+1.2)continue;
            BlockPos below=new BlockPos(item.x,item.y-.1,item.z);
            for(BlockPos support:new BlockPos[]{below,below.down()}) {
                Block block=bot.mc.world.getBlockState(support).getBlock();
                String id=String.valueOf(Block.REGISTRY.getIdentifier(block));
                // Village lamp drops can perch on the remaining wool or the
                // 1.5-block fence top. Break that support so the real item falls
                // to a reachable floor instead of trying to walk onto a fence.
                if(block==Block.get("wool") || id.endsWith("_fence") || id.equals("minecraft:fence")) {
                    if(bot.hit(support)!=null) {bot.mine(support);return true;}
                }
            }
        }
        return false;
    }
    public void stop(LegacyBot bot) {if(child!=null)child.stop(bot);if(pickup!=null)pickup.stop(bot);bot.cancelMining();bot.releaseMovement();}
}
