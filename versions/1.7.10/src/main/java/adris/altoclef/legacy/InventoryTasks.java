package adris.altoclef.legacy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.screen.*;
import net.minecraft.util.math.BlockPos;
import java.util.Objects;

final class InventoryTasks {
    static final class Withdraw implements LegacyTask {
        final LegacyItems.Target target;
        final int total;
        final BlockPos chest;
        WorkstationTasks.Open open;
        int nextClick,moved;
        Withdraw(LegacyItems.Target target,int total,BlockPos chest) {
            this.target=target;this.total=total;this.chest=chest;
        }
        public String describe() {return "take "+target+" from chest at "+chest+" ("+moved+" taken)";}
        public void stop(LegacyBot bot) {if(open!=null) open.stop(bot);bot.closeOwnedScreen();}
        public boolean tick(LegacyBot bot) {
            if(bot.count(target)>=total) {bot.closeOwnedScreen();return true;}
            if(!(bot.mc.field_3805.openScreenHandler instanceof ChestScreenHandler)) {
                if(open==null) {
                    open=new WorkstationTasks.Open("chest",ChestScreenHandler.class,null);
                    open.pos=chest;open.fixedPosition=true;
                }
                open.tick(bot);return false;
            }
            if(bot.ticks<nextClick) return false;
            nextClick=bot.ticks+3;
            ScreenHandler handler=bot.mc.field_3805.openScreenHandler;
            for(int slot=0;slot<handler.slots.size();slot++) {
                if(handler.getSlot(slot).inventory==bot.mc.field_3805.inventory) continue;
                ItemStack stack=handler.getSlot(slot).getStack();
                if(!target.matches(stack)) continue;
                int before=bot.count(target);
                bot.click(slot,0,1);
                int gained=bot.count(target)-before;
                if(gained<=0) throw new TaskFailure("Make inventory space for chest loot.");
                moved+=gained;
                return false;
            }
            bot.closeOwnedScreen();return true;
        }
    }
    static final class Equip implements LegacyTask {
        final LegacyItems.Target target;
        LegacyTask acquire;
        int phase, source, destination, waitUntil;
        Equip(LegacyItems.Target target) { this.target = target; }
        public String describe() { return "equip " + target; }
        public void stop(LegacyBot bot) { if (acquire != null) acquire.stop(bot); }
        public boolean tick(LegacyBot bot) {
            if (acquire != null) { if (acquire.tick(bot)) { acquire.stop(bot); acquire = null; } return false; }
            if (bot.ticks < waitUntil) return false;
            waitUntil = bot.ticks + 3;
            if (phase == 0) {
                for (ItemStack armor : bot.mc.field_3805.inventory.armor) if (target.item instanceof ArmorItem && target.matches(armor)) return true;
                if (bot.count(target) == 0) { acquire = new AcquisitionTask(target, 1, "get"); return false; }
                bot.closeOwnedScreen();
                if (!(bot.mc.field_3805.openScreenHandler instanceof PlayerScreenHandler)) throw new TaskFailure("Close the container before equipping.");
                int index = bot.findInventory(target);
                if (!(target.item instanceof ArmorItem)) { bot.equip(index); return target.matches(bot.mc.field_3805.inventory.getMainHandStack()); }
                source = bot.screenSlot(index); destination = 5 + ((ArmorItem) target.item).slot;
                bot.click(source, 0, 0); phase = 1; return false;
            }
            if (phase == 1) {
                if (!target.matches(bot.mc.field_3805.inventory.getCursorStack())) throw new TaskFailure("Equipment cursor changed unexpectedly.");
                bot.click(destination, 0, 0); phase = 2; return false;
            }
            if (bot.mc.field_3805.inventory.getCursorStack() != null) bot.click(source, 0, 0);
            if (bot.mc.field_3805.inventory.getCursorStack() != null) throw new TaskFailure("Make room for the previous armor item.");
            if (!target.matches(bot.mc.field_3805.openScreenHandler.getSlot(destination).getStack())) throw new TaskFailure("Armor equip was rejected.");
            return true;
        }
    }

    static final class Deposit implements LegacyTask {
        final LegacyItems.Target target;
        final int limit;
        WorkstationTasks.Open open;
        WorkstationTasks.Transfer transfer;
        int deposited, moving;
        Deposit(LegacyItems.Target target, int limit) { this.target = target; this.limit = limit; }
        public String describe() { return target == null ? "stash inventory" : "deposit " + target + " x" + limit; }
        public boolean tick(LegacyBot bot) {
            if (transfer != null) {
                if (transfer.tick(bot)) { transfer = null; deposited += moving; }
                return false;
            }
            if (!(bot.mc.field_3805.openScreenHandler instanceof ChestScreenHandler)) {
                if (open == null) open = new WorkstationTasks.Open("chest", ChestScreenHandler.class, null);
                open.tick(bot); return false;
            }
            int index = -1;
            for (int i = 0; i < 36; i++) if (bot.mc.field_3805.inventory.main[i] != null
                    && (target == null || target.matches(bot.mc.field_3805.inventory.main[i]))) { index = i; break; }
            if (index < 0 || deposited >= limit) {
                bot.closeOwnedScreen(); bot.say("Deposited " + deposited + " items."); return true;
            }
            ItemStack stack = bot.mc.field_3805.inventory.main[index];
            ScreenHandler h = bot.mc.field_3805.openScreenHandler;
            for (int i = 0; i < h.slots.size(); i++) {
                if (h.getSlot(i).inventory == bot.mc.field_3805.inventory) continue;
                ItemStack dest = h.getSlot(i).getStack();
                if (dest != null && (dest.getItem() != stack.getItem() || dest.getData() != stack.getData()
                        || !Objects.equals(dest.getNbt(), stack.getNbt()))) continue;
                int room = Math.min(h.getSlot(i).getMaxStackAmount(), stack.getMaxCount()) - (dest == null ? 0 : dest.count);
                if (room <= 0) continue;
                moving = Math.min(limit - deposited, Math.min(stack.count, room));
                transfer = new WorkstationTasks.Transfer(LegacyItems.of(stack), i, moving); return false;
            }
            throw new TaskFailure("Chest has no space for " + LegacyItems.of(stack) + ".");
        }
    }

    static final class Give implements LegacyTask {
        final String name;
        final LegacyItems.Target target;
        final int amount;
        int given, waitUntil, phase, beforeDrop, dropDeadline;
        LegacyTask acquire;
        Give(String name, LegacyItems.Target target, int amount) { this.name = name; this.target = target; this.amount = amount; }
        public String describe() { return "give " + name + " " + target + " x" + amount; }
        public boolean tick(LegacyBot bot) {
            PlayerEntity player = MovementTasks.player(bot, name);
            if (acquire != null) { if (acquire.tick(bot)) { acquire.stop(bot); acquire = null; } return false; }
            if (given >= amount) { bot.say("Dropped " + given + " items near " + name + "."); return true; }
            if (phase == 2) {
                int current = bot.count(target);
                if (current == beforeDrop - 1) { given++; phase = 0; waitUntil = bot.ticks + 4; }
                else if (current < beforeDrop - 1) throw new TaskFailure("Inventory changed unexpectedly while giving items.");
                else if (bot.ticks > dropDeadline) throw new TaskFailure("Server did not confirm the item drop.");
                return false;
            }
            if (bot.count(target) < amount - given) { acquire = new AcquisitionTask(target, amount - given, "get"); return false; }
            if (LegacyPos.squaredDistance(bot.mc.field_3805,player) > 6) {
                bot.navigation.move(new PathSearch.Pos((int)Math.floor(player.x), (int)Math.floor(player.y), (int)Math.floor(player.z)), 1.5); return false;
            }
            bot.releaseMovement();
            if (bot.ticks < waitUntil) return false;
            bot.look(player.x, player.boundingBox.minY + LegacyPos.eyeHeight(player), player.z);
            if (phase == 0) {
                bot.equip(bot.findInventory(target));
                phase = 1; waitUntil = bot.ticks + 3; return false;
            }
            // Let the regular interaction-manager tick synchronize the selected hotbar slot
            // before sending a drop packet, then observe the server's inventory update.
            beforeDrop = bot.count(target);
            bot.mc.field_3805.dropSelectedItem(false);
            phase = 2; dropDeadline = bot.ticks + 40; return false;
        }
        public void stop(LegacyBot bot) { if (acquire != null) acquire.stop(bot); }
    }
}
