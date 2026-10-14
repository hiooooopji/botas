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
    final LegacyPlayerState playerState = new LegacyPlayerState();
    boolean autoDupe;
    BlockPos practiceOrigin;
    Object practiceWorld;
    boolean autoDupeAvailable() {return autoDupe && mc.getServer()!=null && !mc.getServer().isPublished();}
    boolean canCollectWhileTravelling() {return !(task instanceof TasTask) && mining==null && mc.player.onGround && mc.player.vehicle==null;}
    boolean tasTravel() {return task instanceof TasTask;}
    int travelSprintJumps;
    int tasCrafts,maxTasCraftTicks;
    int tasPearlThrows,tasPearlLandings,tasGoldenApplesEaten;
    int tasEatingFlightTicks,tasDupeCycles,maxTasDupeTicks;
    int tasPearlDamageAvoided;
    int ticks;
    long maxWorkNanos;
    int slowWorkTicks;
    private LegacyTask task;
    private boolean paused;
    private int observedServerTick=-1;
    private long serverAdvancedAt;
    private boolean waitingForServer;
    boolean hudEnabled = true;
    final TaskTimer timer = new TaskTimer();
    private String lastTask = "";
    List<String> hudLines() {
        List<String> lines = new ArrayList<>();
        String state = task == null ? "Idle" : suspended() ? "Paused" : waitingForServer ? "Waiting for server" : "Running";
        lines.add("Botas 1.8 | " + state + " | " + timer.display(System.nanoTime()));
        if(LegacyTickControl.active()) lines.add("Game clock: "+LegacyTickControl.status());
        if(autoDupeAvailable()) lines.add("Resource duplication: on");
        String chain = task == null ? lastTask : task.describe()+(waterEscape==null ? "" : " -> "+waterEscape.describe())+(nearbySupplies.active() ? " -> "+nearbySupplies.describe() : "");
        if (!chain.isEmpty()) {
            String[] tasks = chain.split(" -> ");
            for (int i=0;i<Math.min(tasks.length,10);i++) lines.add((i==0 ? "" : "  > ") + tasks[i]);
        }
        return lines;
    }
    private boolean suspended() {
        if(!paused && task instanceof TasTask && ((TasTask)task).allowsScreen(this))return false;
        return paused || mc.isPaused() || (mc.currentScreen != null && !(mc.currentScreen instanceof HandledScreen && mc.player.openScreenHandler == ownedScreen));
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
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(new LiteralText("[Botas 1.8] " + message));
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
                say("[ASSISTED TAS] @dupe save/load rolls back player state only (private single-player); aliases @savestate/@loadstate.");
                say("[ASSISTED TAS] @dupe item [count] duplicates as drops. Chest duplication is replaced by drops.");
                say("[ASSISTED TAS] @autodupe on/off lets resource tasks duplicate a carried seed item and collect the copies.");
                say("[ASSISTED TAS] @tick pause/resume/normal, @tick rate 5, @tick step [count]; F6 toggles pause and F7 steps.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @parkour-smooth x y z navigates from live terrain and velocity without whole-course rehearsal.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @parkour cw2cw builds and runs a cobblewall-to-cobblewall neo with trapdoors flat against the sides.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @parkour h2h builds and runs a flat-momentum head-to-head neo with three level momentum jumps.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @parkour skills lists 16 techniques; @parkour skills map builds their arena; @parkour skills <name> builds and runs one.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @parkour map creates the marathon map; @parkour runmap rehearses and runs it from the emerald start; @parkour marathon creates and runs it.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @parkour challenge runs three three-block neos, a four-block jump and Andromeda bridging; @parkour x y z rehearses the whole loaded course, then runs continuously to destination feet coordinates; @parkour triple practices a triple neo; @parkour practice tests gap/neo/extension jumps.");
                say("[ASSISTED TAS] @tasloot prepares supplies and enters the End, then rehearses a SIMULATED credits warp: setup, death, bed respawn and portal return. @tasloot end stops at first End entry. @creditswarp starts the rehearsal from the End platform.");
                say("[ASSISTED TAS] Batch drop duplication: @dupe apple 8 gold_ingot 4 log 1 (one checkpoint for every item).");
                say("[ASSISTED TAS] @tasenderman teleports an existing loaded Enderman beside the player, kills it, and collects its guaranteed pearl.");
                say("[ROUTE / RESOURCES] @trade item [count] searches for villager offers; @get emerald trades paper/books.");
                say("[ROUTE / RESOURCES] @structurekit [nether] retries seeds for nearby village/temple loot; nether continues through portal building and entry.");
                say("[ROUTE / RESOURCES] @overworld prepares supplies only. @overworld nether also builds a portal and enters the Nether.");
                say("[ROUTE / RESOURCES] @fortress scans 32 chunks in the Nether, budgets bridges and travels to a fortress with combat defence.");
                say("[ROUTE / RESOURCES] After seven rods, @fortress returns to the Overworld and builds a sword tower. @tower [pearls] tests that stage directly (default 12).");
                say("[ROUTE / RESOURCES] @tower base builds the recorded first Enderman tower section and stops.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] @stronghold uses the seed to fly to and enter the nearest stronghold in Creative; @stronghold scan uses the diagonal render scan.");
                say("[ROUTE / RESOURCES] @templevillage gets stone tools, temple TNT, 30 sugar cane, then village crop emeralds.");
                say("[ROUTE / RESOURCES] @templeloot (or @templevillage temple) runs phase 1 only: loot the temple, collect TNT and get outside.");
                say("[ROUTE / RESOURCES] @templevillage cane runs phase 2 only: collect 30 sugar cane and stop.");
                say("[NON-SPEEDRUN: UTILITY / PRACTICE] Walking uses loaded terrain; @gamer is excluded. Item metadata: planks:2; modern wood names are accepted.");
                return;
            }
            if (cmd.equals("stop")) { stop(); say("Stopped."); return; }
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
            if (cmd.equals("gamer")) throw new TaskFailure("@gamer is excluded from the 1.8 port.");
            if (mc.player == null || mc.world == null) throw new TaskFailure("Join a world first.");
            switch (cmd) {
                case "tasenderman":
                    exact(a,1,"@tasenderman");LegacyPlayerState.requirePrivate(mc.getServer());
                    start(new TasTask(new EndermanCombatTask(true)));return;
                case "tasloot":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("end") && !a[1].equalsIgnoreCase("credits"))throw new TaskFailure("Usage: @tasloot [credits|end]");
                    LegacyPlayerState.requirePrivate(mc.getServer());
                    LegacyTask route=new SeedChestTradeTask();
                    start(new TasTask(a.length==2 && a[1].equalsIgnoreCase("end")?route:new TasCreditsRunTask(route)));return;
                case "creditswarp":
                    exact(a,1,"@creditswarp");LegacyPlayerState.requirePrivate(mc.getServer());
                    start(new TasTask(new SimulatedCreditsWarpTask()));return;
                case "parkour-smooth":
                    exact(a,4,"@parkour-smooth x y z (destination feet position)");
                    start(new SmoothParkourTask(parkourCoordinate(a[1],mc.player.x),parkourCoordinate(a[2],mc.player.y),parkourCoordinate(a[3],mc.player.z),a[1].contains("."),a[3].contains(".")));return;
                case "parkour":
                    if(a.length==2 && (a[1].equalsIgnoreCase("cw2cw") || a[1].equalsIgnoreCase("winged"))) {start(new WallWingNeoTask());return;}
                    if(a.length==2 && (a[1].equalsIgnoreCase("h2h") || a[1].equalsIgnoreCase("head-to-head"))) {start(new HeadNeoTask());return;}
                    if(a.length>=2 && a[1].equalsIgnoreCase("skills")) {
                        if(a.length==2 || a.length==3 && a[2].equalsIgnoreCase("list")) {say("@parkour skills map | @parkour skills <name> | @parkour skills run <name>. Moves: "+ParkourSkills.names());return;}
                        if(a.length==3 && a[2].equalsIgnoreCase("map")) {start(new ParkourSkillsTask(ParkourSkills.ALL.get(0),true,false));return;}
                        if(a.length==3) {start(new ParkourSkillsTask(ParkourSkills.find(a[2]),true,true));return;}
                        if(a.length==4 && a[2].equalsIgnoreCase("run")) {start(new ParkourSkillsTask(ParkourSkills.find(a[3]),false,true));return;}
                        throw new TaskFailure("Usage: @parkour skills [map|name|run name]");
                    }
                    if(a.length==2 && a[1].equalsIgnoreCase("map")) {start(new ParkourMapTask(true,false));return;}
                    if(a.length==2 && a[1].equalsIgnoreCase("marathon")) {start(new ParkourMapTask(true,true));return;}
                    if(a.length==2 && a[1].equalsIgnoreCase("runmap")) {start(new ParkourMapTask(false,true));return;}
                    if(a.length==2 && a[1].equalsIgnoreCase("challenge")) {start(new ParkourChallengeTask());return;}
                    if(a.length==2 && a[1].equalsIgnoreCase("practice")) {start(new LegacyParkourTask(null));return;}
                    if(a.length==2 && a[1].equalsIgnoreCase("triple")) {start(new TerrainParkourTask(null));return;}
                    exact(a,4,"@parkour x y z (destination feet position)");
                    if(a[1].contains(".") || a[2].contains(".") || a[3].contains("."))
                        start(new TerrainParkourTask(parkourCoordinate(a[1],mc.player.x),parkourCoordinate(a[2],mc.player.y),parkourCoordinate(a[3],mc.player.z),a[1].contains("."),a[3].contains(".")));
                    else start(new TerrainParkourTask(new BlockPos(coordinate(a[1],mc.player.x),coordinate(a[2],mc.player.y),coordinate(a[3],mc.player.z))));return;
                case "tick": LegacyTickControl.command(this,a);return;
                case "autodupe":
                    if(a.length!=2 || !(a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("off"))) throw new TaskFailure("Usage: @autodupe on/off");
                    if(a[1].equalsIgnoreCase("on")) LegacyPlayerState.requirePrivate(mc.getServer());
                    autoDupe=a[1].equalsIgnoreCase("on");say("Automatic resource duplication "+(autoDupe ? "on" : "off")+".");return;
                case "dupe": case "savestate": case "loadstate":
                    LegacyDupeTask.command(this,cmd,a); return;
                case "coords":
                    exact(a, 1, "@coords"); say(navigation.feet().toString()); return;
                case "dumpmold":
                    exact(a,1,"@dumpmold");
                    BlockPos center=new BlockPos(mc.player);java.util.List<BlockPos> mold=new java.util.ArrayList<>();
                    for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) for(int dy=-4;dy<=10;dy++) {
                        BlockPos p=center.add(dx,dy,dz);
                        String id=String.valueOf(Block.REGISTRY.getIdentifier(mc.world.getBlockState(p).getBlock()));
                        if(id.equals("minecraft:cobblestone")) mold.add(p);
                    }
                    mold.sort(java.util.Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
                    say("MOLD_DUMP center="+center+" blocks="+mold);return;
                case "inventory": case "inv":
                    exact(a, 1, "@inventory");
                    Map<String, Integer> counts = new TreeMap<>();
                    for (ItemStack s : mc.player.inventory.main) if (s != null)
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
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("nether")) throw new TaskFailure("Usage: @structurekit [nether]");
                    if(mc.getServer()==null || mc.player.dimension!=0) throw new TaskFailure("@structurekit requires a single-player Overworld; it scans real server-side chest contents and retries seeds.");
                    start(new LegacyStructureRun(a.length==2));return;
                case "fortress":
                    exact(a,1,"@fortress");start(new LegacyFortressTask());return;
                case "tower": case "swordtower":
                    if(a.length==2 && a[1].equalsIgnoreCase("base")) {start(new LegacyTowerBaseTask());return;}
                    if(a.length>2) throw new TaskFailure("Usage: @tower [base|pearls]");
                    start(new LegacySwordTowerTask(amount(a,1,12)));return;
                case "towerbase":
                    exact(a,1,"@towerbase");start(new LegacyTowerBaseTask());return;
                case "stronghold":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("seed") && !a[1].equalsIgnoreCase("scan") && !a[1].equalsIgnoreCase("loot")) throw new TaskFailure("Usage: @stronghold [seed|scan|loot]");
                    start(a.length==2 && a[1].equalsIgnoreCase("loot") ? new LegacyStrongholdLootTask()
                            : a.length==2 && a[1].equalsIgnoreCase("scan") ? new LegacyStrongholdSearchTask() : new LegacyStrongholdTravelTask()); return;
                case "templevillage": case "templeloot":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("temple") && !a[1].equalsIgnoreCase("cane")) throw new TaskFailure("Usage: @templevillage [temple|cane] or @templeloot");
                    if(mc.getServer()==null || mc.player.dimension!=0) throw new TaskFailure("@templevillage requires a single-player Overworld.");
                    start(a.length==2 && a[1].equalsIgnoreCase("cane") ? LegacyTempleVillageTask.sugarCanePhase()
                            : new LegacyTempleVillageTask(cmd.equals("templeloot") || a.length==2));return;
                case "dragon":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("practice")) throw new TaskFailure("Usage: @dragon [practice]");
                    start(new LegacyEndBattleTask(a.length==2));return;
                case "overworld": case "overwold":
                    if(a.length>2 || a.length==2 && !a[1].equalsIgnoreCase("prepare") && !a[1].equalsIgnoreCase("nether"))
                        throw new TaskFailure("Usage: @overworld [prepare|nether]");
                    start(new LegacyOverworldTask(a.length==1 || !a[1].equalsIgnoreCase("nether")));return;
                case "goto":
                    if (a.length != 3 && a.length != 4) throw new TaskFailure("Usage: @goto x z OR @goto x y z (supports ~relative coordinates)");
                    int x = coordinate(a[1], mc.player.x), z = coordinate(a[a.length - 1], mc.player.z);
                    Integer y = a.length == 4 ? coordinate(a[2], mc.player.y) : null;
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
    private static double parkourCoordinate(String token,double current) {
        double value=token.startsWith("~")?(token.contains(".")?current:Math.floor(current))+(token.length()==1?0:Double.parseDouble(token.substring(1))):Double.parseDouble(token);
        if(!Double.isFinite(value) || Math.abs(value)>29999984)throw new TaskFailure("Parkour coordinates must be finite and inside the world border.");return value;
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
        if (mc.player.inventory.getCursorStack() != null) throw new TaskFailure("Put down the item on your inventory cursor first.");
        stop(); observedServerTick=-1;serverAdvancedAt=System.nanoTime();waitingForServer=false;
        travelSprintJumps=0;
        tasCrafts=maxTasCraftTicks=0;
        tasPearlThrows=tasPearlLandings=tasGoldenApplesEaten=0;
        tasEatingFlightTicks=tasDupeCycles=maxTasDupeTicks=0;
        tasPearlDamageAvoided=0;
        waterEscape=!(next instanceof LegacyDupeTask) && !(next instanceof LegacyStrongholdSearchTask) && !(next instanceof LegacyStrongholdTravelTask) && WaterEscapeTask.wet(this) ? new WaterEscapeTask() : null; task = next; taskWorld = mc.world; paused = false; timer.reset(System.nanoTime()); timer.setRunning(System.nanoTime(),true); say("Started: " + next.describe());
    }
    void stop() {
        timer.setRunning(System.nanoTime(),false);
        if (task != null) { lastTask=task.describe(); task.stop(this); }
        nearbySupplies.reset();waterEscape=null; task = null; paused = false; releaseMovement(); cancelMining(); navigation.reset();
        recoverCursor(); closeOwnedScreen();
    }
    boolean busy() { return task != null; }
    boolean strongholdLoot() {return task instanceof LegacyStrongholdLootTask;}
    boolean protectsTowerBase(BlockPos pos) {
        return task instanceof LegacyTowerBaseTask && ((LegacyTowerBaseTask)task).protects(pos);
    }
    boolean structurePaused() {return paused || mc.world!=null && mc.isPaused();}
    void bindStructureWorld() {taskWorld=mc.world;navigation.reset();nearbySupplies.reset();waterEscape=WaterEscapeTask.wet(this) ? new WaterEscapeTask() : null;}
    void tick() {tickInternal(false);}
    void tickBeforeSimulation() {tickInternal(true);}
    private void tickInternal(boolean beforeSimulation) {
        if(beforeSimulation!=(task instanceof WallWingNeoTask || task instanceof HeadNeoTask || task instanceof ParkourSkillsTask || task instanceof ParkourMapTask || task instanceof ParkourChallengeTask || task instanceof LegacyParkourTask || task instanceof TerrainParkourTask || task instanceof SmoothParkourTask || task instanceof TasTask)) return;
        if(task!=null && !LegacyTickControl.clientAdvanced() && !(task instanceof LegacyDupeTask) && !(task instanceof WallWingNeoTask) && !(task instanceof HeadNeoTask) && !(task instanceof ParkourSkillsTask) && !(task instanceof ParkourMapTask) && !(task instanceof ParkourChallengeTask) && !(task instanceof LegacyParkourTask) && !(task instanceof TerrainParkourTask) && !(task instanceof SmoothParkourTask) && !(task instanceof TasTask)) {
            timer.setRunning(System.nanoTime(),false);return;
        }
        ticks++;
        if (task == null) return;
        try {
            if(task instanceof LegacyStructureRun && ((LegacyStructureRun)task).transition(this)) return;
        } catch(RuntimeException failure) {
            stop();say("Could not create the next structure world: "+failure.getMessage());return;
        }
        if(mc.player!=null && mc.world!=null && mc.world!=taskWorld && (task instanceof LegacyOverworldTask
                || task instanceof LegacyStructureRun && ((LegacyStructureRun)task).enteringNether())
                && mc.player.dimension==-1) taskWorld=mc.world;
        if(mc.player!=null && mc.world!=null && mc.world!=taskWorld && task instanceof LegacyFortressTask
                && ((LegacyFortressTask)task).returningToOverworld() && mc.player.dimension==0) {
            taskWorld=mc.world;navigation.reset();nearbySupplies.reset();
        }
        if(task instanceof TasTask && ((TasTask)task).transitioning()) {
            if(mc.player==null || mc.world==null) {ticks--;releaseMovement();return;}
            if(((TasTask)task).allowsWorld(mc.player.dimension) && mc.world!=taskWorld) {taskWorld=mc.world;navigation.reset();nearbySupplies.reset();waterEscape=null;}
        }
        if (mc.player == null || mc.world != taskWorld || !mc.player.isAlive() && !(task instanceof TasTask && ((TasTask)task).handlesDeath())) { stop(); return; }
        if (expectedScreen != null && mc.player.openScreenHandler != mc.player.playerScreenHandler) {
            if (!expectedScreen.isInstance(mc.player.openScreenHandler)) {
                stop(); say("Unexpected container opened; task stopped."); return;
            }
            ownedScreen = mc.player.openScreenHandler; expectedScreen = null;
        }
        timer.setRunning(System.nanoTime(), !suspended());
        if (suspended()) {
            if(task instanceof TasTask) ticks--;
            releaseMovement(); return;
        }
        if(mc.getServer()!=null && LegacyTickControl.active()) {
            // A deliberate rehearsal freeze is not server lag. Start the lag
            // deadline afresh when playback returns to the normal clock.
            observedServerTick=mc.getServer().getTicks();serverAdvancedAt=System.nanoTime();waitingForServer=false;
        }
        if(mc.getServer()!=null && !LegacyTickControl.active()) {
            int serverTick=mc.getServer().getTicks();long now=System.nanoTime();
            if(serverTick!=observedServerTick) {observedServerTick=serverTick;serverAdvancedAt=now;waitingForServer=false;}
            if(now-serverAdvancedAt>1000000000L && !task.canTickDuringServerLag()) {
                waitingForServer=true;
                // Keep action deadlines in active simulation time. Otherwise a
                // frozen server makes fresh drops and valid paths time out.
                ticks--;releaseMovement();cancelMining();return;
            }
        }
        long workStarted=System.nanoTime();
        boolean plannedTick=false;
        try {
            // Recognize newly opened owned screens before checking readiness.
            // Navigation deadlines advance only with planned simulation ticks.
            if(task instanceof TasTask) {
                if(!((TasTask)task).ready(this)) {ticks--;return;}
                plannedTick=true;
            }
            if(ticks%1000==0 && Boolean.getBoolean("altoclef.legacy.smoke"))
                LogManager.getLogger("Botas/Legacy").info("Bot work timing: max {} ms, {} ticks above 50 ms",maxWorkNanos/1000000L,slowWorkTicks);
            if (ticks % 100 == 0) LogManager.getLogger("Botas/Legacy").info("Task status: {} at {} (screen {}, grounded {})",
                    task.describe(), navigation.feet(), mc.player.openScreenHandler.getClass().getSimpleName(),mc.player.onGround);
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
            if(plannedTick && task instanceof TasTask) ((TasTask)task).advance();
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
        if (mc.player != null && (enabled || sprinting)) mc.player.setSprinting(enabled);
        sprinting = enabled;
    }
    void releaseMovement() {
        sprint(false);
        for (KeyBinding key : pressed) KeyBinding.setKeyPressed(key.getCode(), false);
        pressed.clear();
    }
    int count(LegacyItems.Target target) {
        int result = 0;
        for (ItemStack s : mc.player.inventory.main) if (target.matches(s)) result += s.count;
        return result;
    }
    int findInventory(LegacyItems.Target target) {
        for (int i = 0; i < 36; i++) if (target.matches(mc.player.inventory.main[i])) return i;
        return -1;
    }
    int screenSlot(int inventoryIndex) {
        ScreenHandler h = mc.player.openScreenHandler;
        for (int i = 0; i < h.slots.size(); i++) if (h.getSlot(i).equals(mc.player.inventory, inventoryIndex)) return i;
        throw new TaskFailure("Player inventory is unavailable in this screen.");
    }
    void click(int slot, int button, int mode) {
        mc.interactionManager.clickSlot(mc.player.openScreenHandler.syncId, slot, button, mode, mc.player);
    }
    void equip(int index) {
        if (index < 0) throw new TaskFailure("Required item is missing.");
        if (index < 9) mc.player.inventory.selectedSlot = index;
        else click(screenSlot(index), mc.player.inventory.selectedSlot, 2);
    }
    void recoverCursor() {
        if (mc.player == null || mc.player.inventory.getCursorStack() == null) return;
        // Never drop a carried stack to recover automation state.
        for (int i = 0; i < 36 && mc.player.inventory.getCursorStack() != null; i++) {
            ItemStack cursor = mc.player.inventory.getCursorStack(), stack = mc.player.inventory.main[i];
            if (stack == null || (stack.getItem() == cursor.getItem() && stack.getData() == cursor.getData()
                    && Objects.equals(stack.getNbt(), cursor.getNbt()) && stack.count < stack.getMaxCount())) click(screenSlot(i), 0, 0);
        }
    }
    void closeOwnedScreen() {
        if(mc.player!=null && ownedScreen instanceof net.minecraft.screen.VillagerScreenHandler
                && mc.player.openScreenHandler==ownedScreen) {
            // Merchant inputs are returned before closing, just like crafting ingredients.
            for(int i=0;i<2;i++) if(ownedScreen.getSlot(i).getStack()!=null) {
                click(i,0,1);
                if(ownedScreen.getSlot(i).getStack()!=null) return;
            }
        }
        if (mc.player != null && ownedScreen instanceof CraftingScreenHandler && mc.player.openScreenHandler == ownedScreen) {
            // Closing a full crafting grid can drop ingredients if the inventory is full.
            for (int i = 1; i <= 9; i++) if (ownedScreen.getSlot(i).getStack() != null) {
                click(i, 0, 1);
                if (ownedScreen.getSlot(i).getStack() != null) return;
            }
        }
        if (mc.player != null && ownedScreen != null && mc.player.openScreenHandler == ownedScreen && mc.player.inventory.getCursorStack() == null) mc.player.closeHandledScreen();
        ownedScreen = null;
        expectedScreen = null;
    }
    void look(double x, double y, double z) {
        lookExact(x,y,z);
        if(!(task instanceof LegacyEndBattleTask) || mc.player.dimension!=1) return;
        double eyeX=mc.player.x,eyeY=mc.player.y+mc.player.getEyeHeight(),eyeZ=mc.player.z;
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
                mc.player.pitch=70;
                return;
            }
        }
    }
    void lookExact(double x, double y, double z) {
        double dx = x - mc.player.x, dz = z - mc.player.z, dy = y - mc.player.y - mc.player.getEyeHeight();
        mc.player.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
    BlockHitResult hit(BlockPos pos) {
        return hitFrom(new Vec3d(mc.player.x, mc.player.y + mc.player.getEyeHeight(), mc.player.z), pos);
    }
    BlockHitResult hitFrom(Vec3d eye, BlockPos pos) {
        double reach = mc.interactionManager.getReachDistance() - .1;
        // On continuous floors and walls, a center ray can hit the neighbor first.
        // Test each face as well, using the actual ray result for the mining packet.
        for (int face = -1; face < Direction.values().length; face++) {
            Vec3d aim = new Vec3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            if (face >= 0) {
                BlockPos adjacent = pos.offset(Direction.values()[face]);
                aim = new Vec3d(aim.x + .499 * (adjacent.getX() - pos.getX()),
                        aim.y + .499 * (adjacent.getY() - pos.getY()), aim.z + .499 * (adjacent.getZ() - pos.getZ()));
            }
            double dx = eye.x - aim.x, dy = eye.y - aim.y, dz = eye.z - aim.z;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            BlockHitResult hit = mc.world.rayTrace(eye, aim);
            if (hit != null && pos.equals(hit.getBlockPos())) return hit;
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
        BlockPos center = new BlockPos(mc.player);
        BlockPos best = null; double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++)
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++)
                for (int y = Math.max(1, center.getY() - 8); y <= Math.min(254, center.getY() + 10); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    double d = mc.player.squaredDistanceToCenter(p);
                    if (d < bestDistance && !rejected.contains(p) && mc.world.isLoaded(p, false) && matches.test(mc.world.getBlockState(p)) && usable.test(p)) { best = p; bestDistance = d; }
                }
        return best;
    }
    boolean interactiveSupport(BlockPos pos) {
        String id=String.valueOf(Block.REGISTRY.getIdentifier(mc.world.getBlockState(pos).getBlock()));
        return mc.world.getBlockEntity(pos)!=null || id.equals("minecraft:crafting_table")
                || id.contains("door") || id.contains("button") || id.equals("minecraft:lever");
    }
    boolean mine(BlockPos pos) {
        return mine(pos,-1);
    }
    boolean mine(BlockPos pos,int preferredSlot) {
        if (mc.world.isAir(pos)) { cancelMining(); return true; }
        // Seeing a face during a jump is not a completed approach. Mining would
        // release movement, fall out of reach, cancel progress and jump again.
        if(!mc.player.onGround) {cancelMining();navigation.approach(pos);return false;}
        BlockHitResult hit = hit(pos);
        if (hit == null) { cancelMining(); navigation.approach(pos); return false; }
        releaseMovement(); look(hit.pos.x, hit.pos.y, hit.pos.z);
        Block block = mc.world.getBlockState(pos).getBlock();
        int tool=preferredSlot>=0?preferredSlot:miningTool(block);
        if(tool<0) throw new TaskFailure("No suitable usable tool for "+Block.REGISTRY.getIdentifier(block));
        equip(tool);
        if (!pos.equals(mining)) { cancelMining(); mining = pos; mc.interactionManager.attackBlock(pos, hit.direction); }
        else mc.interactionManager.updateBlockBreakingProgress(pos, hit.direction);
        mc.player.swingHand();
        if (++miningTicks > 800) throw new TaskFailure("Block could not be mined (protection, tool or reach).");
        return false;
    }
    int miningTool(Block block) {
        net.minecraft.block.material.Material material = block.getMaterial();
        boolean needsTool = material == net.minecraft.block.material.Material.STONE
                || material == net.minecraft.block.material.Material.IRON || material == net.minecraft.block.material.Material.ANVIL;
        int tool = -1; float speed = 1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.inventory.main[i];
            if (s != null && (!needsTool || s.isEffectiveOn(block)) && s.getMiningSpeedMultiplier(block) > speed
                    && (!s.isDamageable() || s.getDamage() < s.getMaxDamage() - 1)) {
                tool = i; speed = s.getMiningSpeedMultiplier(block);
            }
        }
        if(tool>=0 || needsTool) return tool;
        // No shovel/axe/etc. available: use an empty hand or a non-tool item,
        // rather than wearing out whichever pickaxe or sword was held last.
        for(int i=0;i<9;i++) if(mc.player.inventory.main[i]==null) return i;
        for(int i=0;i<36;i++) {
            ItemStack stack=mc.player.inventory.main[i];
            if(stack!=null && !stack.isDamageable()) return i;
        }
        return -1;
    }
    void cancelMining() {
        if (mining != null && mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        mining = null; miningTicks = 0;
    }
    boolean interact(BlockPos pos) {
        BlockHitResult hit = hit(pos);
        if (hit == null) { navigation.approach(pos); return false; }
        releaseMovement(); look(hit.pos.x, hit.pos.y, hit.pos.z);
        mc.interactionManager.onRightClick(mc.player, mc.world, mc.player.inventory.getMainHandStack(), pos, hit.direction, hit.pos);
        return true;
    }
}
