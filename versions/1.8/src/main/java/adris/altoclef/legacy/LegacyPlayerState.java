package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.*;

/** Player-only rollback, like LoTAS's Dupe Mod, not a whole-world savestate.
 * All entry points run on the integrated server thread. No upstream code is copied. */
final class LegacyPlayerState {
    private final Map<IntegratedServer, Map<UUID, Snapshot>> sessions = new WeakHashMap<>();

    private static final class Snapshot {
        final int dimension;
        final NbtCompound data;
        Snapshot(ServerPlayerEntity player) {
            dimension = player.dimension;
            NbtCompound captured = new NbtCompound();
            player.writePlayerData(captured);
            // Item serialization can retain the live stack's tag object.
            data = (NbtCompound) captured.copy();
        }
    }

    static void requirePrivate(IntegratedServer server) {
        if (server == null || server.isPublished())
            throw new TaskFailure("Player-state duplication requires a private single-player world.");
    }

    private static void ready(IntegratedServer server, ServerPlayerEntity player) {
        requirePrivate(server);
        if (player == null || !player.isAlive()) throw new TaskFailure("A living player is required.");
        if (player.vehicle != null) throw new TaskFailure("Leave the boat or mount before saving/loading player state.");
        if (player.openScreenHandler != player.playerScreenHandler || player.inventory.getCursorStack() != null)
            throw new TaskFailure("Close the container and put down the cursor item first.");
        for (int slot = 1; slot <= 4; slot++) if (player.playerScreenHandler.getSlot(slot).getStack() != null)
            throw new TaskFailure("Empty the inventory crafting grid before saving/loading player state.");
    }

    synchronized void save(IntegratedServer server, ServerPlayerEntity player) {
        ready(server, player);
        sessions.computeIfAbsent(server, key -> new HashMap<>()).put(player.getUuid(), new Snapshot(player));
    }

    synchronized NbtCompound load(IntegratedServer server, ServerPlayerEntity player) {
        ready(server, player);
        Map<UUID, Snapshot> players = sessions.get(server);
        Snapshot snapshot = players == null ? null : players.get(player.getUuid());
        if (snapshot == null) throw new TaskFailure("No player state saved in this session. Use @dupe save first.");
        if (snapshot.dimension != player.dimension) throw new TaskFailure("Return to the saved dimension before loading player state.");
        NbtCompound data = (NbtCompound) snapshot.data.copy();
        player.fromNbt(data);
        player.method_3170(GameMode.setGameModeWithId(data.getInt("playerGameType")));
        player.networkHandler.requestTeleport(player.x, player.y, player.z, player.yaw, player.pitch);
        player.inventory.markDirty();
        player.refreshScreenHandler(player.playerScreenHandler);
        player.playerScreenHandler.sendContentUpdates();
        player.sendAbilitiesUpdate();
        player.markHealthDirty();
        player.networkHandler.sendPacket(new HealthUpdateS2CPacket(player.getHealth(),
                player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel()));
        player.networkHandler.sendPacket(new ExperienceBarUpdateS2CPacket(player.experienceProgress,
                player.totalExperience, player.experienceLevel));
        player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
        return data;
    }

    /** Preflight the entire transfer, save, transfer the originals, then roll back the player.
     * Executed in one server operation so pickup cannot race the rollback. */
    synchronized NbtCompound duplicate(IntegratedServer server, ServerPlayerEntity player,
                                       LegacyItems.Target target, int count, BlockPos chest) {
        return duplicate(server,player,Collections.singletonMap(target,count),chest);
    }

    /** One checkpoint and rollback for the entire batch; all items/capacity are checked before mutation. */
    synchronized NbtCompound duplicate(IntegratedServer server, ServerPlayerEntity player,
                                       Map<LegacyItems.Target,Integer> batch, BlockPos chest) {
        return duplicate(server,player,batch,chest,false);
    }
    synchronized NbtCompound duplicate(IntegratedServer server, ServerPlayerEntity player,
                                       Map<LegacyItems.Target,Integer> batch, BlockPos chest,boolean fastPickup) {
        ready(server, player);
        if(batch.isEmpty() || batch.size()>36) throw new TaskFailure("Choose 1..36 item types to duplicate.");
        ItemStack[] remaining = copy(player.inventory.main);
        List<ItemStack> outgoing = new ArrayList<>();
        for(Map.Entry<LegacyItems.Target,Integer> entry:batch.entrySet()) {
        LegacyItems.Target target=entry.getKey();int count=entry.getValue();
        if(count<1 || count>4096) throw new TaskFailure("Count must be 1..4096.");
        int needed = count;
        for (int slot = 0; slot < remaining.length && needed > 0; slot++) {
            ItemStack stack = remaining[slot];
            if (!target.matches(stack)) continue;
            ItemStack part = stack.copy();
            part.count = Math.min(needed, stack.count);
            outgoing.add(part);
            stack.count -= part.count;
            needed -= part.count;
            if (stack.count == 0) remaining[slot] = null;
        }
        if (needed != 0) throw new TaskFailure("Carry at least " + count + " " + target + " before duplicating.");
        }

        Inventory destination = null;
        ItemStack[] deposited = null;
        if (chest != null) {
            if (!player.getServerWorld().isLoaded(chest, false)
                    || player.squaredDistanceTo(chest.getX() + .5, chest.getY() + .5, chest.getZ() + .5) > 36)
                throw new TaskFailure("Stand within six blocks of the chest.");
            Block block = player.world.getBlockState(chest).getBlock();
            if (!(block instanceof ChestBlock)) throw new TaskFailure("The selected chest is no longer there.");
            destination = ((ChestBlock) block).createScreenHandlerFactory(player.world, chest);
            if (destination == null || !destination.canPlayerUseInv(player))
                throw new TaskFailure("The chest cannot be opened here.");
            deposited = new ItemStack[destination.getInvSize()];
            for (int i = 0; i < deposited.length; i++) {
                ItemStack stack = destination.getInvStack(i);
                deposited[i] = stack == null ? null : stack.copy();
            }
            for (ItemStack stack : outgoing) insert(deposited, stack, destination.getInvMaxStackAmount());
        }

        // A failed preflight does not overwrite a manually saved state or move any items.
        save(server, player);
        System.arraycopy(remaining, 0, player.inventory.main, 0, remaining.length);
        if (destination != null) {
            for (int i = 0; i < deposited.length; i++) destination.setInvStack(i, deposited[i]);
            destination.markDirty();
        } else {
            for (ItemStack stack : outgoing) {
                ItemEntity dropped = player.dropStack(stack, false, true);
                if (dropped == null) throw new TaskFailure("Drop failed. The saved player state is available with @dupe load.");
                if(fastPickup) {
                    // TAS copies still exist as real drops and use normal pickup.
                    // Delay pickup until the client has applied the rollback.
                    dropped.refreshPositionAndAngles(player.x,player.y+.2,player.z,0,0);
                    dropped.velocityX=dropped.velocityZ=0;dropped.velocityY=-.2;
                    dropped.setPickupDelay(3);
                }
            }
        }
        return load(server, player);
    }

    private static ItemStack[] copy(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].copy();
        return result;
    }

    private static void insert(ItemStack[] slots, ItemStack original, int inventoryLimit) {
        int remaining = original.count;
        // Merge compatible stacks first; NBT differences (names/enchantments) must stay separate.
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < slots.length && remaining > 0; i++) {
            ItemStack stack = slots[i];
            if (pass == 0 ? stack == null : stack != null) continue;
            if (stack != null && (stack.getItem() != original.getItem() || stack.getData() != original.getData()
                    || !Objects.equals(stack.getNbt(), original.getNbt()))) continue;
            int room = Math.min(inventoryLimit, original.getMaxCount()) - (stack == null ? 0 : stack.count);
            int moved = Math.min(remaining, room);
            if (moved <= 0) continue;
            if (stack == null) { stack = original.copy(); stack.count = 0; slots[i] = stack; }
            stack.count += moved;
            remaining -= moved;
        }
        if (remaining != 0) throw new TaskFailure("Chest has insufficient space; no items were moved.");
    }
}
