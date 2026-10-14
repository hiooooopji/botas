package adris.altoclef.legacy;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.gen.feature.FeatureState;

/** Creative practice: vanilla seed locator, actual flight, then a mined access shaft. */
final class LegacyStrongholdTravelTask implements LegacyTask {
    private Future<BlockPos> location;
    private Future<int[]> roomBounds;
    private BlockPos target, entrance;
    private int[] bounds;
    private int nextRoomCheck, stable, ticks;
    private int stage;
    private boolean arrived;
    private String status="locate nearest stronghold from seed";
    BlockPos target() {return target;}
    BlockPos entrance() {return entrance;}
    boolean arrived() {return arrived;}
    public String describe() {return "stronghold -> "+status;}
    public boolean tick(LegacyBot bot) {
        if(bot.mc.getServer()==null || bot.mc.player.dimension!=0 || !bot.mc.player.abilities.creativeMode)
            throw new TaskFailure("Seed-based stronghold travel currently requires a Creative single-player Overworld.");
        if(++ticks>24000) throw new TaskFailure("Stronghold travel timed out.");
        bot.releaseMovement();
        if(location==null) {
            final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
            final BlockPos from=new BlockPos(bot.mc.player);
            location=server.method_10815((Callable<BlockPos>) () -> server.getWorld(0).getChunkProvider()
                    .getNearestStructurePos(server.getWorld(0),"Stronghold",from));
            return false;
        }
        if(target==null) {
            if(!location.isDone()) return false;
            try {target=location.get();} catch(Exception e) {throw new TaskFailure("Stronghold location failed: "+e.getMessage());}
            if(target==null) throw new TaskFailure("This world has no stronghold location.");
            bot.say("Nearest seed-based stronghold: "+target.getX()+" "+target.getY()+" "+target.getZ()+". Flying there, then mining an entrance.");
        }
        if(!bot.mc.player.abilities.flying) {bot.mc.player.abilities.flying=true;bot.mc.player.sendAbilitiesUpdate();}
        if(stage==0) {
            status="fly to "+target.getX()+" "+target.getZ();
            if(bot.mc.player.y<160) {
                BlockPos head=new BlockPos(bot.mc.player.x,bot.mc.player.y+2,bot.mc.player.z);
                if(!bot.mc.world.isAir(head)) clear(bot,head);
                else bot.key(bot.mc.options.jumpKey,true);
                return false;
            }
            if(!align(bot,target.getX()+.5,target.getZ()+.5)) return false;
            stage=1;
        }
        if(stage==1) {
            status="confirm loaded stronghold entrance";
            if(roomBounds==null && bot.ticks>=nextRoomCheck) {
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
                final BlockPos near=target;
                roomBounds=server.method_10815((Callable<int[]>) () -> {
                    FeatureState state=(FeatureState)server.getWorld(0).getOrCreateState(FeatureState.class,"Stronghold");
                    if(state==null) return null;
                    int[] best=null;double distance=Double.MAX_VALUE;
                    NbtCompound features=state.getFeatures();
                    for(Object key:features.getKeys()) {
                        NbtList children=features.getCompound((String)key).getList("Children",10);
                        for(int i=0;i<children.size();i++) {
                            NbtCompound piece=children.getCompound(i);
                            if(!piece.getString("id").equals("SHStart")) continue;
                            int[] b=piece.getIntArray("BB");if(b.length!=6) continue;
                            double dx=(b[0]+b[3])*.5-near.getX(),dz=(b[2]+b[5])*.5-near.getZ();
                            double d=dx*dx+dz*dz;
                            if(d<distance && d<32*32) {distance=d;best=b.clone();}
                        }
                    }
                    return best;
                });
            }
            if(roomBounds!=null && roomBounds.isDone()) {
                try {bounds=roomBounds.get();} catch(Exception e) {throw new TaskFailure("Stronghold entrance check failed: "+e.getMessage());}
                roomBounds=null;nextRoomCheck=bot.ticks+20;
            }
            if(bounds==null) return false;
            double best=Double.MAX_VALUE;
            for(int y=bounds[1]+1;y<bounds[4]-1;y++) for(int x=bounds[0]+1;x<bounds[3];x++) for(int z=bounds[2]+1;z<bounds[5];z++) {
                BlockPos p=new BlockPos(x,y,z);
                if(!bot.mc.world.isLoaded(p,false) || !bot.mc.world.isAir(p) || !bot.mc.world.isAir(p.up())) continue;
                if(!bot.mc.world.getBlockState(p.down()).getBlock().isFullCube()) continue;
                double d=Math.abs(x-(bounds[0]+bounds[3])*.5)+Math.abs(z-(bounds[2]+bounds[5])*.5)+(bounds[4]-y)*.1;
                if(d<best){best=d;entrance=p;}
            }
            if(entrance==null) return false;
            bot.say("Confirmed generated stronghold entrance at "+entrance.getX()+" "+entrance.getY()+" "+entrance.getZ());
            stage=2;
        }
        status="enter stronghold at "+entrance.getX()+" "+entrance.getY()+" "+entrance.getZ();
        if(!align(bot,entrance.getX()+.5,entrance.getZ()+.5)) return false;
        // Only clear the vertical access column; leave the room's floor intact.
        for(int y=(int)Math.floor(bot.mc.player.y)+1;y>=Math.max(entrance.getY(),(int)Math.floor(bot.mc.player.y)-2);y--) {
            BlockPos p=new BlockPos(entrance.getX(),y,entrance.getZ());
            if(!bot.mc.world.isAir(p) && !bot.mc.world.getBlockState(p).getBlock().getMaterial().isFluid()) {clear(bot,p);return false;}
        }
        if(bot.mc.player.y>entrance.getY()+.25) {
            bot.mc.player.pitch=80;bot.key(bot.mc.options.sneakKey,true);return false;
        }
        if(bot.mc.player.abilities.flying) {bot.mc.player.abilities.flying=false;bot.mc.player.sendAbilitiesUpdate();}
        if(bot.mc.player.onGround && Math.abs(bot.mc.player.y-entrance.getY())<.25) {
            arrived=true;status="arrived inside stronghold";return true;
        }
        return false;
    }
    private boolean align(LegacyBot bot,double x,double z) {
        double dx=x-bot.mc.player.x,dz=z-bot.mc.player.z,d=Math.hypot(dx,dz);
        double speed=Math.hypot(bot.mc.player.velocityX,bot.mc.player.velocityZ);
        if(d<.23 && speed<.035) {stable++;return stable>=3;}
        stable=0;
        bot.mc.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));bot.mc.player.pitch=0;
        if(d>Math.max(.15,speed*5)) {bot.key(bot.mc.options.forwardKey,true);bot.sprint(d>12);}
        return false;
    }
    private void clear(LegacyBot bot,BlockPos p) {
        if(bot.mc.world.getBlockState(p).getBlock()==Block.get("bedrock")) throw new TaskFailure("Bedrock blocks stronghold access.");
        BlockHitResult hit=bot.hit(p);if(hit==null) return;
        bot.lookExact(hit.pos.x,hit.pos.y,hit.pos.z);
        bot.mc.interactionManager.attackBlock(p,hit.direction);bot.mc.player.swingHand();
    }
    public void stop(LegacyBot bot) {bot.releaseMovement();bot.cancelMining();}
}
