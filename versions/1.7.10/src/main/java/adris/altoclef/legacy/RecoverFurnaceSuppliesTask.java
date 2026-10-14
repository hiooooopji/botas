package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/** Recover kit supplies through normal furnace interactions after a restart. */
final class RecoverFurnaceSuppliesTask implements LegacyTask {
    private List<BlockPos> candidates;
    private int next, deadline, waitUntil;
    private WorkstationTasks.Open open;
    private BlockPos current;

    public String describe() { return "recover furnace supplies" + (current == null ? "" : " at " + current)
            + (open == null ? "" : " -> " + open.describe()); }
    public void stop(LegacyBot bot) { bot.closeOwnedScreen(); bot.releaseMovement(); bot.cancelMining(); }

    public boolean tick(LegacyBot bot) {
        if (candidates == null) {
            candidates = new ArrayList<>();
            for (Object entry : bot.mc.world.blockEntities) if (entry instanceof FurnaceBlockEntity) {
                FurnaceBlockEntity furnace = (FurnaceBlockEntity)entry;
                BlockPos pos = new BlockPos(furnace.x, furnace.y, furnace.z);
                if (LegacyPos.squaredDistanceToCenter(bot.mc.field_3805, pos) <= 64 * 64) candidates.add(pos);
            }
            candidates.sort(Comparator.comparingDouble(p -> LegacyPos.squaredDistanceToCenter(bot.mc.field_3805, p)));
        }
        if (bot.ticks < waitUntil) return false;
        if (open == null) {
            if (next >= candidates.size()) return true;
            current = candidates.get(next++);
            open = new WorkstationTasks.Open("furnace", FurnaceScreenHandler.class, null);
            open.pos = current;
            open.fixedPosition = true;
            deadline = bot.ticks + 400;
        }
        try {
            if (bot.ticks > deadline) throw new TaskFailure("Could not reach saved furnace");
            if (!(bot.mc.field_3805.openScreenHandler instanceof FurnaceScreenHandler)) {
                open.tick(bot);
                waitUntil = bot.ticks + 3;
                return false;
            }
            ScreenHandler handler = bot.mc.field_3805.openScreenHandler;
            ItemStack result = handler.getSlot(2).getStack();
            if (result != null && (LegacyItems.readyFood(result) || LegacyItems.id(result.getItem()).equals("iron_ingot"))) {
                recover(bot, result, 2);
            }
            ItemStack input = handler.getSlot(0).getStack();
            if (input != null && (LegacyItems.meat(false).matches(input) || LegacyItems.id(input.getItem()).equals("iron_ore")))
                recover(bot, input, 0);
        } catch (TaskFailure inaccessible) {
            bot.say("Skipping saved furnace at " + current + ": " + inaccessible.getMessage());
        }
        bot.closeOwnedScreen(); bot.releaseMovement(); bot.cancelMining(); bot.navigation.reset();
        open = null;
        return false;
    }

    private void recover(LegacyBot bot, ItemStack stack, int slot) {
        LegacyItems.Target target = LegacyItems.of(stack);
        int before = bot.count(target);
        bot.click(slot, 0, 1);
        int recovered = bot.count(target) - before;
        if (recovered > 0) bot.say("Recovered " + recovered + " " + target + " from furnace at " + current + ".");
    }
}
