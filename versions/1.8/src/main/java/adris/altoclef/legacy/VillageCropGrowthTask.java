package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Spends carried bone meal on real carrot and potato crops in the selected village. */
final class VillageCropGrowthTask implements LegacyTask {
    private final SurfaceStructureScan.Site village;
    private final List<BlockPos> crops=new ArrayList<>();
    private int ticks,nextUse,growthUses;
    private boolean scanned;
    VillageCropGrowthTask(SurfaceStructureScan.Site village) {this.village=village;}
    public String describe() {return "bone-meal village carrots and potatoes ("+growthUses+" uses)";}
    public void stop(LegacyBot bot) {bot.cancelMining();bot.releaseMovement();}
    private boolean vegetable(BlockState state) {
        String id=String.valueOf(Block.REGISTRY.getIdentifier(state.getBlock()));
        return id.equals("minecraft:carrots") || id.equals("minecraft:potatoes");
    }
    private void scan(LegacyBot bot) {
        int minX=Math.max(village.bounds[0]-3,village.center.getX()-64);
        int maxX=Math.min(village.bounds[3]+3,village.center.getX()+64);
        int minZ=Math.max(village.bounds[2]-3,village.center.getZ()-64);
        int maxZ=Math.min(village.bounds[5]+3,village.center.getZ()+64);
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            BlockPos column=new BlockPos(x,bot.navigation.feet().y,z);
            if(!bot.mc.world.isLoaded(column,false)) continue;
            int top=bot.mc.world.getHighestBlock(new BlockPos(x,0,z)).getY();
            for(int y=Math.max(1,top-4);y<=Math.min(254,top+1);y++) {
                BlockPos p=new BlockPos(x,y,z);
                if(vegetable(bot.mc.world.getBlockState(p))) crops.add(p);
            }
        }
        crops.sort(Comparator.comparingDouble(p->bot.mc.player.squaredDistanceTo(p.getX()+.5,p.getY()+.5,p.getZ()+.5)));
        scanned=true;
        bot.say("Village crop plots found: "+crops.size()+" carrots/potatoes.");
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>6000) throw new TaskFailure("Village crop growth timed out.");
        if(!scanned) {scan(bot);return false;}
        if(bot.count(LegacyItems.parse("dye:15"))==0 || crops.isEmpty()) return true;
        if(bot.ticks<nextUse) return false;
        BlockPos target=null;
        double best=Double.POSITIVE_INFINITY;
        for(BlockPos p:crops) {
            if(!bot.mc.world.isLoaded(p,false)) continue;
            BlockState state=bot.mc.world.getBlockState(p);
            if(!vegetable(state) || state.getBlock().getData(state)>=7) continue;
            double d=bot.mc.player.squaredDistanceTo(p.getX()+.5,p.getY()+.5,p.getZ()+.5);
            if(d<best) {best=d;target=p;}
        }
        if(target==null) return true;
        if(best>16 || bot.hit(target)==null) {bot.navigation.approach(target);return false;}
        int slot=bot.findInventory(LegacyItems.parse("dye:15"));
        if(slot<0) return true;
        BlockHitResult hit=bot.hit(target);
        if(hit==null) return false;
        bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
        bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,
                bot.mc.player.inventory.getMainHandStack(),target,hit.direction,hit.pos);
        growthUses++;nextUse=bot.ticks+3;
        return false;
    }
}
