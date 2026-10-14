package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.feature.FeatureState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/** Creative diagonal survey. Never generates chunks or asks the seed for remote locations. */
final class LegacyStrongholdSearchTask implements LegacyTask {
    private Future<List<int[]>> snapshot;
    private List<int[]> pieces = new ArrayList<>();
    private int nextSnapshot, pieceIndex, blockIndex;
    private boolean started;
    private BlockPos found;
    private double startX, startZ;

    BlockPos found() { return found; }
    double traveled(LegacyBot bot) {
        return Math.hypot(bot.mc.player.x-startX, bot.mc.player.z-startZ);
    }
    public String describe() {
        return found == null ? "stronghold survey: fly +X/-Z, render 32 chunks"
                : "stronghold found at " + found;
    }
    public boolean tick(LegacyBot bot) {
        if (bot.mc.getServer()==null || bot.mc.player.dimension!=0 || !bot.mc.player.abilities.creativeMode)
            throw new TaskFailure("@stronghold requires a Creative single-player Overworld.");
        if (!started) {
            started=true; startX=bot.mc.player.x; startZ=bot.mc.player.z;
            bot.mc.options.viewDistance=32;
            bot.say("Searching loaded chunks for stronghold blocks; flying +X/-Z at render distance 32. Use @stop to cancel.");
        }
        bot.releaseMovement();
        if (!bot.mc.player.abilities.flying) {
            bot.mc.player.abilities.flying=true;
            bot.mc.player.sendAbilitiesUpdate();
        }
        if (snapshot!=null && snapshot.isDone()) {
            try { pieces=snapshot.get(); }
            catch (Exception e) { throw new TaskFailure("Stronghold scan failed: "+e.getMessage()); }
            snapshot=null; pieceIndex=blockIndex=0; nextSnapshot=bot.ticks+40;
        }
        // Copy only existing generated structure records on the owning server thread.
        if (snapshot==null && pieceIndex>=pieces.size() && bot.ticks>=nextSnapshot) {
            final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
            snapshot=server.method_10815((Callable<List<int[]>>) () -> {
                List<int[]> result=new ArrayList<>();
                FeatureState state=(FeatureState)server.getWorld(0).getOrCreateState(FeatureState.class,"Stronghold");
                if (state==null) return result;
                NbtCompound features=state.getFeatures();
                for (Object key:features.getKeys()) {
                    NbtList children=features.getCompound((String)key).getList("Children",10);
                    for (int i=0;i<children.size();i++) {
                        int[] box=children.getCompound(i).getIntArray("BB");
                        if (box.length==6) result.add(box.clone());
                    }
                }
                return result;
            });
        }
        // A bounding box alone is not proof: confirm actual blocks received by the client.
        for (int budget=2048; budget>0 && pieceIndex<pieces.size();budget--) {
            int[] b=pieces.get(pieceIndex);
            int dx=b[3]-b[0]+1, dy=b[4]-b[1]+1, dz=b[5]-b[2]+1;
            if (dx<=0 || dy<=0 || dz<=0 || (long)dx*dy*dz>1000000L || blockIndex>=dx*dy*dz) {
                pieceIndex++;blockIndex=0;continue;
            }
            int index=blockIndex++;
            BlockPos pos=new BlockPos(b[0]+index%dx,b[1]+index/dx%dy,b[2]+index/(dx*dy));
            int cx=pos.getX()>>4, cz=pos.getZ()>>4;
            if (Math.abs(cx-((int)Math.floor(bot.mc.player.x)>>4))>32
                    || Math.abs(cz-((int)Math.floor(bot.mc.player.z)>>4))>32
                    || !bot.mc.world.isLoaded(pos,false)) continue;
            Block block=bot.mc.world.getBlockState(pos).getBlock();
            if (block==Block.get("stonebrick") || block==Block.get("end_portal_frame")) {
                found=pos;
                bot.say("Stronghold detected in loaded chunks at "+pos.getX()+" "+pos.getY()+" "+pos.getZ()
                        +"; flight distance "+Math.round(traveled(bot))+" blocks.");
                return true;
            }
        }
        bot.mc.player.yaw=-135;
        bot.mc.player.pitch=0;
        if (bot.mc.player.y<160) bot.key(bot.mc.options.jumpKey,true);
        else if (bot.mc.world.isLoaded(new BlockPos(bot.mc.player.x+32,80,bot.mc.player.z-32),false))
            bot.key(bot.mc.options.forwardKey,true);
        return false;
    }
    public void stop(LegacyBot bot) {
        bot.releaseMovement();
        // Leave the requested render distance and Creative flight enabled for inspection.
    }
}
