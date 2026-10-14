package adris.altoclef.legacy;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

/** Private single-player rollback used by manual commands and opt-in resource duplication. */
final class LegacyDupeTask implements LegacyTask {
    enum Mode { SAVE, LOAD, DROP, CHEST }
    private final Mode mode;
    private final LegacyItems.Target target;
    private final int count;
    private final BlockPos chest;
    private final LegacyPlayerState state;
    private final Map<LegacyItems.Target,Integer> batch;
    private Future<NbtCompound> pending;
    private volatile boolean cancelled;
    boolean completed;

    LegacyDupeTask(Mode mode, LegacyItems.Target target, int count, BlockPos chest) {
        this(mode,target,count,chest,null);
    }
    LegacyDupeTask(Mode mode, LegacyItems.Target target, int count, BlockPos chest, LegacyPlayerState state) {
        this.mode = mode; this.target = target; this.count = count; this.chest = chest;
        this.state=state;
        this.batch=target==null ? Collections.emptyMap() : Collections.singletonMap(target,count);
    }

    LegacyDupeTask(Map<LegacyItems.Target,Integer> batch,BlockPos chest,LegacyPlayerState state) {
        this.mode=chest==null ? Mode.DROP : Mode.CHEST;this.target=null;this.count=0;
        this.chest=chest;this.state=state;this.batch=new LinkedHashMap<>(batch);
    }

    static void command(LegacyBot bot, String command, String[] args) {
        LegacyPlayerState.requirePrivate(bot.mc.getServer());
        Mode mode;
        LegacyItems.Target target = null;
        int count = 0;
        BlockPos chest = null;
        Map<LegacyItems.Target,Integer> batch=new LinkedHashMap<>();
        if (command.equals("savestate") || command.equals("loadstate")) {
            if (args.length != 1) throw new TaskFailure("Usage: @" + command);
            mode = command.equals("savestate") ? Mode.SAVE : Mode.LOAD;
        } else if (args.length == 2 && (args[1].equalsIgnoreCase("save") || args[1].equalsIgnoreCase("load"))) {
            mode = args[1].equalsIgnoreCase("save") ? Mode.SAVE : Mode.LOAD;
        } else {
            boolean toChest = args.length > 1 && args[1].equalsIgnoreCase("chest");
            int itemIndex = toChest ? 2 : 1;
            int supplied=args.length-itemIndex;
            if(supplied<1 || supplied>72 || supplied>2 && supplied%2!=0)
                throw new TaskFailure("Usage: @dupe [chest] item [count], or @dupe chest apple 8 gold_ingot 4 log 1");
            for(int i=itemIndex;i<args.length;i+=2) batch.put(LegacyItems.parse(args[i]),LegacyBot.amount(args,i+1,1));
            mode = Mode.DROP;
            if (toChest) bot.say("Botas uses drop duplication; the old chest keyword is ignored.");
        }
        if (bot.mc.player.openScreenHandler != bot.mc.player.playerScreenHandler)
            throw new TaskFailure("Close the container before saving/loading player state.");
        bot.start(batch.isEmpty() ? new LegacyDupeTask(mode,target,count,chest) : new LegacyDupeTask(batch,chest,null));
    }

    public String describe() {
        if (mode == Mode.SAVE) return "save player state (world unchanged)";
        if (mode == Mode.LOAD) return "load player state (world unchanged)";
        return "duplicate " + batch + (mode == Mode.CHEST ? " into chest" : " as drops");
    }

    public boolean tick(LegacyBot bot) {
        bot.releaseMovement();
        if (pending == null) {
            IntegratedServer server = bot.mc.getServer();
            LegacyPlayerState.requirePrivate(server);
            UUID uuid = bot.mc.player.getUuid();
            String name = bot.mc.player.getGameProfile().getName();
            final boolean fastPickup=bot.tasTravel();
            pending = server.method_10815(() -> {
                if (cancelled) throw new TaskFailure("Player-state operation cancelled.");
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
                if (player == null || !uuid.equals(player.getUuid())) throw new TaskFailure("Player session changed.");
                if (mode == Mode.SAVE) { bot.playerState.save(server, player); return null; }
                if (mode == Mode.LOAD) return bot.playerState.load(server, player);
                return (state==null ? bot.playerState : state).duplicate(server, player, batch, chest,fastPickup);
            });
            return false;
        }
        if (!pending.isDone()) return false;
        try {
            NbtCompound restored = pending.get();
            if (restored != null) {
                // Match server rollback locally as well (motion, hunger, XP, pose and inventory).
                // Vanilla packets also synchronize inventory/stats and the authoritative teleport.
                bot.mc.player.fromNbt((NbtCompound) restored.copy());
                bot.mc.interactionManager.setGameMode(GameMode.setGameModeWithId(restored.getInt("playerGameType")));
                bot.navigation.reset();
            }
            completed = true;
            bot.say(mode == Mode.SAVE ? "Player state saved for this session. Drop items or deposit them, then use @dupe load."
                    : mode == Mode.LOAD ? "Player state restored; world drops and chest contents were kept."
                    : "Duplicated batch " + batch + "; extra items are " + (mode == Mode.CHEST ? "in the chest." : "on the ground."));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new TaskFailure("Player-state operation interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TaskFailure) throw (TaskFailure) cause;
            throw new TaskFailure("Player-state operation failed: " + cause);
        }
    }

    public void stop(LegacyBot bot) {
        cancelled = true;
        if (pending != null && !pending.isDone()) pending.cancel(false);
    }
}
