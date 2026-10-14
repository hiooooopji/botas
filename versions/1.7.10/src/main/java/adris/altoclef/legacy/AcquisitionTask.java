package adris.altoclef.legacy;

import net.minecraft.block.*;
import net.minecraft.entity.*;
import net.minecraft.entity.passive.*;
import net.minecraft.item.*;
import net.minecraft.util.math.*;
import java.util.*;

/** Version-specific resource planning using real 1.7.10 recipes and normal player actions. */
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
    private UUID pickupId;
    private PathSearch.Pos pickupGoal;
    private int pickupStarted, pickupArrivedTicks;
    private double pickupBestDistance;
    private final Set<PathSearch.Pos> rejectedPickupGoals=new HashSet<>();
    private ResourceExploration exploration;
    private boolean exploring;
    private int nextResourceScan, nextStoneAccess, nextLocalScan;
    private int exploreUntil;
    private ResourceScan oreScan;
    private LoadedResourceScan distantScan;
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
    private int nextAlternativeScan;
    private java.util.function.Predicate<LivingEntity> animalFilter;

    // Bounded hunting reuses pickup, combat and obstacle recovery without
    // starting an unbounded resource search when this area is exhausted.
    boolean huntNearby(LegacyBot bot,java.util.function.Predicate<LivingEntity> filter) {
        animalFilter=filter;
        if(pickup(bot)) return false;
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;}return false;}
        return gather(bot);
    }

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
    public String describe() { return mode + " " + target + " x" + count + (!exploring ? "" : " (exploring)")
            + (block==null ? "" : " at "+block.x+" "+block.y+" "+block.z)
            + (huntedEntity==null ? "" : " -> approach "+huntedEntity.getClass().getSimpleName()+" at "+(int)huntedEntity.x+" "+(int)huntedEntity.y+" "+(int)huntedEntity.z)
            + (child == null ? "" : " -> " + child.describe()); }
    public void stop(LegacyBot bot) { if (child != null) child.stop(bot); }

    // Do not abandon crafting-grid or furnace transfers midway through a click.
    boolean canYieldToDrop() {
        return child == null || child instanceof StoneAccessTask || child instanceof RiverCrossingTask
                || child instanceof SurfaceAccessTask
                || child instanceof AcquisitionTask && ((AcquisitionTask) child).canYieldToDrop();
    }

    public boolean tick(LegacyBot bot) {
        int have = bot.count(target);
        noProgress = have == lastCount ? noProgress + 1 : 0; lastCount = have;
        if (noProgress > 12000) throw new TaskFailure("No acquisition progress for " + target + ".");
        if (mode.equals("get") && canYieldToDrop() && (child == null || bot.mc.field_3805.onGround)) {
            if (have >= count) {
                if (child != null) { child.stop(bot); child = null; }
                bot.releaseMovement(); bot.cancelMining(); return true;
            }
            // Check even with a selected block or a resource dependency underway.
            // Any matching quantity is useful, even when it only partly fills
            // the target. Recompute the remainder after collecting that stack.
            if (pickup(bot)) {
                if (child != null) { child.stop(bot); child = null; }
                block = null;
                if (exploration != null) exploration.suspend();
                exploring = false; exploreUntil = 0;
                return false;
            }
        }
        // Access tunnels can consume the pickaxe after gather() has checked it.
        // Recheck before advancing a mining child, but let replacement acquisition
        // finish. Crafting may move us, so discard the old route and replan.
        String required = requiredPickaxe(LegacyItems.id(target.item));
        if (child != null && !(child instanceof AcquisitionTask) && mode.equals("get")
                && required != null && !hasPickaxe(bot, required)) {
            child.stop(bot);
            bot.cancelMining(); bot.releaseMovement(); bot.navigation.reset();
            block = null;
            child = obtain(LegacyItems.usableTool(required), 1);
            bot.say("Replacing worn " + required + " before continuing " + target + ".");
        }
        if (child != null) {
            if (child.tick(bot)) { child.stop(bot); child = null; bot.navigation.reset(); }
            return false;
        }
        if (have >= count) { bot.releaseMovement(); bot.cancelMining(); return true; }
        if (mode.equals("get") && bot.ticks>=nextChestScan) {
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
        if (mode.equals("smelt")) throw new TaskFailure("No 1.7.10 furnace recipe for " + target + ".");

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
            if (bot.count(entry.getKey()) < entry.getValue()) {
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

    boolean pickup(LegacyBot bot) {
        // Try other drops before mining when the nearest one is inaccessible.
        // Retry rejected entities later: falling items and our route can move.
        rejectedItems.entrySet().removeIf(entry -> bot.ticks >= entry.getValue());
        for (int attempt=0;attempt<16;attempt++) {
            int rejectedBefore=rejectedItems.size();
            if (tryPickup(bot)) return true;
            if (rejectedItems.size()==rejectedBefore) return false;
        }
        bot.cancelMining();bot.releaseMovement();
        return true;
    }

    private boolean tryPickup(LegacyBot bot) {
        ItemEntity nearest = null;
        double distance = 64 * 64;
        for (Object entry : bot.mc.world.loadedEntities) if (entry instanceof ItemEntity) {
            ItemEntity item = (ItemEntity) entry;
            double d = LegacyPos.squaredDistance(bot.mc.field_3805,item);
            if (item.removed || rejectedItems.containsKey(item.getUuid()) || !target.matches(item.getItemStack()) || d>=64*64) continue;
            // Start approaching fresh drops during their short vanilla pickup delay,
            // and keep following the same entity instead of oscillating between drops.
            if(item.getUuid().equals(pickupId)) {nearest=item;break;}
            if(d<distance) {nearest=item;distance=d;}
        }
        if (nearest == null) { pickupId=null; pickupGoal=null; return false; }
        if (!nearest.getUuid().equals(pickupId)) {
            bot.cancelMining();
            pickupId=nearest.getUuid(); pickupGoal=null; pickupStarted=bot.ticks;
            pickupBestDistance=LegacyPos.squaredDistance(bot.mc.field_3805,nearest);
            pickupArrivedTicks=0;rejectedPickupGoals.clear();
            bot.navigation.reset();
        }
        // A drop behind a ledge must not monopolize the acquisition task forever.
        double currentDistance=LegacyPos.squaredDistance(bot.mc.field_3805,nearest);
        if(currentDistance<pickupBestDistance-.25) {pickupBestDistance=currentDistance;pickupStarted=bot.ticks;}
        if (bot.ticks-pickupStarted>160) {
            rejectedItems.put(pickupId,bot.ticks+200); pickupId=null; pickupGoal=null;
            bot.navigation.reset(); bot.releaseMovement(); return false;
        }
        if (bot.mc.field_3805.inventory.getEmptySlot() < 0) {
            boolean space = false;
            for (ItemStack stack : bot.mc.field_3805.inventory.main) if (target.matches(stack) && stack.count < stack.getMaxCount()) space = true;
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
            if (!bot.mc.field_3805.onGround) return false;
            PathSearch.Pos from=PathSearch.groundedStart(bot.navigation,bot.mc.field_3805.x,bot.navigation.feet().y,bot.mc.field_3805.z);
            List<PathSearch.Pos> candidates=new ArrayList<>();
            for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) for (int dy=0;dy>=-4;dy--) {
                PathSearch.Pos p=new PathSearch.Pos(x+dx,y+dy,z+dz);
                // Pickup requires vertical overlap as well as horizontal proximity.
                if (nearest.y<p.y-.25 || nearest.y>p.y+2.05
                        || Math.abs(p.x+.5-nearest.x)>1.25 || Math.abs(p.z+.5-nearest.z)>1.25) continue;
                if (!rejectedPickupGoals.contains(p) && PathSearch.standing(bot.navigation,p.x,p.y,p.z)) candidates.add(p);
            }
            final ItemEntity drop=nearest;
            candidates.sort(Comparator.comparingDouble(p ->
                    4*(Math.abs(p.x+.5-drop.x)+Math.abs(p.z+.5-drop.z))+from.distance(p)));
            for (PathSearch.Pos p:candidates) {
                List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,p,2000);
                if (from.equals(p) || !route.isEmpty() && route.get(route.size()-1).equals(p)) {pickupGoal=p;break;}
            }
            if (pickupGoal==null) {rejectedItems.put(pickupId,bot.ticks+200);pickupId=null;return false;}
        }
        try {
            if(bot.navigation.move(pickupGoal, .15)) {
                if(++pickupArrivedTicks>6 && nearest.pickupDelay == 0) {
                    rejectedPickupGoals.add(pickupGoal);pickupGoal=null;pickupArrivedTicks=0;
                }
            } else pickupArrivedTicks=0;
        }
        catch (TaskFailure blocked) { rejectedItems.put(nearest.getUuid(),bot.ticks+200); pickupId=null;pickupGoal=null;bot.navigation.reset(); return false; }
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
                Block block=LegacyWorld.state(bot.mc.world,p).getBlock();
                if(block!=Block.get("chest") && block!=Block.get("trapped_chest")) continue;
                double d=LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p);
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
            case "wool": return new String[]{"wool"};
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
            case "wool": return null; // Wool comes only from blocks or existing drops.
            case "rabbit": case "rabbit_hide": return null; // Rabbits were added in 1.8.
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
                    && (animalFilter==null || animalFilter.test(huntedEntity))
                    && rejectedAnimals.getOrDefault(huntedEntity.getUuid(),0)<=bot.ticks) {
                nearest=huntedEntity;distance=LegacyPos.squaredDistance(bot.mc.field_3805,nearest);
            }
            if(nearest==null)
            for (Object e : bot.mc.world.loadedEntities) if (animal.isInstance(e) || target.meatFamily==1 && e instanceof PigEntity) {
                LivingEntity mob = (LivingEntity) e;
                double d = LegacyPos.squaredDistance(bot.mc.field_3805,mob);
                double score=mob.getUuid().equals(huntedAnimal) ? d*.7 : d;
                if (!mob.isAlive() || mob.isBaby() || rejectedAnimals.getOrDefault(mob.getUuid(),0)>bot.ticks) continue;
                if(animalFilter!=null && !animalFilter.test(mob)) continue;
                if(score < nearestScore) { nearest = mob; distance = d; nearestScore=score; }
            }
            if (nearest == null) {if(animalFilter!=null) return true; explore(bot); return false; }
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
                Vec3d eye = Vec3d.of(bot.mc.field_3805.x, bot.mc.field_3805.boundingBox.minY + LegacyPos.eyeHeight(bot.mc.field_3805), bot.mc.field_3805.z);
                if (bot.mc.world.rayTrace(eye, Vec3d.of(nearest.x, nearest.y + nearest.getEyeHeight(), nearest.z),false,true,false) != null) {
                    approachAnimal(bot,nearest);return false;
                }
                if (bot.ticks % 10 == 0) { bot.mc.interactionManager.attackEntity(bot.mc.field_3805, nearest); bot.mc.field_3805.swingHand();huntStarted=bot.ticks; }
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
            java.util.function.Predicate<BlockState> matches = state -> {
                Block b = state.getBlock(); String name = String.valueOf(Block.REGISTRY.getId(b)).replace("minecraft:", "");
                if (!names.contains(name)) return false;
                int data = state.getData();
                if ((name.equals("log") || name.equals("log2")) && target.data >= 0 && (data & 3) != target.data) return false;
                if (name.equals("sand") && target.data >= 0 && data != target.data) return false;
                if (name.equals("wool") && target.data >= 0 && data != target.data) return false;
                if (name.equals("stone") && data != 0) return false;
                if ((name.equals("wheat") || name.equals("carrots") || name.equals("potatoes")) && data < 7) return false;
                return true;
            };
        if(block!=null && bot.hit(block)==null && bot.ticks>=nextAlternativeScan) {
            nextAlternativeScan=bot.ticks+40;
            BlockPos alternative=LegacySourceSearch.accessible(bot,matches,24,rejected,p->exposedSource(bot,p));
            if(alternative!=null && !alternative.equals(block)
                    && (block.y<bot.navigation.feet().y-2 || LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,alternative)
                    <LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,block))) {
                block=alternative;bot.cancelMining();bot.navigation.reset();
            }
        }
        if (block == null) {
            // Prefer a face we can mine now, especially the walls exposed by
            // a newly dug shaft. Do not walk away while waiting for a wide rescan.
            if(block==null && bot.ticks>=nextLocalScan) {
                nextLocalScan=bot.ticks+5;
                // Mine the wall at standing height before excavating lower blocks.
                // Chasing drops from successively lower walls turns tool gathering
                // into an unnecessary descending shaft in natural terrain.
                block=bot.nearestBlock(matches,5,rejected,p -> exposedSource(bot,p) && bot.hit(p)!=null);
            }
            if (block==null && bot.ticks >= nextResourceScan) {
                nextResourceScan = bot.ticks + 20;
                block=LegacySourceSearch.accessible(bot,matches,24,rejected,p->exposedSource(bot,p));
                if(block==null) block = bot.nearestBlock(matches, 24, rejected, p -> exposedSource(bot, p));
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
                        return !rejectedOre.contains(pos) && LegacyWorld.isLoaded(bot.mc.world,pos,false)
                                && matches.test(LegacyWorld.state(bot.mc.world,pos));
                    });
                    if (ore != null) {
                        BlockPos pos = new BlockPos(ore.x,ore.y,ore.z);
                        nextOrePlan = bot.ticks + 20;
                        StoneAccessTask access=null;
                        if(id.equals("flint") || id.equals("gravel")) {
                            for(Direction side:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                                access=StoneAccessTask.planOre(bot,LegacyPos.offset(pos,side),"gravel");
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
                if (distantScan == null) {
                    PathSearch.Pos feet = bot.navigation.feet();
                    distantScan = new LoadedResourceScan(feet);
                }
                BlockPos found = distantScan.poll(bot, (pos,state) -> !rejected.contains(pos)
                        && matches.test(state) && exposedSource(bot,pos));
                if (found != null) block = found;
                else {
                    if (distantScan.exhausted()) { distantScan = null; explore(bot); }
                    else if (exploring) explore(bot);
                    else bot.releaseMovement();
                    return false;
                }
            }
            stopExploring(bot);
        }
        
        // A remembered distant chunk may unload as the player moves.
        // Keep walking toward its remembered position until it loads again.
        if (!LegacyWorld.isLoaded(bot.mc.world,block, false)) {
            PathSearch.Pos feet=bot.navigation.feet();
            bot.navigation.move(new PathSearch.Pos(block.x,feet.y,block.z),.5);
            return false;
        }
        // Do not excavate the block supporting the player or open an adjacent liquid.
        if (block.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805)))) { rejected.add(block); block = null; return false; }
        for (Direction d : Direction.values()) {
            BlockPos adjacent = LegacyPos.offset(block,d);
            if (LegacyWorld.state(bot.mc.world,adjacent).getBlock().getMaterial().isFluid()) {
                rejected.add(block); block = null; return false;
            }
        }
        // A low stone seam can leave a one-block-high recess with dirt above
        // the drops. Carve a two-block corridor so mining also opens pickup access.
        if(id.equals("cobblestone") && block.y==bot.navigation.feet().y
                && !bot.navigation.clear(block.x,block.y+1,block.z)) {
            StoneAccessTask access=StoneAccessTask.planOre(bot,block,"stone");
            if(access!=null) {child=access;block=null;bot.cancelMining();bot.navigation.reset();return false;}
        }
        try {
            if (bot.mine(block)) {
                block = null; attempts = 0;
                // A new drop can be collected as soon as vanilla permits it. Scan
                // the newly exposed face next tick instead of sleeping after every block.
                nextLocalScan = bot.ticks; nextResourceScan = bot.ticks;
            }
        } catch (TaskFailure failure) {
            org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").warn("Cannot gather {} at {}: {}", id, block, failure.getMessage());
            bot.cancelMining(); bot.navigation.reset();
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
                    && block.y>bot.navigation.feet().y+1 && (hasPickaxe(bot,"wooden_pickaxe")
                    || bot.count(LegacyItems.parse("dirt"))+bot.count(LegacyItems.parse("planks"))+bot.count(LegacyItems.anyLogs())>0)) {
                child=new SurfaceAccessTask(Math.min(block.y,bot.navigation.feet().y+8),protectedCobblestone,0);block=null;return false;
            }
            rejected.add(block); block = null;
            if (++attempts >= 12) { attempts = 0; exploreUntil=bot.ticks+160; explore(bot); }
        }
        return false;
    }
    private void rejectAnimal(LegacyBot bot,LivingEntity animal) {
        if(bot.ticks>=nextHuntExit) {
            RiverCrossingTask crossing=RiverCrossingTask.plan(bot,LegacyPos.of(animal));
            if(crossing!=null) {
                nextHuntExit=bot.ticks+200;child=crossing;
                animalApproach=null;animalPosition=null;animalCandidates=null;
                bot.cancelMining();bot.releaseMovement();bot.navigation.reset();return;
            }
        }
        // Tool crafting can finish within reach of the table while our feet are
        // still in a two-block mining pit. Recover height before rejecting the herd.
        int feetY=bot.navigation.feet().y;
        if(animal.y>feetY+1.5 && bot.mc.field_3805.onGround && bot.ticks>=nextHuntExit
                && SurfaceAccessTask.underground(bot)) {
            nextHuntExit=bot.ticks+200;
            child=new SurfaceAccessTask(null,protectedCobblestone,0);
            animalApproach=null;animalPosition=null;animalCandidates=null;
            bot.cancelMining();bot.releaseMovement();bot.navigation.reset();return;
        }
        int failures=animalFailures.getOrDefault(animal.getUuid(),0)+1;
        animalFailures.put(animal.getUuid(),failures);
        org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("Skipping inaccessible animal {} at {} (attempt {})",animal.getUuid(),LegacyPos.of(animal),failures);
        rejectedAnimals.put(animal.getUuid(),bot.ticks+(failures==1 ? 1200 : 6000));huntedAnimal=null;huntedEntity=null;animalApproach=null;animalPosition=null;
        bot.releaseMovement();bot.navigation.reset();
    }
    private void approachAnimal(LegacyBot bot,LivingEntity animal) {
        BlockPos current=LegacyPos.of(animal);
        if(!current.equals(animalPosition)) {animalPosition=current;animalApproach=null;animalCandidates=null;bot.navigation.reset();}
        if(animalApproach==null) {
            if(!bot.mc.field_3805.onGround) return;
            PathSearch.Pos from=bot.navigation.feet();
            if(animalCandidates==null) {
                List<PathSearch.Pos> candidates=new ArrayList<>();
                Vec3d aim=Vec3d.of(animal.x,animal.y+animal.getEyeHeight(),animal.z);
                for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=-1;dy<=1;dy++) {
                    PathSearch.Pos p=new PathSearch.Pos(current.x+dx,current.y+dy,current.z+dz);
                    if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z)) continue;
                    double ax=p.x+.5-animal.x,ay=p.y-animal.y,az=p.z+.5-animal.z;
                    if(ax*ax+ay*ay+az*az>6) continue;
                    if(bot.mc.world.rayTrace(Vec3d.of(p.x+.5,p.y+LegacyPos.eyeHeight(bot.mc.field_3805),p.z+.5),aim,false,true,false)==null) candidates.add(p);
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
                if(!item.removed && gravel.matches(item.getItemStack()) && LegacyPos.squaredDistance(bot.mc.field_3805,item)<36) {
                    child=obtain(gravel,1);return true;
                }
            }
            return false;
        }
        if(!bot.mc.field_3805.onGround) return false;
        BlockPos feet=LegacyPos.of(bot.mc.field_3805);
        ItemStack stack=bot.mc.field_3805.inventory.main[slot];
        Block placing=Block.getBlockFromItem(stack.getItem());
        Vec3d eye=Vec3d.of(bot.mc.field_3805.x,bot.mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(bot.mc.field_3805),bot.mc.field_3805.z);
        for(int r=1;r<=2;r++) for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r) continue;
            BlockPos place=LegacyPos.add(feet,dx,0,dz);
            if(bot.interactiveSupport(LegacyPos.down(place)) || !bot.navigation.floor(place.x,place.y-1,place.z)
                    || !LegacyWorld.canPlace(bot.mc.world,placing,place,false,Direction.UP,null,stack)) continue;
            Vec3d aim=Vec3d.of(place.x+.5,place.y-.001,place.z+.5);
            net.minecraft.util.hit.BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
            if(hit==null || !LegacyPos.down(place).equals(LegacyPos.hit(hit)) || net.minecraft.util.math.Direction.getById(hit.side)!=Direction.UP) continue;
            bot.releaseMovement();bot.cancelMining();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
            LegacyInteraction.rightClick(bot.mc.interactionManager,bot.mc.field_3805,bot.mc.world,stack,LegacyPos.hit(hit),net.minecraft.util.math.Direction.getById(hit.side),hit.pos);
            if(!LegacyWorld.isAir(bot.mc.world,place)) block=place;
            return true;
        }
        child=WorkstationSpaceTask.plan(bot,gravelSpaces);
        return child!=null;
    }
    private boolean exposedSource(LegacyBot bot, BlockPos pos) {
        if (pos.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805)))) return false;
        boolean exposed = false;
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = LegacyPos.offset(pos,direction);
            if (!LegacyWorld.isLoaded(bot.mc.world,adjacent, false)) continue;
            if (LegacyWorld.state(bot.mc.world,adjacent).getBlock().getMaterial().isFluid()) return false;
            if (bot.navigation.clear(adjacent.x, adjacent.y, adjacent.z)) exposed = true;
        }
        return exposed;
    }
    private void explore(LegacyBot bot) {
        bot.cancelMining();
        CanopyDropTask canopy=CanopyDropTask.plan(bot);
        if(canopy!=null) {child=canopy;return;}
        StoneAccessTask descent=StoneAccessTask.leavePillar(bot);
        if(descent==null) descent=StoneAccessTask.leaveCanopy(bot);
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
                    && bot.mc.field_3805.onGround) {
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
                ItemStack stack=bot.mc.field_3805.inventory.main[slot];
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
        for (ItemStack s : bot.mc.field_3805.inventory.main) if (s != null && tiers.indexOf(LegacyItems.id(s.getItem())) >= level
                && LegacyItems.usableTool(s)) return true;
        return false;
    }
}
