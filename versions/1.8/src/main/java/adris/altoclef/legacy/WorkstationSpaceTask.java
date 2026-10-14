package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.Set;

/** Clears a two-block-high side recess without removing the player's floor or tunnel steps. */
final class WorkstationSpaceTask implements LegacyTask {
    final BlockPos base;
    private int ticks;
    private WorkstationSpaceTask(BlockPos base) { this.base=base; }
    public String describe() { return "clear space for workstation"; }
    public void stop(LegacyBot bot) { bot.cancelMining(); bot.releaseMovement(); }
    static WorkstationSpaceTask plan(LegacyBot bot, Set<BlockPos> attempted) {
        BlockPos feet=new BlockPos(bot.mc.player);
        for (Direction direction : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}) {
            BlockPos base=feet.offset(direction);
            if (attempted.contains(base) || !safe(bot,base)) continue;
            // Already empty spaces cannot be improved by digging.
            if (bot.mc.world.isAir(base) && bot.mc.world.isAir(base.up())) continue;
            BlockPos first=bot.mc.world.isAir(base.up()) ? base : base.up();
            if (bot.hit(first)==null) continue;
            attempted.add(base);
            return new WorkstationSpaceTask(base);
        }
        return null;
    }
    private static boolean safe(LegacyBot bot, BlockPos base) {
        if (!bot.navigation.floor(base.getX(),base.getY()-1,base.getZ())) return false;
        BlockPos above=base.up().up();
        if (!bot.mc.world.isLoaded(above,false)) return false;
        Block ceiling=bot.mc.world.getBlockState(above).getBlock();
        if (ceiling instanceof FallingBlock || ceiling.getMaterial().isFluid()) return false;
        for (BlockPos p : new BlockPos[]{base,base.up()}) {
            if (!bot.mc.world.isLoaded(p,false)) return false;
            Block b=bot.mc.world.getBlockState(p).getBlock();
            String id=String.valueOf(Block.REGISTRY.getIdentifier(b));
            if (!bot.mc.world.isAir(p) && !id.equals("minecraft:dirt") && !id.equals("minecraft:grass")
                    && !id.equals("minecraft:stone") && !id.equals("minecraft:cobblestone")
                    && !id.equals("minecraft:sandstone") && !id.equals("minecraft:red_sandstone")
                    && !id.equals("minecraft:hardened_clay") && !id.equals("minecraft:stained_hardened_clay")) return false;
            // Require a harvest-capable pickaxe before breaking rock.
            if (b.getMaterial()==net.minecraft.block.material.Material.STONE) {
                boolean tool=false;
                for (net.minecraft.item.ItemStack s : bot.mc.player.inventory.main)
                    if (s!=null && s.isEffectiveOn(b) && (!s.isDamageable() || s.getDamage()<s.getMaxDamage()-1)) tool=true;
                if (!tool) return false;
            }
            for (Direction d : Direction.values()) {
                BlockPos adjacent=p.offset(d);
                if (!bot.mc.world.isLoaded(adjacent,false) || bot.mc.world.getBlockState(adjacent).getBlock().getMaterial().isFluid()) return false;
            }
        }
        return true;
    }
    public boolean tick(LegacyBot bot) {
        if (++ticks>400 || !safe(bot,base)) throw new TaskFailure("Could not safely clear workstation space.");
        for (BlockPos p : new BlockPos[]{base.up(),base}) {
            if (bot.mc.world.isAir(p)) continue;
            if (p.equals(new BlockPos(bot.mc.player).down()) || bot.hit(p)==null)
                throw new TaskFailure("Workstation recess is no longer within reach.");
            bot.mine(p); return false;
        }
        bot.cancelMining(); bot.releaseMovement(); return true;
    }
}
