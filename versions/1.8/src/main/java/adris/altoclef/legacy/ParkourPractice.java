package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import java.util.concurrent.Future;

/** Explicit practice setup, separate from the controller that performs the actual jumps. */
final class ParkourPractice {
    static Future<BlockPos> prepareTriple(LegacyBot bot,java.util.function.BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
        final String name=bot.mc.player.getGameProfile().getName();final int dimension=bot.mc.player.dimension;
        final BlockPos origin=new BlockPos(bot.mc.player).add(4,12,0);
        return server.method_10815(()-> {
            if(cancelled.getAsBoolean())throw new TaskFailure("Practice cancelled.");
            ServerWorld world=server.getWorld(dimension);ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(origin.getY()>238)throw new TaskFailure("Not enough room below the height limit.");
            for(int x=-3;x<=25;x++)for(int z=-4;z<=6;z++)for(int y=-4;y<=4;y++)
                if(!world.isLoaded(origin.add(x,y,z),false) || !world.isAir(origin.add(x,y,z)))throw new TaskFailure("Triple neo practice area is obstructed.");
            for(int x=-3;x<=25;x++)for(int z=-4;z<=6;z++)world.setBlockState(origin.add(x,-4,z),Block.get("stone").getDefaultState(),3);
            for(int x=0;x<=20;x++) {
                world.setBlockState(origin.add(x,0,1),Block.get("stone").getDefaultState(),3);
                if(x>=12 && x<=14)for(int y=1;y<=3;y++)world.setBlockState(origin.add(x,y,1),Block.get("obsidian").getDefaultState(),3);
            }
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(origin.getX()+.5,origin.getY()+1,origin.getZ()+1.5,-90,0);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
            return origin.add(17,1,1);
        });
    }
    static Future<BlockPos> prepare(LegacyBot bot,java.util.function.BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();
        final String name=bot.mc.player.getGameProfile().getName();
        final int dimension=bot.mc.player.dimension;
        final BlockPos existing=bot.practiceWorld==bot.mc.world ? bot.practiceOrigin:null;
        final BlockPos candidate=new BlockPos(bot.mc.player).add(4,12,0);
        return server.method_10815(()-> {
            if(cancelled.getAsBoolean()) throw new TaskFailure("Practice cancelled.");
            LegacyPlayerState.requirePrivate(server);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(player==null || player.dimension!=dimension || !player.isAlive()) throw new TaskFailure("Player changed worlds before practice.");
            if(player.vehicle!=null || player.openScreenHandler!=player.playerScreenHandler || player.inventory.getCursorStack()!=null)
                throw new TaskFailure("Leave your mount and close the container first.");
            ServerWorld world=server.getWorld(dimension);
            BlockPos origin=existing==null ? candidate:existing;
            if(origin.getY()<5 || origin.getY()>240) throw new TaskFailure("Move away from the world height limit for practice.");
            if(existing==null) {
                // Refuse to replace existing terrain/builds; this only creates a course in clear air.
                for(int x=-3;x<=26;x++) for(int z=-4;z<=6;z++) for(int y=-4;y<=4;y++) {
                    BlockPos pos=origin.add(x,y,z);
                    if(!world.isLoaded(pos,false) || !world.isAir(pos)) throw new TaskFailure("Practice area is obstructed; use an open area.");
                }
            } else {
                for(int[] box:blueprint()) for(int x=box[0];x<=box[3];x++) for(int y=box[1];y<=box[4];y++) for(int z=box[2];z<=box[5];z++) {
                    Block block=world.getBlockState(origin.add(x,y,z)).getBlock();
                    if(block!=Block.get(box[6]==1?"obsidian":"stone")) throw new TaskFailure("The recorded practice course was edited; restore it before replaying.");
                }
                Block block=world.getBlockState(origin.add(19,0,1)).getBlock();
                if(block!=Block.get("air") && block!=Block.get("cobblestone")) throw new TaskFailure("The extension spot contains another block.");
            }
            int blocks=0;for(ItemStack stack:player.inventory.main) if(LegacyItems.parse("cobblestone").matches(stack)) blocks+=stack.count;
            if(blocks==0 && player.inventory.getEmptySlot()<0) throw new TaskFailure("Make one inventory slot free for practice blocks.");
            if(cancelled.getAsBoolean()) throw new TaskFailure("Practice cancelled.");
            if(existing==null) {
                for(int x=-3;x<=26;x++) for(int z=-4;z<=6;z++) world.setBlockState(origin.add(x,-4,z),Block.get("stone").getDefaultState(),3);
                for(int[] box:blueprint()) for(int x=box[0];x<=box[3];x++) for(int y=box[1];y<=box[4];y++) for(int z=box[2];z<=box[5];z++)
                    world.setBlockState(origin.add(x,y,z),Block.get(box[6]==1?"obsidian":"stone").getDefaultState(),3);
            }
            world.setBlockState(origin.add(19,0,1),Block.get("air").getDefaultState(),3);
            if(blocks==0) player.inventory.insertStack(new ItemStack(LegacyItems.parse("cobblestone").item,8));
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(origin.getX()+.5,origin.getY()+1,origin.getZ()+1.5,-90,0);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
            player.refreshScreenHandler(player.playerScreenHandler);
            return origin;
        });
    }
    private static int[][] blueprint() {
        return new int[][]{{0,0,0,3,0,2,0},{7,0,1,9,0,1,0},{10,0,1,10,3,1,1},{11,0,1,14,0,1,0},{20,0,0,22,0,2,0}};
    }
}
