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

/** Explicit map construction only; all extension and bridge cells start empty. */
final class ParkourMapBuilder {
    static final String[] MATERIALS={"stone","obsidian","emerald_block","gold_block","diamond_block","lapis_block"};
    static Future<BlockPos> prepare(LegacyBot bot,BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
        final String name=bot.mc.player.getGameProfile().getName();final int dimension=bot.mc.player.dimension;
        final BlockPos origin=new BlockPos(bot.mc.player).add(4,12,0);
        return server.method_10815(()-> {
            LegacyPlayerState.requirePrivate(server);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(cancelled.getAsBoolean() || player==null || player.dimension!=dimension || !player.isAlive())throw new TaskFailure("Map setup cancelled or player changed worlds.");
            if(player.vehicle!=null || player.openScreenHandler!=player.playerScreenHandler || player.inventory.getCursorStack()!=null)throw new TaskFailure("Leave your mount and close the container first.");
            if(origin.getY()<5 || origin.getY()>247)throw new TaskFailure("Move away from the height limit for this map.");
            ServerWorld world=server.getWorld(dimension);
            for(int x=-3;x<=128;x++)for(int z=-4;z<=50;z++)for(int y=-4;y<=6;y++) {
                BlockPos p=origin.add(x,y,z);
                if(!world.isLoaded(p,false) || !world.isAir(p))throw new TaskFailure("Map needs a clear, loaded 132 by 55 area east and south of you.");
            }
            int blocks=0;for(ItemStack stack:player.inventory.main)if(LegacyItems.parse("cobblestone").matches(stack))blocks+=stack.count;
            if(blocks<64 && player.inventory.getEmptySlot()<0)throw new TaskFailure("Free one inventory slot for map blocks.");
            if(cancelled.getAsBoolean())throw new TaskFailure("Map setup cancelled.");
            for(int x=-3;x<=128;x++)for(int z=-4;z<=50;z++)world.setBlockState(origin.add(x,-4,z),Block.get("stone").getDefaultState(),3);
            for(int[] b:ParkourMarathonLayout.boxes())for(int x=b[0];x<=b[3];x++)for(int y=b[1];y<=b[4];y++)for(int z=b[2];z<=b[5];z++)
                world.setBlockState(origin.add(x,y,z),Block.get(MATERIALS[b[6]]).getDefaultState(),3);
            sign(world,origin.add(0,0,0),"START","3 triple neos","then extensions","@parkour runmap");
            sign(world,origin.add(65,0,0),"7-BLOCK GAP","Place 3 blocks","from far landing","Gold markers");
            sign(world,origin.add(81,0,0),"ANDROMEDA","16 blocks long","Floor + ceiling","32 placements");
            sign(world,origin.add(103,0,0),"7-BLOCK GAP","Place 3 blocks","then up-one neo","Keep moving");
            sign(world,origin.add(119,1,0),"UP-ONE NEO","Land 1 block up","Turn south","5 normal jumps");
            sign(world,origin.add(121,1,39),"FINAL JUMP","4-block gap","Finish on emerald","Well done!");
            if(blocks<64)player.inventory.insertStack(new ItemStack(LegacyItems.parse("cobblestone").item,64-blocks));
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(origin.getX()+.5,origin.getY()+1,origin.getZ()+1.5,-90,0);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));player.refreshScreenHandler(player.playerScreenHandler);
            return origin;
        });
    }
    static Future<BlockPos> prepareSkills(LegacyBot bot,ParkourSkills.Skill selected,BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
        final String name=bot.mc.player.getGameProfile().getName();final int dimension=bot.mc.player.dimension;
        final BlockPos arena=new BlockPos(bot.mc.player).add(4,12,0);
        return server.method_10815(()-> {
            LegacyPlayerState.requirePrivate(server);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(cancelled.getAsBoolean() || player==null || player.dimension!=dimension || !player.isAlive())throw new TaskFailure("Skills setup cancelled.");
            if(player.vehicle!=null || player.openScreenHandler!=player.playerScreenHandler)throw new TaskFailure("Leave your mount and close the container first.");
            if(arena.getY()<5 || arena.getY()>247)throw new TaskFailure("Move away from the height limit.");
            ServerWorld world=server.getWorld(dimension);
            for(int x=-3;x<=119;x++)for(int z=-3;z<=48;z++)for(int y=-4;y<=6;y++) {
                BlockPos p=arena.add(x,y,z);
                if(!world.isLoaded(p,false) || !world.isAir(p))throw new TaskFailure("Skills arena needs a clear loaded 123 by 52 area east and south of you.");
            }
            if(cancelled.getAsBoolean())throw new TaskFailure("Skills setup cancelled.");
            for(int x=-3;x<=119;x++)for(int z=-3;z<=48;z++)world.setBlockState(arena.add(x,-4,z),Block.get("stone").getDefaultState(),3);
            BlockPos selectedOrigin=null;
            for(int i=0;i<ParkourSkills.ALL.size();i++) {
                ParkourSkills.Skill skill=ParkourSkills.ALL.get(i);BlockPos origin=arena.add((i%4)*30,0,(i/4)*12);
                for(int[] b:skill.boxes)for(int x=b[0];x<=b[3];x++)for(int y=b[1];y<=b[4];y++)for(int z=b[2];z<=b[5];z++)
                    world.setBlockState(origin.add(x,y,z),Block.get(y>0?"obsidian":"stone").getDefaultState(),3);
                world.setBlockState(origin.add((int)skill.startX,0,4),Block.get("emerald_block").getDefaultState(),3);
                PathSearch.Pos end=skill.targets.get(skill.targets.size()-1);
                world.setBlockState(origin.add(end.x,end.y-1,end.z),Block.get("gold_block").getDefaultState(),3);
                sign(world,origin.add(0,0,3),"SKILL "+(i+1),skill.id,skill.label,"Gold = finish");
                if(skill==selected)selectedOrigin=origin;
            }
            if(selectedOrigin==null)throw new TaskFailure("Unknown selected skill.");
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(selectedOrigin.getX()+selected.startX,selectedOrigin.getY()+1,selectedOrigin.getZ()+4.5,-90,0);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
            return selectedOrigin;
        });
    }
    static Future<BlockPos> prepareHeadNeo(LegacyBot bot,BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
        final String name=bot.mc.player.getGameProfile().getName();final int dimension=bot.mc.player.dimension;
        final BlockPos origin=new BlockPos(bot.mc.player).add(18,12,0);
        return server.method_10815(()-> {
            LegacyPlayerState.requirePrivate(server);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(cancelled.getAsBoolean() || player==null || player.dimension!=dimension || !player.isAlive())throw new TaskFailure("Head neo setup cancelled.");
            if(player.vehicle!=null || player.openScreenHandler!=player.playerScreenHandler)throw new TaskFailure("Leave your mount and close the container first.");
            if(origin.getY()<5 || origin.getY()>247)throw new TaskFailure("Move away from the height limit.");
            ServerWorld world=server.getWorld(dimension);
            for(int x=-3;x<=10;x++)for(int z=-10;z<=5;z++)for(int y=-4;y<=5;y++) {
                BlockPos p=origin.add(x,y,z);
                if(!world.isLoaded(p,false) || !world.isAir(p))throw new TaskFailure("Head neo needs a clear loaded 14 by 16 area east of you.");
            }
            if(cancelled.getAsBoolean())throw new TaskFailure("Head neo setup cancelled.");
            for(int x=-3;x<=10;x++)for(int z=-10;z<=5;z++)world.setBlockState(origin.add(x,-4,z),Block.get("stone").getDefaultState(),3);
            for(int[] table:ParkourHeadNeo.TABLES)world.setBlockState(origin.add(table[0],0,table[1]),Block.get("enchanting_table").getDefaultState(),3);
            for(int y=-1;y<=3;y++) {
                world.setBlockState(origin.add(0,y,0),Block.get("obsidian").getDefaultState(),3);
                for(int z=1;z<=3;z++)world.setBlockState(origin.add(1,y,z),Block.get("obsidian").getDefaultState(),3);
            }
            for(int side:new int[]{-1,1}) {
                BlockPos p=origin.add(0,0,side);
                world.setBlockState(p,Block.get("skull").getDefaultState().with(net.minecraft.block.SkullBlock.FACING,
                        side<0?net.minecraft.util.math.Direction.NORTH:net.minecraft.util.math.Direction.WEST),3);
                net.minecraft.block.entity.SkullBlockEntity head=(net.minecraft.block.entity.SkullBlockEntity)world.getBlockEntity(p);
                head.setSkullType(4);head.markDirty();
            }
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(origin.getX()+ParkourHeadNeo.START_X,origin.getY()+ParkourHeadNeo.FEET_Y,
                    origin.getZ()+ParkourHeadNeo.START_Z,42.191994f,12);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));return origin;
        });
    }
    static Future<BlockPos> prepareWallWingNeo(LegacyBot bot,BooleanSupplier cancelled) {
        final IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
        final String name=bot.mc.player.getGameProfile().getName();final int dimension=bot.mc.player.dimension;
        final BlockPos origin=new BlockPos(bot.mc.player).add(18,12,0);
        return server.method_10815(()-> {
            LegacyPlayerState.requirePrivate(server);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
            if(cancelled.getAsBoolean() || player==null || player.dimension!=dimension || !player.isAlive())throw new TaskFailure("Winged neo setup cancelled.");
            if(player.vehicle!=null || player.openScreenHandler!=player.playerScreenHandler)throw new TaskFailure("Leave your mount and close the container first.");
            if(origin.getY()<5 || origin.getY()>247)throw new TaskFailure("Move away from the height limit.");
            ServerWorld world=server.getWorld(dimension);
            for(int x=-3;x<=17;x++)for(int z=-6;z<=4;z++)for(int y=-4;y<=6;y++) {
                BlockPos p=origin.add(x,y,z);
                if(!world.isLoaded(p,false) || !world.isAir(p))throw new TaskFailure("Winged neo needs a clear loaded 21 by 11 area east of you.");
            }
            if(cancelled.getAsBoolean())throw new TaskFailure("Winged neo setup cancelled.");
            for(int x=-3;x<=17;x++)for(int z=-6;z<=4;z++)world.setBlockState(origin.add(x,-4,z),Block.get("stone").getDefaultState(),3);
            for(int[] pad:ParkourWallWingNeo.TABLES)world.setBlockState(origin.add(pad[0],1,pad[1]),Block.get("stone_slab").stateFromData(0),3);
            for(int y=0;y<=4;y++)world.setBlockState(origin.add(0,y,0),Block.get("obsidian").getDefaultState(),3);
            for(int side:new int[]{-1,1}) {
                world.setBlockState(origin.add(0,0,side),Block.get("cobblestone_wall").getDefaultState(),3);
                for(int level=1;level<=3;level++)world.setBlockState(origin.add(side,level,0),Block.get("trapdoor").stateFromData(0)
                        .with(net.minecraft.block.TrapdoorBlock.FACING,side<0?net.minecraft.util.math.Direction.WEST:net.minecraft.util.math.Direction.EAST)
                        .with(net.minecraft.block.TrapdoorBlock.OPEN,true),3);
            }
            player.method_3170(GameMode.SURVIVAL);player.setHealth(20);player.getHungerManager().setFoodLevel(20);
            player.velocityX=player.velocityY=player.velocityZ=0;player.fallDistance=0;
            player.networkHandler.requestTeleport(origin.getX()+ParkourWallWingNeo.START_X,origin.getY()+ParkourWallWingNeo.FEET_Y,
                    origin.getZ()+ParkourWallWingNeo.START_Z,81.88381f,12);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));return origin;
        });
    }
    private static void sign(ServerWorld world,BlockPos pos,String... lines) {
        world.setBlockState(pos,Block.get("wall_sign").stateFromData(2),3);
        net.minecraft.block.entity.SignBlockEntity sign=(net.minecraft.block.entity.SignBlockEntity)world.getBlockEntity(pos);
        if(sign!=null) {for(int i=0;i<4;i++)sign.text[i]=new net.minecraft.text.LiteralText(lines[i]);sign.markDirty();}
    }
}
