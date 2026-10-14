package adris.altoclef.legacy;

import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import net.minecraft.block.Block;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

/** Three three-block-thick neos, four empty blocks, then a sixteen-block Andromeda lane. */
final class ParkourChallenge {
    static final int[] WALLS={12,32,52};
    static final int BRIDGE_START=79, BRIDGE_END=95;
    static Future<BlockPos> prepare(LegacyBot bot,BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
        final String name=bot.mc.player.getGameProfile().getName();final int dimension=bot.mc.player.dimension;
        final BlockPos origin=new BlockPos(bot.mc.player).add(4,12,0);
        return server.method_10815(()-> {
            LegacyPlayerState.requirePrivate(server);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(cancelled.getAsBoolean() || player==null || player.dimension!=dimension || !player.isAlive())
                throw new TaskFailure("Challenge setup cancelled or player changed worlds.");
            if(player.vehicle!=null || player.openScreenHandler!=player.playerScreenHandler || player.inventory.getCursorStack()!=null)
                throw new TaskFailure("Leave your mount and close the container first.");
            if(origin.getY()<5 || origin.getY()>248)throw new TaskFailure("Move away from the height limit for the challenge.");
            ServerWorld world=server.getWorld(dimension);
            for(int x=-3;x<=103;x++)for(int z=-4;z<=6;z++)for(int y=-4;y<=5;y++) {
                BlockPos pos=origin.add(x,y,z);
                if(!world.isLoaded(pos,false) || !world.isAir(pos))throw new TaskFailure("Challenge needs a clear, loaded 107-block area east of you.");
            }
            int blocks=0;for(ItemStack stack:player.inventory.main)if(LegacyItems.parse("cobblestone").matches(stack))blocks+=stack.count;
            if(blocks<64 && player.inventory.getEmptySlot()<0)throw new TaskFailure("Free an inventory slot for challenge blocks.");
            if(cancelled.getAsBoolean())throw new TaskFailure("Challenge cancelled.");
            for(int x=-3;x<=103;x++)for(int z=-4;z<=6;z++)put(world,origin.add(x,-4,z),"stone");
            for(int x=0;x<=100;x++) {
                if(x<=65 || x>=70 && x<BRIDGE_START || x>=BRIDGE_END)put(world,origin.add(x,0,1),"stone");
                if(x>=75 && x<BRIDGE_START)put(world,origin.add(x,3,1),"stone");
            }
            for(int start:WALLS)for(int x=start;x<start+3;x++)for(int y=1;y<=3;y++)put(world,origin.add(x,y,1),"obsidian");
            if(blocks<64)player.inventory.insertStack(new ItemStack(LegacyItems.parse("cobblestone").item,64-blocks));
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(origin.getX()+.5,origin.getY()+1,origin.getZ()+1.5,-90,0);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));player.refreshScreenHandler(player.playerScreenHandler);
            return origin;
        });
    }
    private static void put(ServerWorld world,BlockPos pos,String id) {world.setBlockState(pos,Block.get(id).getDefaultState(),3);}
}
