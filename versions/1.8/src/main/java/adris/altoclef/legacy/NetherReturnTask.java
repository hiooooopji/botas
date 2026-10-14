package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Return through an existing Nether portal using normal movement and portal contact. */
final class NetherReturnTask implements LegacyTask {
    private BlockPos portal;
    private LegacyTask travel;
    private final ArrayDeque<BlockPos> chunks=new ArrayDeque<>();
    private Future<BlockPos> pending;
    private boolean scanning;
    private int ticks,contactTicks;
    NetherReturnTask(BlockPos remembered) {portal=remembered;}
    public String describe() {return "return to Overworld -> "+(travel!=null ? travel.describe() : portal==null ? "find existing Nether portal" : "enter portal at "+portal);}
    public void stop(LegacyBot bot) {if(travel!=null) travel.stop(bot);if(pending!=null) pending.cancel(false);bot.releaseMovement();bot.cancelMining();}
    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension==0) return true;
        if(++ticks>24000) throw new TaskFailure("Return to the Overworld timed out.");
        if(portal==null) {
            bot.releaseMovement();
            if(!scanning) {
                scanning=true;
                final int cx=bot.navigation.feet().x>>4,cz=bot.navigation.feet().z>>4;
                List<BlockPos> ordered=new ArrayList<>();
                for(int dx=-32;dx<=32;dx++) for(int dz=-32;dz<=32;dz++) ordered.add(new BlockPos(cx+dx,0,cz+dz));
                ordered.sort(Comparator.comparingInt(p->Math.abs(p.getX()-cx)+Math.abs(p.getZ()-cz)));
                chunks.addAll(ordered);
            }
            if(pending==null) {
                if(chunks.isEmpty()) throw new TaskFailure("No existing portal found in loaded Nether chunks. Return closer to your entry portal and retry @fortress.");
                BlockPos chunk=chunks.remove();
                pending=bot.mc.getServer().method_10815((java.util.concurrent.Callable<BlockPos>)()->findPortal(bot.mc.getServer().getWorld(-1),chunk));
                return false;
            }
            if(!pending.isDone()) return false;
            try {portal=pending.get();} catch(Exception e) {throw new TaskFailure("Portal scan failed: "+e.getMessage());}
            pending=null;
            if(portal!=null) bot.say("Return portal located at "+portal);
            return false;
        }
        if(bot.mc.world.isLoaded(portal,false) && bot.mc.world.getBlockState(portal).getBlock()!=Block.get("portal")) {
            portal=null;scanning=false;chunks.clear();return false;
        }
        BlockPos feet=new BlockPos(bot.mc.player);
        if(bot.mc.world.getBlockState(feet).getBlock()==Block.get("portal")) {
            if(travel!=null) {travel.stop(bot);travel=null;}
            bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.useKey,false);bot.closeOwnedScreen();
            if(++contactTicks>400) throw new TaskFailure("Standing in a portal but no dimension transfer occurred.");
            return false;
        }
        contactTicks=0;
        if(travel==null) travel=new NetherTravelTask(portal,false);
        if(travel.tick(bot)) {travel.stop(bot);travel=null;}
        return false;
    }
    private BlockPos findPortal(World world,BlockPos chunk) {
        int x0=chunk.getX()*16,z0=chunk.getZ()*16;
        if(!world.isLoaded(new BlockPos(x0+8,64,z0+8),false)) return null;
        for(int y=1;y<127;y++) for(int x=x0;x<x0+16;x++) for(int z=z0;z<z0+16;z++) {
            BlockPos p=new BlockPos(x,y,z);
            if(world.getBlockState(p).getBlock()==Block.get("portal") && world.getBlockState(p.down()).getBlock()==Block.get("obsidian")) return p;
        }
        return null;
    }
}
