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
        BlockPos feet=LegacyPos.of(bot.mc.field_3805);
        for (Direction direction : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}) {
            BlockPos base=LegacyPos.offset(feet,direction);
            if (attempted.contains(base) || !safe(bot,base)) continue;
            // Already empty spaces cannot be improved by digging.
            if (LegacyWorld.isAir(bot.mc.world,base) && LegacyWorld.isAir(bot.mc.world,LegacyPos.up(base))) continue;
            BlockPos first=LegacyWorld.isAir(bot.mc.world,LegacyPos.up(base)) ? base : LegacyPos.up(base);
            if (bot.hit(first)==null) continue;
            attempted.add(base);
            return new WorkstationSpaceTask(base);
        }
        return null;
    }
    private static boolean safe(LegacyBot bot, BlockPos base) {
        if (!bot.navigation.floor(base.x,base.y-1,base.z)) return false;
        BlockPos above=LegacyPos.up(LegacyPos.up(base));
        if (!LegacyWorld.isLoaded(bot.mc.world,above,false)) return false;
        Block ceiling=LegacyWorld.state(bot.mc.world,above).getBlock();
        if (ceiling instanceof FallingBlock || ceiling.getMaterial().isFluid()) return false;
        for (BlockPos p : new BlockPos[]{base,LegacyPos.up(base)}) {
            if (!LegacyWorld.isLoaded(bot.mc.world,p,false)) return false;
            Block b=LegacyWorld.state(bot.mc.world,p).getBlock();
            String id=String.valueOf(Block.REGISTRY.getId(b));
            if (!LegacyWorld.isAir(bot.mc.world,p) && !id.equals("minecraft:dirt") && !id.equals("minecraft:grass")
                    && !id.equals("minecraft:stone") && !id.equals("minecraft:cobblestone")
                    && !id.equals("minecraft:sandstone") && !id.equals("minecraft:red_sandstone")
                    && !id.equals("minecraft:hardened_clay") && !id.equals("minecraft:stained_hardened_clay")) return false;
            // Require a harvest-capable pickaxe before breaking rock.
            if (b.getMaterial()==net.minecraft.block.material.Material.STONE) {
                boolean tool=false;
                for (net.minecraft.item.ItemStack s : bot.mc.field_3805.inventory.main)
                    if (s!=null && s.isEffectiveOn(b) && (!s.isDamageable() || s.getDamage()<s.getMaxDamage()-1)) tool=true;
                if (!tool) return false;
            }
            for (Direction d : Direction.values()) {
                BlockPos adjacent=LegacyPos.offset(p,d);
                if (!LegacyWorld.isLoaded(bot.mc.world,adjacent,false) || LegacyWorld.state(bot.mc.world,adjacent).getBlock().getMaterial().isFluid()) return false;
            }
        }
        return true;
    }
    public boolean tick(LegacyBot bot) {
        if (++ticks>400 || !safe(bot,base)) throw new TaskFailure("Could not safely clear workstation space.");
        for (BlockPos p : new BlockPos[]{LegacyPos.up(base),base}) {
            if (LegacyWorld.isAir(bot.mc.world,p)) continue;
            if (p.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805))) || bot.hit(p)==null)
                throw new TaskFailure("Workstation recess is no longer within reach.");
            bot.mine(p); return false;
        }
        bot.cancelMining(); bot.releaseMovement(); return true;
    }
}
