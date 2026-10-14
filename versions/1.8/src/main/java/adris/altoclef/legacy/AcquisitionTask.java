package adris.altoclef.legacy;

import net.minecraft.block.*;
import net.minecraft.entity.*;
import net.minecraft.entity.passive.*;
import net.minecraft.item.*;
import net.minecraft.util.math.*;
import java.util.*;

/** Version-specific resource planning using real 1.8 recipes and normal player actions. */
final class AcquisitionTask implements LegacyTask {
    final LegacyItems.Target target;
    final int count;
    int protectedCobblestone;
    private final String mode;
    private final Set<String> ancestors;
    private LegacyTask child;
    private BlockPos block;
    private int attempts;
    private int lastCount = -1, noProgress;
    private final Set<BlockPos> rejected = new HashSet<>();
    private final Set<BlockPos> checkedChests = new HashSet<>();
    private int nextChestScan;
    private final Map<UUID,Integer> rejectedItems = new HashMap<>();
    private ShallowItemPickupTask waterPickup;
    private UUID pickupId;
    private PathSearch.Pos pickupGoal;
    private int pickupStarted, pickupArrivedTicks;
    private final Set<PathSearch.Pos> rejectedPickupGoals=new HashSet<>();
    private ResourceExploration exploration;
    private boolean exploring;
    private int nextResourceScan, nextStoneAccess, nextLocalScan;
    private int exploreUntil;
    private ResourceScan oreScan;
    private LoadedResourceScan distantScan;
    private int savedCaneViewDistance=-1,caneScanStarted,nextCaneBoost;
    private int caneScanDistance=16,caneScanServerTick;
    private java.util.concurrent.Future<Boolean> caneDistanceSettled;
    private int restoredCaneViewDistance;
    private final Set<BlockPos> rejectedOre = new HashSet<>();
    private int nextOrePlan;
    private final Set<BlockPos> gravelSpaces=new HashSet<>();
    private final Map<UUID,Integer> rejectedAnimals=new HashMap<>();
    private UUID huntedAnimal;
    private LivingEntity huntedEntity;
    private final Map<UUID,Integer> animalFailures=new HashMap<>();
    private int huntStarted,nextHuntExit;
    private double huntBestDistance;
    private PathSearch.Pos animalApproach;
    private BlockPos animalPosition;
    private List<PathSearch.Pos> animalCandidates;
    private int animalCandidateIndex;
    private boolean diggingAnimalApproaches;
    private boolean denseStonePlanned;

    AcquisitionTask(LegacyItems.Target target, int count, String mode) {
        this(target, count, mode, new HashSet<String>());
    }
    AcquisitionTask(LegacyItems.Target target, int count, String mode, Set<String> ancestors) {
        this.target = target; this.count = count; this.mode = mode; this.ancestors = new HashSet<>(ancestors);
        String key = LegacyItems.id(target.item);
        if (!this.ancestors.add(key)) throw new TaskFailure("Recipe dependency cycle for " + target + ".");
        if (this.ancestors.size() > 16) throw new TaskFailure("Recipe is too deeply nested.");
    }
    LegacyTask obtain(LegacyItems.Target ingredient, int amount) {
        AcquisitionTask task=new AcquisitionTask(ingredient, amount, "get", ancestors);
        task.protectedCobblestone=protectedCobblestone;
        return task;
    }
    public String describe() { return mode + " " + target + " x" + count + (LegacyItems.id(target.item).equals("reeds") ? " (have "+lastCount+")" : "") + (!exploring ? "" : " ("+exploration.describe()+")")
            + (block==null ? "" : " at "+block.getX()+" "+block.getY()+" "+block.getZ())
            + (caneDistanceSettled!=null ? " (waiting for server render distance)"
                : savedCaneViewDistance>=0 ? " (searching at "+caneScanDistance+" chunks)" : "")
            + (huntedEntity==null ? "" : " -> approach "+huntedEntity.getClass().getSimpleName()+" at "+(int)huntedEntity.x+" "+(int)huntedEntity.y+" "+(int)huntedEntity.z)
            + (waterPickup==null ? "" : " -> "+waterPickup.describe())
            + (child == null ? "" : " -> " + child.describe()); }
    public void stop(LegacyBot bot) { if (child != null) child.stop(bot);if(waterPickup!=null) waterPickup.stop(bot);restoreCaneViewDistance(bot); }
    public boolean canTickDuringServerLag() {return savedCaneViewDistance>=0 || caneDistanceSettled!=null;}
    private void restoreCaneViewDistance(LegacyBot bot) {
        if(savedCaneViewDistance<0) return;
        restoredCaneViewDistance=savedCaneViewDistance;
        bot.mc.options.viewDistance=savedCaneViewDistance;savedCaneViewDistance=-1;
        nextCaneBoost=bot.ticks+600;
        if(bot.mc.getServer()!=null) waitForCaneViewDistance(bot);
    }

    private void waitForCaneViewDistance(LegacyBot bot) {
        caneDistanceSettled=bot.mc.getServer().method_10815((java.util.concurrent.Callable<Boolean>)()
                -> bot.mc.getServer().getPlayerManager().getViewDistance()==restoredCaneViewDistance);
    }

    // Do not abandon crafting-grid or furnace transfers midway through a click.
    private boolean canYieldToDrop() {
        return child == null || child instanceof StoneAccessTask || child instanceof StructureTravelTask
                || child instanceof AcquisitionTask && ((AcquisitionTask) child).canYieldToDrop();
    }

    public boolean tick(LegacyBot bot) {
        // Integrated-server chunk generation can outlast the client scan.
        // Wait for the server to catch up before placing/mining or travelling.
        if(caneDistanceSettled!=null) {
            if(!caneDistanceSettled.isDone()) {bot.cancelMining();bot.releaseMovement();return false;}
            try {
                if(!caneDistanceSettled.get()) {
                    waitForCaneViewDistance(bot);bot.cancelMining();bot.releaseMovement();return false;
                }
            } catch(Exception failure) {throw new TaskFailure("Could not confirm render distance after cane scan.");}
            caneDistanceSettled=null;nextCaneBoost=bot.ticks+600;
            rejectedItems.clear();pickupId=null;pickupGoal=null;
        }
        if(waterPickup!=null) {
            try {if(waterPickup.tick(bot)) {waterPickup.stop(bot);waterPickup=null;bot.navigation.reset();}}
            catch(TaskFailure failed) {waterPickup.stop(bot);waterPickup=null;bot.navigation.reset();}
            return false;
        }
        if(savedCaneViewDistance>=0) {bot.cancelMining();bot.releaseMovement();return gather(bot);}
        int have = bot.count(target);
        int wanted=count+(bot.autoDupeAvailable() && reserveDupeSeed(target) ? 1 : 0);
        noProgress = have == lastCount ? noProgress + 1 : 0; lastCount = have;
        if (noProgress > 12000) throw new TaskFailure("No acquisition progress for " + target + ".");
        if (mode.equals("get") && canYieldToDrop()) {
            if (have >= wanted) {
                if (child != null) { child.stop(bot); child = null; }
                bot.releaseMovement(); bot.cancelMining(); return true;
            }
            // Check even with a selected block or a resource dependency underway.
            // A dropped finished product can make the entire dependency unnecessary.
            if (pickup(bot)) {
                if (child != null) { child.stop(bot); child = null; }
                block = null;
                if (exploration != null) exploration.suspend();
                exploring = false; exploreUntil = 0;
                bot.cancelMining();
                return false;
            }
            if(have>0 && have<wanted && bot.autoDupeAvailable()) {
                if(child!=null) child.stop(bot);
                bot.cancelMining();bot.releaseMovement();block=null;
                if(exploration!=null) exploration.suspend();exploring=false;
                child=new ResourceDupeTask(target,wanted);
            }
        }
        // Access tunnels can consume the pickaxe after gather() has checked it.
        // Recheck before advancing a mining child, but let replacement acquisition
        // finish. Crafting may move us, so discard the old route and replan.
        String required = requiredPickaxe(LegacyItems.id(target.item));
        if (child != null && !(child instanceof AcquisitionTask) && !(child instanceof ResourceDupeTask) && mode.equals("get")
                && required != null && !hasPickaxe(bot, required)) {
            child.stop(bot);
            bot.cancelMining(); bot.releaseMovement(); bot.navigation.reset();
            block = null;
            child = obtain(LegacyItems.usableTool(required), 1);
            bot.say("Replacing worn " + required + " before continuing " + target + ".");
        }
        if (child != null) {
            try {if (child.tick(bot)) { child.stop(bot); child = null; bot.navigation.reset(); }}
            catch(TaskFailure blocked) {
                if(child instanceof InventoryTasks.Withdraw) {
                    // Checking a nearby chest is optional. A locked-in approach
                    // or a server-delayed open must not cancel resource gathering.
                    child.stop(bot);child=null;bot.navigation.reset();return false;
                }
                if(!(child instanceof StructureTravelTask) || !LegacyItems.id(target.item).equals("reeds")) throw blocked;
                bot.say("Sugar cane travel failed: "+blocked.getMessage());
                child.stop(bot);child=null;
                if(block!=null) rejected.add(block);
                block=null;distantScan=null;bot.navigation.reset();
            }
            return false;
        }
        if (have >= count) { bot.releaseMovement(); bot.cancelMining(); return true; }
        if (mode.equals("get") && savedCaneViewDistance<0 && bot.ticks>=nextChestScan) {
            nextChestScan=bot.ticks+80;
            BlockPos chest=nearbyUnvisitedChest(bot);
            if(chest!=null) {
                checkedChests.add(chest);
                child=new InventoryTasks.Withdraw(target,count,chest);
                return false;
            }
        }
        if (mode.equals("get") && LegacyItems.id(target.item).equals("emerald")) {
            child=new VillagerTradeTask(target,count,this,true);return false;
        }

        if (mode.equals("get") && natural(target)) return gather(bot);

        LegacyItems.Target input = LegacyItems.smeltingInput(target);
        if (!mode.equals("craft") && input != null) {
            int needed = count - have;
            if (bot.count(input) < needed) { child = obtain(input, needed); return false; }
            child = new WorkstationTasks.Smelt(target, count, input, this); return false;
        }
        if (mode.equals("smelt")) throw new TaskFailure("No 1.8 furnace recipe for " + target + ".");

        // Recipes accepting any planks must not force oak logs when other species exist.
        // Gather any wood first, then select the recipe matching the actual inventory.
        if (LegacyItems.id(target.item).equals("planks") && target.data < 0 && bot.count(LegacyItems.anyLogs()) == 0) {
            child = obtain(LegacyItems.anyLogs(), 1); return false;
        }

        LegacyItems.Recipe best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (LegacyItems.Recipe recipe : LegacyItems.recipes(target)) {
            Map<LegacyItems.Target, Integer> requirements = requirements(recipe);
            double cost = recipe.needsTable() ? 2 : 0;
            boolean cycle = false;
            for (Map.Entry<LegacyItems.Target, Integer> entry : requirements.entrySet()) {
                int missing = Math.max(0, entry.getValue() - bot.count(entry.getKey()));
                if (missing > 0 && ancestors.contains(LegacyItems.id(entry.getKey().item))) { cycle = true; break; }
                cost += missing * (natural(entry.getKey()) ? 1 : 8);
            }
            if (!cycle && cost < bestCost) { bestCost = cost; best = recipe; }
        }
        if (best == null) throw new TaskFailure("No automatic acquisition route for " + target + ". Nearby dropped items can still be collected.");
        for (Map.Entry<LegacyItems.Target, Integer> entry : requirements(best).entrySet()) {
            if (bot.count(entry.getKey()) < entry.getValue()+(bot.autoDupeAvailable() && reserveDupeSeed(entry.getKey()) ? 1 : 0)) {
                child = obtain(entry.getKey(), entry.getValue()); return false;
            }
        }
        child = new WorkstationTasks.Craft(best, this); return false;
    }

    static Map<LegacyItems.Target, Integer> requirements(LegacyItems.Recipe recipe) {
        Map<String, LegacyItems.Target> targets = new LinkedHashMap<>();
        Map<LegacyItems.Target, Integer> counts = new LinkedHashMap<>();
        for (LegacyItems.Target input : recipe.ingredients) if (input != null) {
            LegacyItems.Target key = targets.computeIfAbsent(input.key(), ignored -> input);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    static boolean reserveDupeSeed(LegacyItems.Target target) {
        String id=LegacyItems.id(target.item);
        return id.equals("log") || id.equals("log2") || id.equals("cobblestone")
                || id.equals("iron_ore") || id.equals("coal") || id.equals("flint");
    }

    boolean pickup(LegacyBot bot) {
        return pickup(bot,item->true);
    }
    boolean pickup(LegacyBot bot,java.util.function.Predicate<ItemEntity> allowed) {
        ItemEntity nearest = null;
        double distance = 24 * 24;
        for (Object entry : bot.mc.world.loadedEntities) if (entry instanceof ItemEntity) {
            ItemEntity item = (ItemEntity) entry;
            double d = bot.mc.player.squaredDistanceTo(item);
            if (item.removed || !allowed.test(item) || rejectedItems.getOrDefault(item.getUuid(),0)>bot.ticks || !target.matches(item.getItemStack()) || d>=24*24) continue;
            // Start approaching fresh drops during their short vanilla pickup delay,
            // and keep following the same entity instead of oscillating between drops.
            if(item.getUuid().equals(pickupId)) {nearest=item;break;}
            if(d<distance) {nearest=item;distance=d;}
        }
        if (nearest == null) { pickupId=null; pickupGoal=null; return false; }
        if (!nearest.getUuid().equals(pickupId)) {
            pickupId=nearest.getUuid(); pickupGoal=null; pickupStarted=bot.ticks;
            pickupArrivedTicks=0;rejectedPickupGoals.clear();
            bot.navigation.reset();
        }
        // A drop behind a ledge must not monopolize the acquisition task forever.
        if (bot.ticks-pickupStarted>160) {
            rejectedItems.put(pickupId,bot.ticks+100); pickupId=null; pickupGoal=null;
            bot.navigation.reset(); bot.releaseMovement(); return false;
        }
        if (bot.mc.player.inventory.getEmptySlot() < 0) {
            boolean space = false;
            for (ItemStack stack : bot.mc.player.inventory.main) if (target.matches(stack) && stack.count < stack.getMaxCount()) space = true;
            if (!space) throw new TaskFailure("Inventory is full.");
        }
        bot.closeOwnedScreen();
        int x = (int)Math.floor(nearest.x), y = (int)Math.floor(nearest.y), z = (int)Math.floor(nearest.z);
        if(pickupGoal!=null && (nearest.y<pickupGoal.y-.25 || nearest.y>pickupGoal.y+2.05
                || Math.abs(pickupGoal.x+.5-nearest.x)>1.25 || Math.abs(pickupGoal.z+.5-nearest.z)>1.25)) {
            pickupGoal=null;pickupArrivedTicks=0;rejectedPickupGoals.clear();
        }
        // Drops may still be falling after a block breaks. Navigate to supported ground,
        // not the transient airborne item coordinate.
        if (pickupGoal==null) {
            if (!bot.mc.player.onGround) return false;
            PathSearch.Pos from=PathSearch.groundedStart(bot.navigation,bot.mc.player.x,bot.navigation.feet().y,bot.mc.player.z);
            List<PathSearch.Pos> candidates=new ArrayList<>();
            for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) for (int dy=0;dy>=-4;dy--) {
                PathSearch.Pos p=new PathSearch.Pos(x+dx,y+dy,z+dz);
                // Pickup requires vertical overlap as well as horizontal proximity.
                if (nearest.y<p.y-.25 || nearest.y>p.y+2.05
                        || Math.abs(p.x+.5-nearest.x)>1.25 || Math.abs(p.z+.5-nearest.z)>1.25) continue;
                if (!rejectedPickupGoals.contains(p) && bot.navigation.floor(p.x,p.y-1,p.z)) candidates.add(p);
            }
            final ItemEntity drop=nearest;
            candidates.sort(Comparator.comparingDouble(p ->
                    4*(Math.abs(p.x+.5-drop.x)+Math.abs(p.z+.5-drop.z))+from.distance(p)));
            for (PathSearch.Pos p:candidates) {
                List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,p,2000);
                if (from.equals(p) && PathSearch.standing(bot.navigation,p.x,p.y,p.z)
                        || !route.isEmpty() && route.get(route.size()-1).equals(p)) {pickupGoal=p;break;}
            }
            // A mined drop can slide into a one-block-high recess. Reuse the
            // bounded safe-excavation planner to clear its headroom when walking
            // alone cannot reach any pickup position.
            if(pickupGoal==null) for(PathSearch.Pos p:candidates) {
                List<PathSearch.Pos> route=bot.navigation.approachRoute(from,p,1200);
                if(!route.isEmpty() && route.get(route.size()-1).equals(p)) {pickupGoal=p;break;}
            }
            if(pickupGoal==null) {
                waterPickup=ShallowItemPickupTask.plan(bot,nearest);
                if(waterPickup!=null) return true;
                rejectedItems.put(pickupId,bot.ticks+100);pickupId=null;return false;
            }
        }
        try {
            if(bot.navigation.move(pickupGoal, .15)) {
                if(++pickupArrivedTicks>6 && !nearest.cannotPickup()) {
                    rejectedPickupGoals.add(pickupGoal);pickupGoal=null;pickupArrivedTicks=0;
                }
            } else pickupArrivedTicks=0;
        }
        catch (TaskFailure blocked) { rejectedItems.put(nearest.getUuid(),bot.ticks+100); pickupId=null;pickupGoal=null;bot.navigation.reset(); return false; }
        return true;
    }

    static boolean natural(LegacyItems.Target target) {
        return sources(target).length > 0 || mob(target) != null;
    }
    private BlockPos nearbyUnvisitedChest(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        BlockPos best=null;double score=Double.POSITIVE_INFINITY;
        for(int x=feet.x-6;x<=feet.x+6;x++) for(int y=feet.y-3;y<=feet.y+3;y++)
            for(int z=feet.z-6;z<=feet.z+6;z++) {
                BlockPos p=new BlockPos(x,y,z);
                if(checkedChests.contains(p)) continue;
                Block block=bot.mc.world.getBlockState(p).getBlock();
                if(block!=Block.get("chest") && block!=Block.get("trapped_chest")) continue;
                double d=bot.mc.player.squaredDistanceToCenter(p);
                if(d<score) {score=d;best=p;}
            }
        return best;
    }
    static String[] sources(LegacyItems.Target t) {
        if(t.leafFamily) return new String[]{"leaves","leaves2"};
        if (t.woodFamily) return new String[]{"log", "log2"};
        String id = LegacyItems.id(t.item);
        switch (id) {
            case "leaves": return new String[]{"leaves"};
            case "leaves2": return new String[]{"leaves2"};
            case "log": return new String[]{"log"};
            case "log2": return new String[]{"log2"};
            case "dirt": return new String[]{"dirt", "grass"};
            case "cobblestone": return new String[]{"stone", "cobblestone"};
            case "coal": return t.data == 1 ? new String[0] : new String[]{"coal_ore"};
            case "diamond": return new String[]{"diamond_ore"};
            case "emerald": return new String[]{"emerald_ore"};
            case "redstone": return new String[]{"redstone_ore", "lit_redstone_ore"};
            case "quartz": return new String[]{"quartz_ore"};
            case "clay_ball": return new String[]{"clay"};
            case "flint": return new String[]{"gravel"};
            case "reeds": return new String[]{"reeds"};
            case "melon": return new String[]{"melon_block"};
            case "wheat": case "carrot": case "potato": return new String[]{id.equals("wheat") ? "wheat" : id + "s"};
            case "dye": return t.data == 4 ? new String[]{"lapis_ore"} : new String[0];
            case "sand": case "gravel": case "iron_ore": case "gold_ore": case "netherrack":
            case "soul_sand": case "end_stone": case "obsidian": case "pumpkin": return new String[]{id};
            default: return new String[0];
        }
    }
    private static Class<? extends LivingEntity> mob(LegacyItems.Target target) {
        switch (LegacyItems.id(target.item)) {
            case "beef": case "leather": return CowEntity.class;
            case "porkchop": return PigEntity.class;
            case "chicken": case "feather": return ChickenEntity.class;
            case "mutton": case "wool": return SheepEntity.class;
            case "rabbit": case "rabbit_hide": return RabbitEntity.class;
            default: return null;
        }
    }
    private boolean gather(LegacyBot bot) {
        bot.closeOwnedScreen();
        // After repeated unreachable sources, actually travel to a new scan area.
        // Previously the next distant log cancelled exploration one tick later.
        if(bot.ticks<exploreUntil) {explore(bot);return false;}
        Class<? extends LivingEntity> animal = mob(target);
        if (animal != null) {
            // A kill is real progress: give the rest of this herd a fresh approach.
            if(huntedEntity!=null && !huntedEntity.isAlive()) {
                rejectedAnimals.clear();animalFailures.clear();huntedEntity=null;huntedAnimal=null;
            }
            LivingEntity nearest = null; double distance = Double.POSITIVE_INFINITY,nearestScore=Double.POSITIVE_INFINITY;
            // Finish one approach instead of bouncing between herds as their
            // straight-line distance changes while we walk around an obstacle.
            if(huntedEntity!=null && huntedEntity.isAlive() && !huntedEntity.isBaby()
                    && rejectedAnimals.getOrDefault(huntedEntity.getUuid(),0)<=bot.ticks) {
                nearest=huntedEntity;distance=bot.mc.player.squaredDistanceTo(nearest);
            }
            if(nearest==null)
            for (Object e : bot.mc.world.loadedEntities) if (animal.isInstance(e) || target.meatFamily==1 && (e instanceof PigEntity || e instanceof SheepEntity)) {
                LivingEntity mob = (LivingEntity) e;
                double d = bot.mc.player.squaredDistanceTo(mob);
                double score=mob.getUuid().equals(huntedAnimal) ? d*.7 : d;
                if (!mob.isAlive() || mob.isBaby() || rejectedAnimals.getOrDefault(mob.getUuid(),0)>bot.ticks) continue;
                if(score < nearestScore) { nearest = mob; distance = d; nearestScore=score; }
            }
            if (nearest == null) { explore(bot); return false; }
            stopExploring(bot);
            if(!nearest.getUuid().equals(huntedAnimal)) {
                huntedEntity=nearest;huntedAnimal=nearest.getUuid();huntStarted=bot.ticks;huntBestDistance=distance;animalApproach=null;animalPosition=null;bot.navigation.reset();
            }
            if(distance<huntBestDistance-1) {huntBestDistance=distance;huntStarted=bot.ticks;}
            if(bot.ticks-huntStarted>200) {rejectAnimal(bot,nearest);return false;}
            if (distance > 7) {
                PathSearch.Pos destination=new PathSearch.Pos((int)Math.floor(nearest.x), (int)Math.floor(nearest.y), (int)Math.floor(nearest.z));
                try {
                    if(distance<144) approachAnimal(bot,nearest);
                    else bot.navigation.move(destination,1.4);
                }
                catch(TaskFailure blocked) {rejectAnimal(bot,nearest);}
            } else {
                bot.releaseMovement();
                int weapon=combatWeapon(bot);
                if(weapon>=0) bot.equip(weapon);
                bot.look(nearest.x, nearest.y + nearest.getEyeHeight(), nearest.z);
                Vec3d eye = new Vec3d(bot.mc.player.x, bot.mc.player.y + bot.mc.player.getEyeHeight(), bot.mc.player.z);
                if (bot.mc.world.rayTrace(eye, new Vec3d(nearest.x, nearest.y + nearest.getEyeHeight(), nearest.z),false,true,false) != null) {
                    approachAnimal(bot,nearest);return false;
                }
                if (bot.ticks % 10 == 0) { bot.mc.interactionManager.attackEntity(bot.mc.player, nearest); bot.mc.player.swingHand();huntStarted=bot.ticks; }
            }
            return false;
        }
        String id = LegacyItems.id(target.item);
        if((id.equals("leaves") || id.equals("leaves2")) && bot.findInventory(LegacyItems.usableTool("shears"))<0) {
            child=obtain(LegacyItems.usableTool("shears"),1);return false;
        }
        String required = requiredPickaxe(id);
        if (required != null && !hasPickaxe(bot, required)) {
            // The old, nearly broken pick remains in inventory. Count only usable
            // replacements, or acquisition immediately succeeds with that same pick.
            child = obtain(LegacyItems.usableTool(required), 1); return false;
        }
        if(id.equals("flint") && block==null && recycleGravel(bot)) return false;
        Set<String> names = new HashSet<>(Arrays.asList(sources(target)));
        if (block == null) {
            java.util.function.Predicate<BlockState> matches = state -> {
                Block b = state.getBlock(); String name = String.valueOf(Block.REGISTRY.getIdentifier(b)).replace("minecraft:", "");
                if (!names.contains(name)) return false;
                int data = b.getData(state);
                if ((name.equals("log") || name.equals("log2")) && target.data >= 0 && (data & 3) != target.data) return false;
                if (name.equals("sand") && target.data >= 0 && data != target.data) return false;
                if (name.equals("stone") && data != 0) return false;
                if ((name.equals("wheat") || name.equals("carrots") || name.equals("potatoes")) && data < 7) return false;
                return true;
            };
            if(id.equals("cobblestone") && count-bot.count(target)>=12 && !denseStonePlanned) {
                denseStonePlanned=true;
                LegacyTask access=StoneAccessTask.planDeep(bot);
                if(access!=null) {stopExploring(bot);child=access;return false;}
            }
            // Prefer a face we can mine now, especially the walls exposed by
            // a newly dug shaft. Do not walk away while waiting for a wide rescan.
            if(block==null && bot.ticks>=nextLocalScan) {
                nextLocalScan=bot.ticks+5;
                // Mine the wall at standing height before excavating lower blocks.
                // Chasing drops from successively lower walls turns tool gathering
                // into an unnecessary descending shaft in natural terrain.
                int standingY=bot.navigation.feet().y;
                block=bot.nearestBlock(matches,5,rejected,p -> p.getY()>=standingY && exposedSource(bot,p) && bot.hit(p)!=null);
                if(block==null) block=bot.nearestBlock(matches,5,rejected,p -> exposedSource(bot,p) && bot.hit(p)!=null);
            }
            if (block==null && bot.ticks >= nextResourceScan) {
                nextResourceScan = bot.ticks + 20;
                block = bot.nearestBlock(matches, 24, rejected, p -> exposedSource(bot, p));
            }
            if (block == null) {
                
                if (id.equals("cobblestone") && bot.ticks >= nextStoneAccess) {
                    nextStoneAccess = bot.ticks + 100;
                    LegacyTask access = StoneAccessTask.plan(bot);
                    if(access==null) access=StoneAccessTask.nearby(bot);
                    if (access != null) {
                        stopExploring(bot); child = access; return false;
                    }
                }
                if (Arrays.asList("iron_ore","coal","flint","gravel").contains(id) && bot.ticks >= nextOrePlan) {
                    if (oreScan == null) {
                        PathSearch.Pos feet = bot.navigation.feet();
                        // Include the lowest reachable legacy levels. Coal and
                        // iron veins at Y=1 were previously invisible because
                        // the scan floor was hard-clamped to Y=2.
                        oreScan = new ResourceScan(feet.x, feet.z, 24, Math.max(1, feet.y - 24), feet.y);
                    }
                    PathSearch.Pos ore = oreScan.poll(8192, p -> {
                        BlockPos pos = new BlockPos(p.x,p.y,p.z);
                        return !rejectedOre.contains(pos) && bot.mc.world.isLoaded(pos,false)
                                && matches.test(bot.mc.world.getBlockState(pos));
                    });
                    if (ore != null) {
                        BlockPos pos = new BlockPos(ore.x,ore.y,ore.z);
                        nextOrePlan = bot.ticks + 20;
                        StoneAccessTask access=null;
                        if(id.equals("flint") || id.equals("gravel")) {
                            for(Direction side:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                                access=StoneAccessTask.planOre(bot,pos.offset(side),"gravel");
                                if(access!=null) break;
                            }
                        } else access=StoneAccessTask.planOre(bot,pos,id.equals("coal") ? "coal" : "iron ore");
                        if (access != null) { stopExploring(bot); child=access; return false; }
                        rejectedOre.add(pos);
                    } else if (!oreScan.exhausted()) { bot.releaseMovement(); return false; }
                    else {
                        oreScan=null; nextOrePlan=bot.ticks+100;
                        PathSearch.Pos feet=bot.navigation.feet();
                        if(id.equals("iron_ore") && feet.y>64) {
                            StoneAccessTask descend=StoneAccessTask.planOre(bot,
                                    new BlockPos(feet.x,Math.max(48,feet.y-24),feet.z),"iron-bearing depth");
                            if(descend!=null) {stopExploring(bot);child=descend;return false;}
                        }
                    }
                }
                if(id.equals("reeds") && savedCaneViewDistance<0 && bot.ticks>=nextCaneBoost) {
                    savedCaneViewDistance=bot.mc.options.viewDistance;
                    caneScanDistance=Math.max(16,savedCaneViewDistance);
                    bot.mc.options.viewDistance=caneScanDistance;caneScanStarted=bot.ticks;distantScan=null;
                    caneScanServerTick=bot.mc.getServer()==null ? 0 : bot.mc.getServer().getTicks();
                    bot.say("Looking for sugar cane at "+caneScanDistance+"-chunk render distance; restoring it when a target is found.");
                }
                if(savedCaneViewDistance>=0 && bot.ticks-caneScanStarted<40) {bot.releaseMovement();return false;}
                if (distantScan == null) {
                    PathSearch.Pos feet = bot.navigation.feet();
                    distantScan = new LoadedResourceScan(feet);
                }
                BlockPos found = distantScan.poll(bot, (pos,state) -> !rejected.contains(pos)
                        && matches.test(state) && exposedSource(bot,pos));
                if (found != null) block = found;
                else {
                    if (distantScan.exhausted()) {
                        distantScan = null;
                        // Expanded chunks arrive asynchronously. Keep the same
                        // high-distance search active across rescans until a
                        // source is found, instead of lowering before they load.
                        if(savedCaneViewDistance>=0) {
                            if(caneScanDistance<32 && bot.ticks-caneScanStarted>400
                                    && (bot.mc.getServer()==null || bot.mc.getServer().getTicks()-caneScanServerTick>=20)) {
                                caneScanDistance=32;bot.mc.options.viewDistance=32;
                                bot.say("No cane found in the expanded area yet; extending the scan to 32 chunks.");
                            }
                            bot.releaseMovement();return false;
                        }
                        explore(bot);
                    }
                    else if (exploring && savedCaneViewDistance<0) explore(bot);
                    else bot.releaseMovement();
                    return false;
                }
            }
            stopExploring(bot);
            restoreCaneViewDistance(bot);
            if(caneDistanceSettled!=null) return false;
        }
        
        // A remembered distant chunk may unload as the player moves.
        // Keep walking toward its remembered position until it loads again.
        if(id.equals("reeds") && bot.mc.player.squaredDistanceTo(block.getX()+.5,block.getY()+.5,block.getZ()+.5)>24*24) {
            // Long-distance travel ends near the patch. The mining approach
            // chooses a reachable bank and can harvest across a short water gap;
            // requiring a walking destination inside the cane patch rejects it.
            child=new StructureTravelTask(block,16);
            return false;
        }
        if (!bot.mc.world.isLoaded(block, false)) {
            PathSearch.Pos feet=bot.navigation.feet();
            bot.navigation.move(new PathSearch.Pos(block.getX(),feet.y,block.getZ()),.5);
            return false;
        }
        // Do not excavate the block supporting the player or open an adjacent liquid.
        if (block.equals(new BlockPos(bot.mc.player).down())) { rejected.add(block); block = null; return false; }
        for (Direction d : Direction.values()) {
            BlockPos adjacent = block.offset(d);
            if (!id.equals("reeds") && bot.mc.world.getBlockState(adjacent).getBlock().getMaterial().isFluid()) {
                rejected.add(block); block = null; return false;
            }
        }
        // A low stone seam can leave a one-block-high recess with dirt above
        // the drops. Carve a two-block corridor so mining also opens pickup access.
        if(id.equals("cobblestone") && block.getY()==bot.navigation.feet().y
                && !bot.navigation.clear(block.getX(),block.getY()+1,block.getZ())) {
            StoneAccessTask access=StoneAccessTask.planOre(bot,block,"stone");
            if(access!=null) {child=access;block=null;bot.cancelMining();bot.navigation.reset();return false;}
        }
        try {
            if (bot.mine(block)) {
                block = null; attempts = 0;
                if(id.equals("reeds")) nextCaneBoost=Math.max(nextCaneBoost,bot.ticks+40);
                // A new drop can be collected as soon as vanilla permits it. Scan
                // the newly exposed face next tick instead of sleeping after every block.
                nextLocalScan = bot.ticks; nextResourceScan = bot.ticks;
            }
        } catch (TaskFailure failure) {
            bot.cancelMining(); bot.navigation.reset();
            if(id.equals("reeds")) bot.say("Reeds approach failed at "+block+" from "+bot.navigation.feet()
                    +" (have "+bot.count(target)+"): "+failure.getMessage());
            // An exposed cave face is not necessarily reachable from the surface.
            // Prefer a short, safe local shaft before chasing more distant faces.
            if(id.equals("cobblestone") && bot.ticks>=nextStoneAccess) {
                nextStoneAccess=bot.ticks+100;
                LegacyTask access=StoneAccessTask.plan(bot);
                if(access==null) access=StoneAccessTask.nearby(bot);
                if(access!=null) {child=access;block=null;return false;}
            }
            RiverCrossingTask crossing=RiverCrossingTask.plan(bot,block);
            if(crossing!=null) {child=crossing;return false;}
            if((target.woodFamily || id.equals("log") || id.equals("log2") || id.equals("reeds"))
                    && SurfaceAccessTask.underground(bot)
                    && block.getY()>bot.navigation.feet().y+1 && (hasPickaxe(bot,"wooden_pickaxe")
                    || bot.count(LegacyItems.parse("dirt"))+bot.count(LegacyItems.parse("planks"))+bot.count(LegacyItems.anyLogs())>0)) {
                child=new SurfaceAccessTask(Math.min(block.getY(),bot.navigation.feet().y+8),protectedCobblestone,0);block=null;return false;
            }
            rejected.add(block); block = null;
            if (++attempts >= 12) { attempts = 0; exploreUntil=bot.ticks+160; explore(bot); }
        }
        return false;
    }
    private void rejectAnimal(LegacyBot bot,LivingEntity animal) {
        if(bot.ticks>=nextHuntExit) {
            RiverCrossingTask crossing=RiverCrossingTask.plan(bot,new BlockPos(animal));
            if(crossing!=null) {
                nextHuntExit=bot.ticks+200;child=crossing;
                animalApproach=null;animalPosition=null;animalCandidates=null;
                bot.cancelMining();bot.releaseMovement();bot.navigation.reset();return;
            }
        }
        // Tool crafting can finish within reach of the table while our feet are
        // still in a two-block mining pit. Recover height before rejecting the herd.
        int feetY=bot.navigation.feet().y;
        if(animal.y>feetY+1.5 && bot.mc.player.onGround && bot.ticks>=nextHuntExit
                && SurfaceAccessTask.underground(bot)) {
            nextHuntExit=bot.ticks+200;
            child=new SurfaceAccessTask(null,protectedCobblestone,0);
            animalApproach=null;animalPosition=null;animalCandidates=null;
            bot.cancelMining();bot.releaseMovement();bot.navigation.reset();return;
        }
        int failures=animalFailures.getOrDefault(animal.getUuid(),0)+1;
        animalFailures.put(animal.getUuid(),failures);
        org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("Skipping inaccessible animal {} at {} (attempt {})",animal.getUuid(),new BlockPos(animal),failures);
        rejectedAnimals.put(animal.getUuid(),bot.ticks+(failures==1 ? 1200 : 6000));huntedAnimal=null;huntedEntity=null;animalApproach=null;animalPosition=null;
        bot.releaseMovement();bot.navigation.reset();
    }
    private void approachAnimal(LegacyBot bot,LivingEntity animal) {
        BlockPos current=new BlockPos(animal);
        if(!current.equals(animalPosition)) {animalPosition=current;animalApproach=null;animalCandidates=null;bot.navigation.reset();}
        if(animalApproach==null) {
            if(!bot.mc.player.onGround) return;
            PathSearch.Pos from=bot.navigation.feet();
            if(animalCandidates==null) {
                List<PathSearch.Pos> candidates=new ArrayList<>();
                Vec3d aim=new Vec3d(animal.x,animal.y+animal.getEyeHeight(),animal.z);
                for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=-1;dy<=1;dy++) {
                    PathSearch.Pos p=new PathSearch.Pos(current.getX()+dx,current.getY()+dy,current.getZ()+dz);
                    if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z)) continue;
                    double ax=p.x+.5-animal.x,ay=p.y-animal.y,az=p.z+.5-animal.z;
                    if(ax*ax+ay*ay+az*az>6) continue;
                    if(bot.mc.world.rayTrace(new Vec3d(p.x+.5,p.y+bot.mc.player.getEyeHeight(),p.z+.5),aim,false,true,false)==null) candidates.add(p);
                }
                candidates.sort(Comparator.comparingDouble(from::distance));
                animalCandidates=candidates;animalCandidateIndex=0;diggingAnimalApproaches=false;
            }
            for(int budget=0;budget<2 && animalCandidateIndex<Math.min(12,animalCandidates.size());budget++) {
                PathSearch.Pos p=animalCandidates.get(animalCandidateIndex++);
                List<PathSearch.Pos> path=diggingAnimalApproaches ? bot.navigation.approachRoute(from,p,1200) : PathSearch.find(bot.navigation,from,p,2000);
                if(from.equals(p) || !path.isEmpty() && path.get(path.size()-1).equals(p)) {animalApproach=p;break;}
            }
            if(animalApproach==null) {
                if(animalCandidateIndex>=Math.min(12,animalCandidates.size())) {
                    if(!diggingAnimalApproaches) {diggingAnimalApproaches=true;animalCandidateIndex=0;bot.releaseMovement();}
                    else rejectAnimal(bot,animal);
                }
                else bot.releaseMovement();
                return;
            }
        }
        try {bot.navigation.move(animalApproach,.2);}
        catch(TaskFailure blocked) {rejectAnimal(bot,animal);}
    }
    private boolean recycleGravel(LegacyBot bot) {
        LegacyItems.Target gravel=LegacyItems.parse("gravel");
        int slot=bot.findInventory(gravel);
        if(slot<0) {
            for(Object e:bot.mc.world.loadedEntities) if(e instanceof ItemEntity) {
                ItemEntity item=(ItemEntity)e;
                if(!item.removed && gravel.matches(item.getItemStack()) && bot.mc.player.squaredDistanceTo(item)<36) {
                    child=obtain(gravel,1);return true;
                }
            }
            return false;
        }
        if(!bot.mc.player.onGround) return false;
        BlockPos feet=new BlockPos(bot.mc.player);
        ItemStack stack=bot.mc.player.inventory.main[slot];
        Block placing=Block.getBlockFromItem(stack.getItem());
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        for(int r=1;r<=2;r++) for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r) continue;
            BlockPos place=feet.add(dx,0,dz);
            if(bot.interactiveSupport(place.down()) || !bot.navigation.floor(place.getX(),place.getY()-1,place.getZ())
                    || !bot.mc.world.canBlockBePlaced(placing,place,false,Direction.UP,null,stack)) continue;
            Vec3d aim=new Vec3d(place.getX()+.5,place.getY()-.001,place.getZ()+.5);
            net.minecraft.util.hit.BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
            if(hit==null || !place.down().equals(hit.getBlockPos()) || hit.direction!=Direction.UP) continue;
            bot.releaseMovement();bot.cancelMining();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
            bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,stack,hit.getBlockPos(),hit.direction,hit.pos);
            if(!bot.mc.world.isAir(place)) block=place;
            return true;
        }
        child=WorkstationSpaceTask.plan(bot,gravelSpaces);
        return child!=null;
    }
    private boolean exposedSource(LegacyBot bot, BlockPos pos) {
        if (pos.equals(new BlockPos(bot.mc.player).down())) return false;
        boolean shoreCrop=LegacyItems.id(target.item).equals("reeds");
        boolean exposed = false;
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            if (!bot.mc.world.isLoaded(adjacent, false)) continue;
            if (!shoreCrop && bot.mc.world.getBlockState(adjacent).getBlock().getMaterial().isFluid()) return false;
            if (bot.navigation.clear(adjacent.getX(), adjacent.getY(), adjacent.getZ())) exposed = true;
        }
        return exposed;
    }
    private void explore(LegacyBot bot) {
        bot.cancelMining();
        StoneAccessTask descent=StoneAccessTask.leavePillar(bot);
        if(descent!=null) {child=descent;return;}
        String resource=LegacyItems.id(target.item);
        if((Arrays.asList("flint","gravel","sand","clay_ball","log","log2","reeds").contains(resource)
                || mob(target)!=null) && SurfaceAccessTask.underground(bot)) {
            child=new SurfaceAccessTask(null,protectedCobblestone,0);return;
        }
        if (exploration == null) exploration = new ResourceExploration();
        exploring = true;
        
        try {exploration.tick(bot, target.toString());}
        catch(TaskFailure noWalkingExit) {
            PathSearch.Pos feet=bot.navigation.feet();
            if(Arrays.asList("iron_ore","coal","flint","gravel","cobblestone").contains(resource)
                    && bot.mc.player.onGround) {
                // A depleted vein is an excavated pocket, not evidence that
                // the surrounding loaded terrain has no more resources.
                for(int[] d:new int[][]{{0,-4,0},{4,0,0},{-4,0,0},{0,0,4},{0,0,-4}}) {
                    if(feet.y+d[1]<2) continue;
                    StoneAccessTask access=StoneAccessTask.planOre(bot,
                            new BlockPos(feet.x+d[0],feet.y+d[1],feet.z+d[2]),resource+" search access");
                    if(access==null) continue;
                    exploration=null;exploring=false;exploreUntil=0;
                    oreScan=null;nextOrePlan=0;rejectedOre.clear();rejected.clear();block=null;
                    bot.navigation.reset();child=access;return;
                }
            }
            throw noWalkingExit;
        }
    }
    private void stopExploring(LegacyBot bot) {
        if (exploring) {
            exploring = false; exploration.suspend();
            bot.releaseMovement(); bot.navigation.reset();
        }
    }
    static int combatWeapon(LegacyBot bot) {
        // 1.8 combat: prefer a sword; use an axe when no usable sword is carried.
        for(String id:new String[]{"diamond_sword","iron_sword","stone_sword","wooden_sword","golden_sword",
                "diamond_axe","iron_axe","stone_axe","wooden_axe","golden_axe"}) {
            for(int slot=0;slot<36;slot++) {
                ItemStack stack=bot.mc.player.inventory.main[slot];
                if(stack!=null && LegacyItems.id(stack.getItem()).equals(id)
                        && (!stack.isDamageable() || stack.getDamage()<stack.getMaxDamage()-1)) return slot;
            }
        }
        return -1;
    }
    private static String requiredPickaxe(String id) {
        if (Arrays.asList("cobblestone", "coal", "netherrack", "end_stone", "quartz").contains(id)) return "wooden_pickaxe";
        if (Arrays.asList("iron_ore", "dye").contains(id)) return "stone_pickaxe";
        if (Arrays.asList("gold_ore", "diamond", "emerald", "redstone").contains(id)) return "iron_pickaxe";
        return id.equals("obsidian") ? "diamond_pickaxe" : null;
    }
    private static boolean hasPickaxe(LegacyBot bot, String required) {
        List<String> tiers = Arrays.asList("wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe");
        int level = tiers.indexOf(required);
        for (ItemStack s : bot.mc.player.inventory.main) if (s != null && tiers.indexOf(LegacyItems.id(s.getItem())) >= level
                && LegacyItems.usableTool(s)) return true;
        return false;
    }
}
