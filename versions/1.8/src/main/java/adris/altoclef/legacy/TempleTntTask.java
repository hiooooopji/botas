package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Disarms a generated desert temple before mining its real TNT blocks. */
final class TempleTntTask implements LegacyTask {
    private final SurfaceStructureScan.Site site;
    private final List<BlockPos> tnt=new ArrayList<>();
    private LegacyTask child;
    private BlockPos plate;
    private final Set<BlockPos> blockedApproaches=new HashSet<>();
    private int ticks,initialCount,requiredInventory;
    private boolean scanned;
    private AcquisitionTask drops;
    private int pickupTicks;

    TempleTntTask(SurfaceStructureScan.Site site) {this.site=site;}
    public String describe() {return "disarm temple and collect TNT "+(initialCount-tnt.size())+"/"+initialCount
            +(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.navigation.disarmedTempleTnt=false;bot.navigation.reset();bot.cancelMining();bot.releaseMovement();}
    private void scan(LegacyBot bot) {
        int cx=site.center.getX(),cz=site.center.getZ();
        int top=Math.min(255,Math.max(site.bounds[4]+2,site.entry.getY()));
        int bottom=Math.max(1,site.bounds[1]-24);
        for(int x=cx-3;x<=cx+3;x++) for(int z=cz-3;z<=cz+3;z++) for(int y=bottom;y<=top;y++) {
            BlockPos p=new BlockPos(x,y,z);
            if(!bot.mc.world.isLoaded(p,false)) continue;
            Block block=bot.mc.world.getBlockState(p).getBlock();
            if(block==Block.get("tnt")) tnt.add(p);
            if(Math.abs(x-cx)<=1 && Math.abs(z-cz)<=1
                    && String.valueOf(Block.REGISTRY.getIdentifier(block)).contains("pressure_plate")) plate=p;
        }
        tnt.sort(Comparator.comparingDouble(p->bot.mc.player.squaredDistanceTo(p.getX()+.5,p.getY()+.5,p.getZ()+.5)));
        initialCount=tnt.size();scanned=true;
        requiredInventory=Math.max(9,bot.count(LegacyItems.parse("tnt"))+initialCount);
        bot.say("Temple TNT: "+initialCount+" blocks; disarming the pressure plate first.");
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>12000) throw new TaskFailure("Temple TNT collection timed out.");
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(!scanned) {scan(bot);return false;}
        if(plate!=null && !bot.mc.world.isAir(plate)) {
            if(bot.hit(plate)==null) {bot.navigation.approach(plate);return false;}
            bot.mine(plate);return false;
        }
        if(plate!=null) {plate=null;bot.navigation.reset();}
        bot.navigation.disarmedTempleTnt=true;
        if(tnt.removeIf(p->bot.mc.world.getBlockState(p).getBlock()!=Block.get("tnt"))) {
            blockedApproaches.clear();bot.navigation.reset();
        }
        if(!tnt.isEmpty()) {
            // Do not commit to one buried block: mining visible TNT and cover
            // blocks first opens more faces without walking into the trap floor.
            for(BlockPos target:tnt) if(bot.hit(target)!=null) {bot.mine(target);return false;}
            for(BlockPos target:tnt) {
                BlockPos cover=target.up();Block block=bot.mc.world.getBlockState(cover).getBlock();
                if(!bot.mc.world.isAir(cover) && block!=Block.get("tnt")
                        && bot.mc.world.getBlockEntity(cover)==null && bot.hit(cover)!=null) {
                    bot.mine(cover);return false;
                }
            }
            for(BlockPos target:tnt) {
                BlockPos cover=target.up();Block block=bot.mc.world.getBlockState(cover).getBlock();
                BlockPos approach=!bot.mc.world.isAir(cover) && block!=Block.get("tnt")
                        && bot.mc.world.getBlockEntity(cover)==null ? cover : target;
                if(blockedApproaches.contains(approach)) continue;
                try {bot.navigation.approach(approach);return false;}
                catch(TaskFailure blocked) {blockedApproaches.add(approach);bot.navigation.reset();}
            }
            // After a reload the player can be back on the roof while the
            // chamber shaft is already open. Plan a safe descent to an air
            // square above the TNT instead of retrying impossible walking
            // approaches from the surface.
            for(BlockPos target:tnt) {
                for(BlockPos approach:new BlockPos[]{target.up(),target.up().north(),target.up().south(),
                        target.up().east(),target.up().west()}) {
                    if(!bot.mc.world.isAir(approach) || !bot.navigation.floor(approach.getX(),approach.getY()-1,approach.getZ())) continue;
                    StoneAccessTask dig=StoneAccessTask.planOre(bot,approach,"temple TNT chamber");
                    if(dig!=null) {child=dig;blockedApproaches.clear();return false;}
                }
            }
            throw new TaskFailure("Cannot safely expose remaining temple TNT at "+tnt);
        }
        if(bot.count(LegacyItems.parse("tnt"))>=requiredInventory) return true;
        // The final drop may still be falling or waiting for its pickup delay.
        // This is collection, never a request to craft replacement TNT.
        if(++pickupTicks>600) throw new TaskFailure("Could not collect all temple TNT drops (have "
                +bot.count(LegacyItems.parse("tnt"))+"/"+requiredInventory+").");
        if(drops==null) drops=new AcquisitionTask(LegacyItems.parse("tnt"),requiredInventory,"get");
        if(!drops.pickup(bot)) bot.releaseMovement();
        return false;
    }
}
