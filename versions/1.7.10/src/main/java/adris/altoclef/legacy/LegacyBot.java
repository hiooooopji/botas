package adris.altoclef.legacy;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.*;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.text.LiteralText;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import org.apache.logging.log4j.LogManager;
import java.util.*;
import java.util.function.Predicate;

final class LegacyBot {
    final MinecraftClient mc = MinecraftClient.getInstance();
    final LegacyNavigation navigation = new LegacyNavigation(this);
    final NearbySupplies nearbySupplies = new NearbySupplies();
    boolean glitchless = Boolean.getBoolean("altoclef.legacy.glitchless");
    final LegacyTntDupe tntDupe = new LegacyTntDupe(this);
    final LegacySaveDupe saveDupe = new LegacySaveDupe(this);
    boolean canCollectWhileTravelling() {return mining==null && mc.field_3805.onGround;}
    int ticks;
    long maxWorkNanos;
    int slowWorkTicks;
    private LegacyTask task;
    private boolean paused;
    boolean hudEnabled = true;
    final TaskTimer timer = new TaskTimer();
    private String lastTask = "";
    List<String> hudLines() {
        List<String> lines = new ArrayList<>();
        if(tntDupe.active()) {
            lines.add("Botas 1.7.10 | "+(tntDupe.paused ? "Paused" : "Running"));
            lines.add(tntDupe.describe());return lines;
        }
        if(saveDupe.active()) {
            lines.add("Botas 1.7.10 | "+(saveDupe.paused ? "Paused" : "Running"));
            lines.add(saveDupe.describe());return lines;
        }
        String state = task == null ? "Idle" : suspended() ? "Paused" : "Running";
        lines.add("Botas 1.7.10 | " + state + " | " + timer.display(System.nanoTime()));
        String chain = task == null ? lastTask : task.describe()+(waterEscape==null ? "" : " -> "+waterEscape.describe())+(nearbySupplies.active() ? " -> "+nearbySupplies.describe() : "");
        if (!chain.isEmpty()) {
            String[] tasks = chain.split(" -> ");
            for (int i=0;i<Math.min(tasks.length,10);i++) lines.add((i==0 ? "" : "  > ") + tasks[i]);
        }
        return lines;
    }
    private boolean suspended() {
        return paused || mc.isPaused() || (mc.currentScreen != null && !(mc.currentScreen instanceof HandledScreen && mc.field_3805.openScreenHandler == ownedScreen));
    }
    private Object taskWorld;
    boolean blazeRodsReady() {return task instanceof LegacyFortressTask && ((LegacyFortressTask)task).rodsReady;}
    boolean fortressReached() {return task instanceof LegacyFortressTask && ((LegacyFortressTask)task).arrived;}
    private final Set<KeyBinding> pressed = new HashSet<>();
    ScreenHandler ownedScreen;
    Class<? extends ScreenHandler> expectedScreen;
    private BlockPos mining;
    private int miningTicks;
    void say(String message) {
        LogManager.getLogger("Botas/Legacy").info(message);
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(new LiteralText("[Botas 1.7.10] " + message));
    }

    void command(String input) {
        String[] a = input.trim().replaceFirst("^@\\s+", "@").split("\\s+");
        String cmd = a[0].substring(1).toLowerCase(Locale.ROOT);
        try {
            if (cmd.equals("help") || cmd.isEmpty()) {
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] Commands: @get item [count], @craft item [count], @smelt item [count], @goto x [y] z,");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @follow player, @equip item, @food [count], @meat [count], @deposit item [count], @stash,");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @give player item [count], @inventory, @coords, @list [filter], @status, @stop, @pause, @unpause, @gamma value.");
                say("[NON-SPEEDRUN: UI] @remove hud hides the Botas overlay; @hud on/off restores or hides it.");
                say("[NON-SPEEDRUN: EXPERIMENT] @dupe item [fuse 0..5] attempts the TNT chest glitch with one item and leaves the result untouched.");
                say("[ROUTE / RESOURCES] @trade item [count] searches for villager offers; @get emerald trades paper/books.");
                say("[ROUTE / RESOURCES] @structurekit [temple] [nether] obtains a one-bucket kit with shears from structure loot; temple uses a temple with at least nine iron in this world, and nether continues through portal building and entry.");
                say("[ROUTE / RESOURCES] @glitchless starts temple preparation on this seed with the boat hunger glitch disabled.");
                say("[ROUTE / RESOURCES] @overworld prepares supplies only. @overworld nether also builds a portal and enters the Nether.");
                say("[ROUTE / RESOURCES] @fortress scans 32 chunks in the Nether, budgets bridges and travels to a fortress with combat defence.");
                say("[ROUTE / RESOURCES] After seven rods, @fortress returns to the Overworld and builds a sword tower. @tower [pearls] tests that stage directly (default 12).");
                say("[ROUTE / RESOURCES] @tower base builds the recorded first Enderman tower section and stops.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] Walking uses loaded terrain; @gamer is excluded. Item metadata: planks:2; modern wood names are accepted.");
                return;
            }
            if (cmd.equals("stop")) { tntDupe.cancel();saveDupe.cancel();stop(); say("Stopped."); return; }
            if(tntDupe.active()) {
                if(cmd.equals("pause")) {tntDupe.paused=true;releaseMovement();cancelMining();return;}
                if(cmd.equals("unpause") || cmd.equals("resume")) {tntDupe.paused=false;return;}
                if(cmd.equals("status")) {say(tntDupe.describe());return;}
                throw new TaskFailure("A TNT chest attempt is active. Use @stop to cancel.");
            }
            if(saveDupe.active()) {
                if(cmd.equals("pause")) {saveDupe.paused=true;releaseMovement();cancelMining();say("Duplication paused.");return;}
                if(cmd.equals("unpause") || cmd.equals("resume")) {saveDupe.paused=false;say("Duplication resumed.");return;}
                if(cmd.equals("status")) {say("Vanilla save/reload duplication "+(saveDupe.paused ? "paused" : "in progress"));return;}
                throw new TaskFailure("Use @stop to cancel the duplication attempt before starting another command.");
            }
            if (cmd.equals("status")) { say(task == null ? "Idle." : (paused ? "Paused: " : "Running: ") + task.describe()); return; }
            if (cmd.equals("pause")) { timer.setRunning(System.nanoTime(),false); paused = true; releaseMovement(); cancelMining(); say("Paused."); return; }
            if (cmd.equals("unpause") || cmd.equals("resume")) { paused = false; navigation.reset(); say("Resumed."); return; }
            if (cmd.equals("remove") && a.length == 2 && a[1].equalsIgnoreCase("hud")) {
                hudEnabled=false; say("Botas HUD hidden. Use @hud on to restore it."); return;
            }
            if (cmd.equals("hud")) {
                if (a.length != 2 || !(a[1].equals("on") || a[1].equals("off"))) throw new TaskFailure("Usage: @hud on/off");
                hudEnabled=a[1].equals("on"); say("Task display " + a[1] + "."); return;
            }
            if (cmd.equals("gamer")) throw new TaskFailure("@gamer is excluded from the 1.7.10 port.");
            if (mc.field_3805 == null || mc.world == null) throw new TaskFailure("Join a world first.");
            switch (cmd) {
                case "dupe":
                    if(a.length<2 || a.length>3) throw new TaskFailure("Usage: @dupe item [fuse 0..5]");
                    tntDupe.start(LegacyItems.parse(a[1]),a.length>2 ? Integer.parseInt(a[2]) : 0);return;
                case "dupe_save":
                    if(a.length<2 || a.length>3) throw new TaskFailure("Usage: @dupe item [count]");
                    saveDupe.start(LegacyItems.parse(a[1]),amount(a,2,1));return;
                case "coords":
                    exact(a, 1, "@coords"); say(navigation.feet().toString()); return;
                case "dumpmold":
                    exact(a,1,"@dumpmold");
                    BlockPos center=LegacyPos.of(mc.field_3805);java.util.List<BlockPos> mold=new java.util.ArrayList<>();
                    for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) for(int dy=-4;dy<=10;dy++) {
                        BlockPos p=LegacyPos.add(center,dx,dy,dz);
                        String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(mc.world,p).getBlock()));
                        if(id.equals("minecraft:cobblestone")) mold.add(p);
                    }
                    mold.sort(java.util.Comparator.comparingInt((BlockPos p) -> p.y).thenComparingInt(p -> p.x).thenComparingInt(p -> p.z));
                    say("MOLD_DUMP center="+center+" blocks="+mold);return;
                case "inventory": case "inv":
                    exact(a, 1, "@inventory");
                    Map<String, Integer> counts = new TreeMap<>();
                    for (ItemStack s : mc.field_3805.inventory.main) if (s != null)
                        counts.put(LegacyItems.of(s).toString(), counts.getOrDefault(LegacyItems.of(s).toString(), 0) + s.count);
                    if (counts.isEmpty()) say("Inventory is empty.");
                    else for (Map.Entry<String, Integer> e : counts.entrySet()) say(e.getKey() + " x" + e.getValue());
                    return;
                case "list":
                    if (a.length > 2) throw new TaskFailure("Usage: @list [filter]");
                    List<String> names = new ArrayList<>();
                    for (Object item : Item.REGISTRY) {
                        String name = LegacyItems.id((Item) item);
                        if (a.length == 1 || name.contains(a[1].toLowerCase(Locale.ROOT))) names.add(name);
                    }
                    Collections.sort(names);
                    say("Minecraft item names (not all have automatic acquisition routes):");
                    for (int i = 0; i < Math.min(80, names.size()); i += 8) say(String.join(", ", names.subList(i, Math.min(i + 8, Math.min(80, names.size())))));
                    if (names.size() > 80) say("Use @list <filter> to narrow the " + names.size() + " matches.");
                    return;
                case "gamma": case "setgamma":
                    exact(a, 2, "@gamma value");
                    float gamma = Float.parseFloat(a[1]);
                    if (!Float.isFinite(gamma) || gamma < 0 || gamma > 16) throw new TaskFailure("Gamma must be 0..16.");
                    mc.options.gamma = gamma; say("Gamma: " + gamma); return;
                case "get": case "craft": case "smelt":
                    if (a.length < 2 || a.length > 3) throw new TaskFailure("Usage: @" + cmd + " item [count]");
                    start(new AcquisitionTask(LegacyItems.parse(a[1]), amount(a, 2, 1), cmd)); return;
                case "trade":
                    if(a.length<2 || a.length>3) throw new TaskFailure("Usage: @trade item [count]");
                    start(new VillagerTradeTask(LegacyItems.parse(a[1]),amount(a,2,1),null,false));return;
                case "structurekit":
                    boolean templeOnly=a.length>=2 && a[1].equalsIgnoreCase("temple");
                    boolean enterNether=a.length>=2 && a[a.length-1].equalsIgnoreCase("nether");
                    if(a.length>3 || a.length==3 && !(templeOnly && enterNether)
                            || a.length==2 && !(templeOnly || enterNether)) throw new TaskFailure("Usage: @structurekit [temple] [nether]");
                    if(mc.getServer()==null || mc.field_3805.dimension!=0) throw new TaskFailure("@structurekit requires a single-player Overworld; it scans real server-side chest contents and retries seeds.");
                    start(new LegacyStructureRun(enterNether,templeOnly));return;
                case "fortress":
                    exact(a,1,"@fortress");start(new LegacyFortressTask());return;
                case "tower": case "swordtower":
                    if(a.length==2 && a[1].equalsIgnoreCase("base")) {start(new LegacyTowerBaseTask());return;}
                    if(a.length>2) throw new TaskFailure("Usage: @tower [base|pearls]");
                    start(new LegacySwordTowerTask(amount(a,1,12)));return;
                case "towerbase":
                    exact(a,1,"@towerbase");start(new LegacyTowerBaseTask());return;
                case "dragon":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("practice")) throw new TaskFailure("Usage: @dragon [practice]");
                    start(new LegacyEndBattleTask(a.length==2));return;
                case "glitchless":
                    exact(a,1,"@glitchless");glitchless=true;
                    start(new LegacyStructureRun(true,true));return;
                case "overworld": case "overwold":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("prepare") && !a[1].equalsIgnoreCase("nether"))
                        throw new TaskFailure("Usage: @overworld [prepare|nether]");
                    start(new LegacyOverworldTask(a.length==1 || !a[1].equalsIgnoreCase("nether")));return;
                case "goto":
                    if (a.length != 3 && a.length != 4) throw new TaskFailure("Usage: @goto x z OR @goto x y z (supports ~relative coordinates)");
                    int x = coordinate(a[1], mc.field_3805.x), z = coordinate(a[a.length - 1], mc.field_3805.z);
                    Integer y = a.length == 4 ? coordinate(a[2], mc.field_3805.boundingBox.minY) : null;
                    if (y != null && (y < 1 || y > 254)) throw new TaskFailure("Y must be 1..254.");
                    start(new MovementTasks.GoTo(x, y, z)); return;
                case "follow":
                    exact(a, 2, "@follow player"); start(new MovementTasks.Follow(a[1])); return;
                case "equip":
                    exact(a, 2, "@equip item"); start(new InventoryTasks.Equip(LegacyItems.parse(a[1]))); return;
                case "food": case "meat":
                    if (a.length > 2) throw new TaskFailure("Usage: @" + cmd + " [count]");
                    start(new LegacyFoodTask(amount(a, 1, 8))); return;
                case "deposit":
                    if (a.length < 2 || a.length > 3) throw new TaskFailure("Usage: @deposit item [count]");
                    start(new InventoryTasks.Deposit(LegacyItems.parse(a[1]), amount(a, 2, 64))); return;
                case "stash":
                    exact(a, 1, "@stash"); start(new InventoryTasks.Deposit(null, 4096)); return;
                case "give":
                    if (a.length < 3 || a.length > 4) throw new TaskFailure("Usage: @give player item [count]");
                    start(new InventoryTasks.Give(a[1], LegacyItems.parse(a[2]), amount(a, 3, 1))); return;
                case "idle":
                    exact(a, 1, "@idle"); stop(); say("Idle."); return;
                default: throw new TaskFailure("Unknown or unported command @" + cmd + ". Use @help for implemented commands.");
            }
        } catch (NumberFormatException e) { say("Invalid number. Use whole counts and coordinates."); }
        catch (TaskFailure e) { say(e.getMessage()); }
    }

    static int amount(String[] args, int index, int fallback) {
        if (args.length <= index) return fallback;
        int n = Integer.parseInt(args[index]);
        if (n < 1 || n > 4096) throw new TaskFailure("Count must be 1..4096.");
        return n;
    }
    private static int coordinate(String token, double current) {
        long result = token.startsWith("~") ? (long) Math.floor(current) + (token.length() == 1 ? 0 : Long.parseLong(token.substring(1))) : Long.parseLong(token);
        if (result < -29999984 || result > 29999984) throw new TaskFailure("Coordinate is outside the world border.");
        return (int) result;
    }
    private static void exact(String[] args, int count, String usage) {
        if (args.length != count) throw new TaskFailure("Usage: " + usage);
    }
    private WaterEscapeTask waterEscape;
    void start(LegacyTask next) {
        if (mc.field_3805.inventory.getCursorStack() != null) throw new TaskFailure("Put down the item on your inventory cursor first.");
        stop(); waterEscape=WaterEscapeTask.wet(this) ? new WaterEscapeTask() : null; task = next; taskWorld = mc.world; paused = false; timer.reset(System.nanoTime()); timer.setRunning(System.nanoTime(),true); say("Started: " + next.describe());
    }
    void stop() {
        tntDupe.result=null;
        timer.setRunning(System.nanoTime(),false);
        if (task != null) { lastTask=task.describe(); task.stop(this); }
        nearbySupplies.reset();waterEscape=null; task = null; paused = false; releaseMovement(); cancelMining(); navigation.reset();
        recoverCursor(); closeOwnedScreen();
    }
    boolean busy() { return task != null; }
    boolean protectsTowerBase(BlockPos pos) {
        return task instanceof LegacyTowerBaseTask && ((LegacyTowerBaseTask)task).protects(pos);
    }
    boolean structurePaused() {return paused || mc.world!=null && mc.isPaused();}
    void bindStructureWorld() {taskWorld=mc.world;navigation.reset();nearbySupplies.reset();waterEscape=WaterEscapeTask.wet(this) ? new WaterEscapeTask() : null;}
    void tick() {
        ticks++;
        tntDupe.tick();
        if(tntDupe.active() || "inspect".equals(tntDupe.result) || "fail".equals(tntDupe.result)) return;
        saveDupe.tick();
        if(saveDupe.active()) return;
        if (task == null) return;
        try {
            if(task instanceof LegacyStructureRun && ((LegacyStructureRun)task).transition(this)) return;
        } catch(RuntimeException failure) {
            stop();say("Could not create the next structure world: "+failure.getMessage());return;
        }
        if(mc.field_3805!=null && mc.world!=null && mc.world!=taskWorld && (task instanceof LegacyOverworldTask
                || task instanceof LegacyStructureRun && ((LegacyStructureRun)task).enteringNether())
                && mc.field_3805.dimension==-1) taskWorld=mc.world;
        if(mc.field_3805!=null && mc.world!=null && mc.world!=taskWorld && task instanceof LegacyFortressTask
                && ((LegacyFortressTask)task).returningToOverworld() && mc.field_3805.dimension==0) {
            taskWorld=mc.world;navigation.reset();nearbySupplies.reset();
        }
        if (mc.field_3805 == null || mc.world != taskWorld || !mc.field_3805.isAlive()) { stop(); return; }
        if (expectedScreen != null && mc.field_3805.openScreenHandler != mc.field_3805.playerScreenHandler) {
            if (!expectedScreen.isInstance(mc.field_3805.openScreenHandler)) {
                stop(); say("Unexpected container opened; task stopped."); return;
            }
            ownedScreen = mc.field_3805.openScreenHandler; expectedScreen = null;
        }
        timer.setRunning(System.nanoTime(), !suspended());
        if (suspended()) {
            releaseMovement(); return;
        }
        long workStarted=System.nanoTime();
        try {
            if(ticks%1000==0 && Boolean.getBoolean("altoclef.legacy.smoke"))
                LogManager.getLogger("Botas/Legacy").info("Bot work timing: max {} ms, {} ticks above 50 ms",maxWorkNanos/1000000L,slowWorkTicks);
            if (ticks % 100 == 0) LogManager.getLogger("Botas/Legacy").info("Task status: {} at {} (screen {}, grounded {})",
                    task.describe(), navigation.feet(), mc.field_3805.openScreenHandler.getClass().getSimpleName(),mc.field_3805.onGround);
            if(waterEscape!=null) {
                closeOwnedScreen();
                if(waterEscape.tick(this)) {waterEscape.stop(this);waterEscape=null;}
                return;
            }
            if(nearbySupplies.continueDetour(this)) return;
            if (task.tick(this)) { String done = task.describe(); stop(); say("Finished: " + done); }
        } catch (TaskFailure e) {
            LogManager.getLogger("Botas/Legacy").warn("Task stopped: {}",task.describe(),e);
            stop(); say("Stopped: " + e.getMessage());
        }
        catch (RuntimeException e) {
            stop(); LogManager.getLogger("Botas/Legacy").error("Task failed", e); say("Task failed; see the client log.");
        } finally {
            long elapsed=System.nanoTime()-workStarted;
            maxWorkNanos=Math.max(maxWorkNanos,elapsed);if(elapsed>50000000L) slowWorkTicks++;
        }
    }
    void key(KeyBinding key, boolean down) {
        KeyBinding.setKeyPressed(key.getCode(), down);
        if (down) pressed.add(key); else pressed.remove(key);
    }
    private boolean sprinting;
    void sprint(boolean enabled) {
        key(mc.options.sprintKey, enabled);
        if (mc.field_3805 != null && (enabled || sprinting)) mc.field_3805.setSprinting(enabled);
        sprinting = enabled;
    }
    void releaseMovement() {
        sprint(false);
        for (KeyBinding key : pressed) KeyBinding.setKeyPressed(key.getCode(), false);
        pressed.clear();
    }
    int count(LegacyItems.Target target) {
        int result = 0;
        for (ItemStack s : mc.field_3805.inventory.main) if (target.matches(s)) result += s.count;
        return result;
    }
    int findInventory(LegacyItems.Target target) {
        if(target.foodFamily) {
            // Use ordinary ready food first, retaining flesh as a fallback.
            for(int i=0;i<36;i++) {
                ItemStack stack=mc.field_3805.inventory.main[i];
                if(target.matches(stack) && !LegacyItems.id(stack.getItem()).equals("rotten_flesh")) return i;
            }
        }
        for (int i = 0; i < 36; i++) if (target.matches(mc.field_3805.inventory.main[i])) return i;
        return -1;
    }
    int screenSlot(int inventoryIndex) {
        ScreenHandler h = mc.field_3805.openScreenHandler;
        for (int i = 0; i < h.slots.size(); i++) if (h.getSlot(i).equals(mc.field_3805.inventory, inventoryIndex)) return i;
        throw new TaskFailure("Player inventory is unavailable in this screen.");
    }
    void click(int slot, int button, int mode) {
        mc.interactionManager.clickSlot(mc.field_3805.openScreenHandler.syncId, slot, button, mode, mc.field_3805);
    }
    void equip(int index) {
        if (index < 0) throw new TaskFailure("Required item is missing.");
        if (index < 9) mc.field_3805.inventory.selectedSlot = index;
        else click(screenSlot(index), mc.field_3805.inventory.selectedSlot, 2);
    }
    void recoverCursor() {
        if (mc.field_3805 == null || mc.field_3805.inventory.getCursorStack() == null) return;
        // Never drop a carried stack to recover automation state.
        for (int i = 0; i < 36 && mc.field_3805.inventory.getCursorStack() != null; i++) {
            ItemStack cursor = mc.field_3805.inventory.getCursorStack(), stack = mc.field_3805.inventory.main[i];
            if (stack == null || (stack.getItem() == cursor.getItem() && stack.getData() == cursor.getData()
                    && Objects.equals(stack.getNbt(), cursor.getNbt()) && stack.count < stack.getMaxCount())) click(screenSlot(i), 0, 0);
        }
    }
    void closeOwnedScreen() {
        if(mc.field_3805!=null && ownedScreen instanceof net.minecraft.screen.VillagerScreenHandler
                && mc.field_3805.openScreenHandler==ownedScreen) {
            // Merchant inputs are returned before closing, just like crafting ingredients.
            for(int i=0;i<2;i++) if(ownedScreen.getSlot(i).getStack()!=null) {
                click(i,0,1);
                if(ownedScreen.getSlot(i).getStack()!=null) return;
            }
        }
        if (mc.field_3805 != null && ownedScreen instanceof CraftingScreenHandler && mc.field_3805.openScreenHandler == ownedScreen) {
            // Closing a full crafting grid can drop ingredients if the inventory is full.
            for (int i = 1; i <= 9; i++) if (ownedScreen.getSlot(i).getStack() != null) {
                click(i, 0, 1);
                if (ownedScreen.getSlot(i).getStack() != null) return;
            }
        }
        if (mc.field_3805 != null && ownedScreen != null && mc.field_3805.openScreenHandler == ownedScreen && mc.field_3805.inventory.getCursorStack() == null) mc.field_3805.closeHandledScreen();
        ownedScreen = null;
        expectedScreen = null;
    }
    void look(double x, double y, double z) {
        lookExact(x,y,z);
        if(!(task instanceof LegacyEndBattleTask) || mc.field_3805.dimension!=1) return;
        double eyeX=mc.field_3805.x,eyeY=mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(mc.field_3805),eyeZ=mc.field_3805.z;
        double dx=x-eyeX,dy=y-eyeY,dz=z-eyeZ;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(length<.001) return;
        for(Object entry:mc.world.loadedEntities) {
            if(!(entry instanceof EndermanEntity) || !((EndermanEntity)entry).isAlive()) continue;
            EndermanEntity enderman=(EndermanEntity)entry;
            double ex=enderman.x-eyeX,ey=enderman.y+enderman.getEyeHeight()-eyeY,ez=enderman.z-eyeZ;
            double distance=Math.sqrt(ex*ex+ey*ey+ez*ez);
            if(distance>32 || distance<.001) continue;
            double alignment=(dx*ex+dy*ey+dz*ez)/(length*distance);
            if(alignment>1-.035/distance) {
                // Keep the travel yaw, but look below the face. Precise
                // crystal attacks use lookExact and are never redirected.
                mc.field_3805.pitch=70;
                return;
            }
        }
    }
    void lookExact(double x, double y, double z) {
        double dx = x - mc.field_3805.x, dz = z - mc.field_3805.z, dy = y - mc.field_3805.boundingBox.minY - LegacyPos.eyeHeight(mc.field_3805);
        mc.field_3805.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.field_3805.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
    BlockHitResult hit(BlockPos pos) {
        return hitFrom(Vec3d.of(mc.field_3805.x, mc.field_3805.boundingBox.minY + LegacyPos.eyeHeight(mc.field_3805), mc.field_3805.z), pos);
    }
    BlockHitResult hitFrom(Vec3d eye, BlockPos pos) {
        double reach = mc.interactionManager.getReachDistance() - .1;
        // On continuous floors and walls, a center ray can hit the neighbor first.
        // Test each face as well, using the actual ray result for the mining packet.
        for (int face = -1; face < Direction.values().length; face++) {
            Vec3d aim = Vec3d.of(pos.x + .5, pos.y + .5, pos.z + .5);
            if (face >= 0) {
                BlockPos adjacent = LegacyPos.offset(pos, Direction.values()[face]);
                aim = Vec3d.of(aim.x + .499 * (adjacent.x - pos.x),
                        aim.y + .499 * (adjacent.y - pos.y), aim.z + .499 * (adjacent.z - pos.z));
            }
            double dx = eye.x - aim.x, dy = eye.y - aim.y, dz = eye.z - aim.z;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            BlockHitResult hit = mc.world.rayTrace(eye, aim);
            if (hit != null && pos.equals(LegacyPos.hit(hit))) return hit;
        }
        return null;
    }
    BlockPos nearestBlock(Predicate<BlockState> matches, int radius) {
        return nearestBlock(matches, radius, Collections.<BlockPos>emptySet());
    }
    BlockPos nearestBlock(Predicate<BlockState> matches, int radius, Set<BlockPos> rejected) {
        return nearestBlock(matches, radius, rejected, p -> true);
    }
    BlockPos nearestBlock(Predicate<BlockState> matches, int radius, Set<BlockPos> rejected, Predicate<BlockPos> usable) {
        BlockPos center = LegacyPos.of(mc.field_3805);
        BlockPos best = null; double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = center.x - radius; x <= center.x + radius; x++)
            for (int z = center.z - radius; z <= center.z + radius; z++)
                for (int y = Math.max(1, center.y - 8); y <= Math.min(254, center.y + 10); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    double d = LegacyPos.squaredDistanceToCenter(mc.field_3805,p);
                    if (d < bestDistance && !rejected.contains(p) && LegacyWorld.isLoaded(mc.world,p, false) && matches.test(LegacyWorld.state(mc.world,p)) && usable.test(p)) { best = p; bestDistance = d; }
                }
        return best;
    }
    boolean interactiveSupport(BlockPos pos) {
        String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(mc.world,pos).getBlock()));
        return LegacyWorld.blockEntity(mc.world,pos)!=null || id.equals("minecraft:crafting_table")
                || id.contains("door") || id.contains("button") || id.equals("minecraft:lever");
    }
    boolean mine(BlockPos pos) {
        if (LegacyWorld.isAir(mc.world,pos)) { cancelMining(); return true; }
        // Seeing a face during a jump is not a completed approach. Mining would
        // release movement, fall out of reach, cancel progress and jump again.
        if(!mc.field_3805.onGround) {cancelMining();navigation.approach(pos);return false;}
        BlockHitResult hit = hit(pos);
        if (hit == null) {
            if(clearMiningObstruction(pos)) return false;
            // Preserve an access-route excavation between ticks. Cancelling
            // it here restarts its breaking progress on every approach attempt.
            if(pos.equals(mining)) cancelMining();
            navigation.approach(pos); return false;
        }
        // Aim inside the selected face. Exact boundary coordinates can round
        // onto a neighboring vine when vanilla recomputes the held-click ray.
        Direction miningFace=Direction.getById(hit.side);
        releaseMovement(); look(hit.pos.x-miningFace.getOffsetX()*.002,
                hit.pos.y-miningFace.getOffsetY()*.002,hit.pos.z-miningFace.getOffsetZ()*.002);
        Block block = LegacyWorld.state(mc.world,pos).getBlock();
        int tool=miningTool(block);
        if(tool<0) throw new TaskFailure("No suitable usable tool for "+Block.REGISTRY.getId(block));
        equip(tool);
        if (!pos.equals(mining)) { cancelMining(); mining = pos; LegacyInteraction.attackBlock(mc.interactionManager, pos, net.minecraft.util.math.Direction.getById(hit.side)); }
        // Vanilla cancels mining every tick when attack is released. Hold the
        // normal input and let vanilla advance progress once per game tick.
        key(mc.options.attackKey, true);
        mc.field_3805.swingHand();
        if (++miningTicks > 800) throw new TaskFailure("Block could not be mined (protection, tool or reach).");
        return false;
    }
    boolean clearMiningObstruction(BlockPos target) {
        Vec3d eye=Vec3d.of(mc.field_3805.x,mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(mc.field_3805),mc.field_3805.z);
        BlockHitResult obstruction=mc.world.rayTrace(eye,Vec3d.of(target.x+.5,target.y+.5,target.z+.5));
        if(obstruction==null) return false;
        BlockPos p=LegacyPos.hit(obstruction);
        if(p.equals(target) || hit(p)==null) return false;
        String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(mc.world,p).getBlock()));
        if(!id.equals("minecraft:vine") && !id.equals("minecraft:tallgrass") && !id.equals("minecraft:double_plant")) return false;
        mine(p);return true;
    }
    int miningTool(Block block) {
        net.minecraft.block.material.Material material = block.getMaterial();
        boolean needsTool = material == net.minecraft.block.material.Material.STONE
                || material == net.minecraft.block.material.Material.IRON || material == net.minecraft.block.material.Material.ANVIL;
        int tool = -1; float speed = 1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.field_3805.inventory.main[i];
            if (s != null && (!needsTool || s.isEffectiveOn(block)) && s.getMiningSpeedMultiplier(block) > speed
                    && (!s.isDamageable() || s.getDamage() < s.getMaxDamage() - 1)) {
                tool = i; speed = s.getMiningSpeedMultiplier(block);
            }
        }
        if(tool>=0 || needsTool) return tool;
        // No shovel/axe/etc. available: use an empty hand or a non-tool item,
        // rather than wearing out whichever pickaxe or sword was held last.
        for(int i=0;i<9;i++) if(mc.field_3805.inventory.main[i]==null) return i;
        for(int i=0;i<36;i++) {
            ItemStack stack=mc.field_3805.inventory.main[i];
            if(stack!=null && !stack.isDamageable()) return i;
        }
        return -1;
    }
    boolean holdsMiningInput() {
        return mining != null && task != null && !suspended() && mc.options.attackKey.isPressed();
    }
    void cancelMining() {
        key(mc.options.attackKey, false);
        if (mining != null && mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        mining = null; miningTicks = 0;
    }
    boolean interact(BlockPos pos) {
        BlockHitResult hit = hit(pos);
        if (hit == null) { navigation.approach(pos); return false; }
        releaseMovement(); look(hit.pos.x, hit.pos.y, hit.pos.z);
        LegacyInteraction.rightClick(mc.interactionManager,mc.field_3805, mc.world, mc.field_3805.inventory.getMainHandStack(), pos, net.minecraft.util.math.Direction.getById(hit.side), hit.pos);
        return true;
    }
}
