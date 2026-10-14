package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import java.util.*;

final class WorkstationTasks {
    /** A cursor transaction. Uses ordinary clicks, returns leftovers, and never drops items. */
    static final class Transfer {
        final LegacyItems.Target target;
        final int destination, amount;
        int source = -1, moved, phase, nextTick;
        ScreenHandler handler;
        Transfer(LegacyItems.Target target, int destination, int amount) {
            this.target = target; this.destination = destination; this.amount = amount;
        }
        boolean tick(LegacyBot bot) {
            if (bot.ticks < nextTick) return false;
            nextTick = bot.ticks + 2;
            if (handler != null && handler != bot.mc.player.openScreenHandler) throw new TaskFailure("Container changed during inventory action.");
            if (phase == 0) {
                if (bot.mc.player.inventory.getCursorStack() != null) throw new TaskFailure("Inventory cursor is occupied.");
                int index = bot.findInventory(target);
                if (index < 0) throw new TaskFailure("Missing ingredient: " + target);
                source = bot.screenSlot(index); handler = bot.mc.player.openScreenHandler;
                bot.click(source, 0, 0); phase = 1; return false;
            }
            if (phase == 1 && moved < amount) {
                ItemStack cursor = bot.mc.player.inventory.getCursorStack();
                if (!target.matches(cursor)) { phase = 2; return false; }
                ItemStack existing = handler.getSlot(destination).getStack();
                if (!handler.getSlot(destination).canInsert(cursor)
                        || (existing != null && (!target.matches(existing) || existing.getData() != cursor.getData()
                            || !Objects.equals(existing.getNbt(), cursor.getNbt()) || existing.count >= existing.getMaxCount()))) {
                    throw new TaskFailure("Destination slot cannot accept " + target + ".");
                }
                int before = existing == null ? 0 : existing.count;
                bot.click(destination, 1, 0);
                ItemStack after = handler.getSlot(destination).getStack();
                if (after == null || after.count <= before) throw new TaskFailure("Inventory click was rejected.");
                moved++; return false;
            }
            // A nearby drop can fill the source slot while the cursor is held.
            // Return leftovers to any compatible/free slot without swapping in
            // the unrelated pickup or failing after a successful recovery.
            if (bot.mc.player.inventory.getCursorStack() != null) bot.recoverCursor();
            if (bot.mc.player.inventory.getCursorStack() != null) throw new TaskFailure("Could not return the inventory cursor stack.");
            if (moved < amount) { phase = 0; return false; }
            return true;
        }
    }

    static final class Open implements LegacyTask {
        final String blockName;
        final Class<? extends ScreenHandler> screen;
        final AcquisitionTask parent;
        int protectedCobblestone,protectedPlanks;
        boolean fixedPosition;
        LegacyTask child;
        BlockPos pos;
        int waitUntil, retries;
        final Set<PathSearch.Pos> placementApproaches = new HashSet<>();
        final Set<BlockPos> rejectedPlaces = new HashSet<>();
        final Set<BlockPos> preparedSpaces = new HashSet<>();
        Open(String blockName, Class<? extends ScreenHandler> screen, AcquisitionTask parent) {
            this.blockName = blockName; this.screen = screen; this.parent = parent;
        }
        public String describe() { return "open " + blockName + (child == null ? "" : " -> " + child.describe()); }
        public boolean tick(LegacyBot bot) {
            if (screen.isInstance(bot.mc.player.openScreenHandler)) {
                bot.ownedScreen = bot.mc.player.openScreenHandler; bot.expectedScreen = null; return true;
            }
            if (child != null) { if (child.tick(bot)) { child.stop(bot); child = null; } return false; }
            if (bot.ticks < waitUntil) return false;
            if (pos != null && !isBlock(bot, pos)) {
                if(fixedPosition) throw new TaskFailure("Remembered "+blockName+" was removed at "+pos);
                rejectedPlaces.add(pos); pos = null;
            }
            if(blockName.equals("furnace") && pos==null) {
                PathSearch.Pos here=bot.navigation.feet();
                int surface=SurfaceAccessTask.terrainSurface(bot,here.x,here.z);
                if(surface<0 || !bot.mc.player.onGround) {bot.releaseMovement();return false;}
                // A freshly dug open shaft has sky above it too. Compare nearby
                // terrain before treating its bottom as a surface furnace site.
                if(SurfaceAccessTask.underground(bot)) {
                    child=new SurfaceAccessTask(null,protectedCobblestone,protectedPlanks);return false;
                }
            }
            if (pos == null) pos = bot.nearestBlock(state -> {
                String id = String.valueOf(Block.REGISTRY.getIdentifier(state.getBlock()));
                return id.equals("minecraft:" + blockName) || blockName.equals("furnace") && id.equals("minecraft:lit_furnace");
            }, 16, rejectedPlaces, p -> !blockName.equals("furnace")
                    || p.getY()+1>=SurfaceAccessTask.terrainSurface(bot,p.getX(),p.getZ()));
            if (pos == null) {
                LegacyItems.Target item = LegacyItems.parse(blockName);
                if (bot.count(item) == 0) {
                    if (parent == null) throw new TaskFailure("No " + blockName + " found nearby.");
                    child = parent.obtain(item, 1); return false;
                }
                BlockPos feet = new BlockPos(bot.mc.player);
                ItemStack stack = bot.mc.player.inventory.main[bot.findInventory(item)];
                Block placing = Block.getBlockFromItem(stack.getItem());
                Vec3d eye = new Vec3d(bot.mc.player.x, bot.mc.player.y + bot.mc.player.getEyeHeight(), bot.mc.player.z);
                double reach = bot.mc.interactionManager.getReachDistance() - .1;
                // canBlockBePlaced handles air/replaceable materials; Block.isReplaceable
                // itself returns false for ordinary air in 1.8.
                // Adjacent squares and different ground heights are valid too. Aim at the
                // support's top face: a ray toward its center often hits a side instead.
                for (int r = 1; r <= 4; r++) for (int dy : new int[]{0, -1, 1, -2, 2, -3})
                    for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos place = new BlockPos(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if(blockName.equals("furnace") && place.getY()<SurfaceAccessTask.terrainSurface(bot,place.getX(),place.getZ())) continue;
                    if (rejectedPlaces.contains(place) || bot.interactiveSupport(place.down()) || !bot.mc.world.isLoaded(place, false)
                            || !bot.navigation.clear(place.getX(), place.getY(), place.getZ())
                            || !bot.navigation.floor(place.getX(), place.getY() - 1, place.getZ())
                            || !bot.mc.world.canBlockBePlaced(placing, place, false, Direction.UP, null, stack)) continue;
                    Vec3d aim = new Vec3d(place.getX() + .5, place.getY() - .001, place.getZ() + .5);
                    double ex = eye.x - aim.x, ey = eye.y - aim.y, ez = eye.z - aim.z;
                    if (ex * ex + ey * ey + ez * ez > reach * reach) continue;
                    BlockHitResult hit = bot.mc.world.rayTrace(eye, aim);
                    // Tall grass and flowers may intercept the ray; Minecraft replaces
                    // them directly when that replaceable block is clicked.
                    if (hit == null || !(place.equals(hit.getBlockPos())
                            || place.down().equals(hit.getBlockPos()) && hit.direction == Direction.UP)) continue;
                    bot.releaseMovement(); bot.equip(bot.findInventory(item));
                    bot.look(hit.pos.x, hit.pos.y, hit.pos.z);
                    bot.mc.interactionManager.onRightClick(bot.mc.player, bot.mc.world, bot.mc.player.inventory.getMainHandStack(),
                            hit.getBlockPos(), hit.direction, hit.pos);
                    pos = place; waitUntil = bot.ticks + 8;
                    if (++retries > 12) throw new TaskFailure("Could not place " + blockName + ".");
                    return false;
                }
                // A shaft/pillar lip may have no supporting square in reach.
                // Walk to an actual workstation footprint before excavating.
                if(placementApproaches.size()<4) {
                    PathSearch.Pos from=bot.navigation.feet();
                    List<PathSearch.Pos> approaches=new ArrayList<>();
                    for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++) for(int dy=-4;dy<=2;dy++) {
                        PathSearch.Pos stand=new PathSearch.Pos(from.x+dx,from.y+dy,from.z+dz);
                        if(stand.equals(from) || placementApproaches.contains(stand)
                                || !PathSearch.standing(bot.navigation,stand.x,stand.y,stand.z)) continue;
                        for(Direction side:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                            BlockPos site=new BlockPos(stand.x,stand.y,stand.z).offset(side);
                            if(rejectedPlaces.contains(site) || bot.interactiveSupport(site.down())
                                    || !bot.navigation.floor(site.getX(),site.getY()-1,site.getZ())
                                    || !bot.mc.world.canBlockBePlaced(placing,site,false,Direction.UP,null,stack)) continue;
                            if(blockName.equals("furnace") && site.getY()<SurfaceAccessTask.terrainSurface(bot,site.getX(),site.getZ())) continue;
                            approaches.add(stand);break;
                        }
                    }
                    approaches.sort(Comparator.comparingDouble(from::distance));
                    for(int i=0;i<Math.min(16,approaches.size());i++) {
                        PathSearch.Pos stand=approaches.get(i);
                        List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,stand,2000);
                        if(route.isEmpty() || !route.get(route.size()-1).equals(stand)) continue;
                        placementApproaches.add(stand);bot.navigation.reset();
                        child=new MovementTasks.GoTo(stand.x,stand.y,stand.z);return false;
                    }
                }
                if (preparedSpaces.size() < 4) {
                    child = WorkstationSpaceTask.plan(bot, preparedSpaces);
                    if (child != null) {
                        bot.say("Clearing a small space for " + blockName + ".");
                        return false;
                    }
                }
                PillarDescentTask descent=PillarDescentTask.plan(bot);
                if(descent!=null) {child=descent;placementApproaches.clear();preparedSpaces.clear();return false;}
                throw new TaskFailure("No reachable safe place for " + blockName + "; could not clear a safe side recess.");
            }
            boolean opened;
            try {opened=bot.interact(pos);}
            catch(TaskFailure blocked) {
                bot.navigation.reset();
                if(pos.getY()<bot.navigation.feet().y-1) {
                    PillarDescentTask descent=PillarDescentTask.plan(bot);
                    if(descent!=null) {child=descent;return false;}
                }
                if(pos.getY()>bot.navigation.feet().y+1) {
                    // A cheap local table is preferable to climbing out of a shaft
                    // solely for crafting, then descending again for the next tool.
                    if(blockName.equals("crafting_table")) {
                        // Never climb toward an elevated/blocked table while
                        // already on the surface. Reject it and let the normal
                        // acquisition path craft or recover a local table;
                        // this avoids the zero-step "mine/pillar up" loop.
                        rejectedPlaces.add(pos);pos=null;return false;
                    }
                    child=new SurfaceAccessTask(Math.min(pos.getY(),bot.navigation.feet().y+8),protectedCobblestone,protectedPlanks);return false;
                }
                if(fixedPosition) {
                    int accessY=Math.max(pos.getY(),bot.navigation.feet().y-24);
                    for(Direction side:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                        BlockPos adjacent=pos.offset(side);
                        StoneAccessTask access=StoneAccessTask.planOre(bot,new BlockPos(adjacent.getX(),accessY,adjacent.getZ()),blockName);
                        if(access!=null) {child=access;return false;}
                    }
                    throw new TaskFailure("No safe access to remembered "+blockName+" at "+pos);
                }
                rejectedPlaces.add(pos);pos=null;return false;
            }
            if (opened) {
                bot.expectedScreen = screen; waitUntil = bot.ticks + 10;
                if (++retries > 12) throw new TaskFailure("Could not open " + blockName + ".");
            }
            return false;
        }
        private boolean isBlock(LegacyBot bot, BlockPos pos) {
            String id = String.valueOf(Block.REGISTRY.getIdentifier(bot.mc.world.getBlockState(pos).getBlock()));
            return id.equals("minecraft:" + blockName) || blockName.equals("furnace") && id.equals("minecraft:lit_furnace");
        }
        public void stop(LegacyBot bot) { if (child != null) child.stop(bot); }
    }

    static final class Craft implements LegacyTask {
        final LegacyItems.Recipe recipe;
        final AcquisitionTask parent;
        LegacyTask child;
        Transfer transfer;
        ScreenHandler handler;
        BlockPos table;
        int ingredient, phase, waitUntil, before;
        int fastStarted,fastExpected;
        java.util.concurrent.Future<Boolean> fastConfirmation;
        Craft(LegacyItems.Recipe recipe, AcquisitionTask parent) { this.recipe = recipe; this.parent = parent; }
        public String describe() { return "craft " + recipe.output + (child == null ? " (phase " + phase + ")" : " -> " + child.describe()); }
        public void stop(LegacyBot bot) { if (child != null) child.stop(bot);if(fastConfirmation!=null&&!fastConfirmation.isDone())fastConfirmation.cancel(false); }
        public boolean tick(LegacyBot bot) {
            if (child != null) { if (child.tick(bot)) {
                if(child instanceof Open)table=((Open)child).pos;
                child.stop(bot); child = null;
            } return false; }
            if(phase==3)return true;
            if (bot.ticks < waitUntil) return false;
            waitUntil = bot.ticks + (bot.tasTravel()?1:2);
            if(phase==4)return confirmFastCraft(bot);
            if (phase == 0) {
                // Leave placed tables available for the next recipe and ingredient trip.
                for (Map.Entry<LegacyItems.Target, Integer> e : AcquisitionTask.requirements(recipe).entrySet()) {
                    if (bot.count(e.getKey()) < e.getValue()+(bot.autoDupeAvailable() && AcquisitionTask.reserveDupeSeed(e.getKey()) ? 1 : 0)) {
                        bot.closeOwnedScreen();
                        child=parent.obtain(e.getKey(),e.getValue());
                        return false;
                    }
                }
                if (recipe.needsTable() && !(bot.mc.player.openScreenHandler instanceof CraftingScreenHandler)) {
                    bot.closeOwnedScreen();
                    Open open=new Open("crafting_table", CraftingScreenHandler.class, parent);
                    int batches=Math.max(1,(Math.max(0,parent.count-bot.count(parent.target))+recipe.count-1)/recipe.count);
                    for(Map.Entry<LegacyItems.Target,Integer> e:AcquisitionTask.requirements(recipe).entrySet()) {
                        String id=LegacyItems.id(e.getKey().item);
                        if(id.equals("cobblestone")) open.protectedCobblestone+=e.getValue()*batches;
                        if(id.equals("planks")) open.protectedPlanks+=e.getValue()*batches;
                    }
                    open.protectedCobblestone=Math.max(open.protectedCobblestone,parent.protectedCobblestone);
                    child=open;return false;
                }
                if (!recipe.needsTable()) bot.closeOwnedScreen();
                handler = bot.mc.player.openScreenHandler;
                if (!(handler instanceof PlayerScreenHandler) && !(handler instanceof CraftingScreenHandler)) throw new TaskFailure("Close the current screen before crafting.");
                if (!clearGrid(bot)) return false;
                before = bot.count(recipe.output); phase = 1;
                if(bot.tasTravel()) {fastCraft(bot);return false;}
            }
            if (handler != bot.mc.player.openScreenHandler) throw new TaskFailure("Crafting screen was closed.");
            if (transfer != null) { if (transfer.tick(bot)) { transfer = null; ingredient++; } return false; }
            if (phase == 1) {
                while (ingredient < recipe.ingredients.length && recipe.ingredients[ingredient] == null) ingredient++;
                if (ingredient < recipe.ingredients.length) {
                    int width = handler instanceof CraftingScreenHandler ? 3 : 2;
                    int gridSlot = 1 + ingredient / recipe.width * width + ingredient % recipe.width;
                    transfer = new Transfer(recipe.ingredients[ingredient], gridSlot, 1); return false;
                }
                ItemStack result = handler.getSlot(0).getStack();
                if (!recipe.output.matches(result)) throw new TaskFailure("Crafting output does not match the requested recipe.");
                if (bot.mc.player.inventory.getEmptySlot() < 0 && bot.count(recipe.output) == 0) throw new TaskFailure("Make room for the crafting result.");
                bot.click(0, 0, 1); phase = 2; waitUntil = bot.ticks + 6; return false;
            }
            if (bot.count(recipe.output) <= before) throw new TaskFailure("Crafting result was not received; inventory may be full.");
            if (!clearGrid(bot)) return false;
            bot.closeOwnedScreen();
            return true;
        }
        private void fastCraft(LegacyBot bot) {
            if(bot.mc.player.inventory.getCursorStack()!=null)throw new TaskFailure("Crafting cursor must be empty.");
            int batches=Math.max(1,(parent.count-before+recipe.count-1)/recipe.count);
            batches=Math.min(64,batches);
            for(Map.Entry<LegacyItems.Target,Integer> e:AcquisitionTask.requirements(recipe).entrySet())
                batches=Math.min(batches,bot.count(e.getKey())/e.getValue());
            if(batches<1)throw new TaskFailure("Ingredients changed before TAS crafting.");
            fastStarted=bot.ticks;fastExpected=before+batches*recipe.count;
            int width=handler instanceof CraftingScreenHandler?3:2;
            for(int i=0;i<recipe.ingredients.length;i++) if(recipe.ingredients[i]!=null) {
                LegacyItems.Target input=recipe.ingredients[i];int slot=1+i/recipe.width*width+i%recipe.width;
                for(int n=0;n<batches;n++) {
                    if(bot.mc.player.inventory.getCursorStack()==null) {
                        int source=bot.findInventory(input);
                        if(source<0)throw new TaskFailure("TAS crafting lost ingredient "+input);
                        bot.click(bot.screenSlot(source),0,0);
                    }
                    if(!input.matches(bot.mc.player.inventory.getCursorStack()))throw new TaskFailure("Unexpected TAS crafting cursor.");
                    bot.click(slot,1,0);
                }
                bot.recoverCursor();
                if(bot.mc.player.inventory.getCursorStack()!=null)throw new TaskFailure("No room to return crafting ingredients.");
            }
            if(!recipe.output.matches(handler.getSlot(0).getStack()))throw new TaskFailure("TAS recipe output did not match.");
            bot.click(0,0,1);phase=4;
        }
        private boolean confirmFastCraft(LegacyBot bot) {
            if(handler!=bot.mc.player.openScreenHandler)throw new TaskFailure("Crafting screen was closed during TAS crafting.");
            if(fastConfirmation!=null && fastConfirmation.isDone()) {
                boolean accepted;
                try {accepted=fastConfirmation.get();}catch(Exception e){throw new TaskFailure("TAS craft verification failed: "+e);}
                fastConfirmation=null;
                if(accepted && bot.count(recipe.output)>=fastExpected && bot.mc.player.inventory.getCursorStack()==null) {
                    int elapsed=bot.ticks-fastStarted;
                    if(elapsed>10)throw new TaskFailure("TAS craft exceeded ten ticks.");
                    bot.tasCrafts++;bot.maxTasCraftTicks=Math.max(bot.maxTasCraftTicks,elapsed);
                    bot.say("TAS_CRAFT: "+recipe.output+" x"+(fastExpected-before)+" confirmed in "+elapsed+" ticks.");
                    bot.closeOwnedScreen();return true;
                }
            }
            if(bot.ticks-fastStarted>=10)throw new TaskFailure("TAS crafting was not confirmed by the server within ten ticks.");
            if(fastConfirmation==null) {
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
                final String name=bot.mc.player.getGameProfile().getName();final int syncId=handler.syncId;
                fastConfirmation=server.method_10815(()-> {
                    net.minecraft.entity.player.ServerPlayerEntity player=server.getPlayerManager().getPlayer(name);
                    if(player==null || player.openScreenHandler.syncId!=syncId || player.inventory.getCursorStack()!=null)return false;
                    int count=0;for(ItemStack s:player.inventory.main)if(recipe.output.matches(s))count+=s.count;
                    for(int i=1;i<=(recipe.needsTable()?9:4);i++)if(player.openScreenHandler.getSlot(i).getStack()!=null)return false;
                    return count>=fastExpected;
                });
            }
            return false;
        }
        private boolean clearGrid(LegacyBot bot) {
            int slots = handler instanceof CraftingScreenHandler ? 9 : 4;
            for (int i = 1; i <= slots; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack != null) {
                    int count = stack.count; bot.click(i, 0, 1);
                    ItemStack after = handler.getSlot(i).getStack();
                    if (after != null && after.count == count) throw new TaskFailure("Make inventory space to clear the crafting grid.");
                    return false;
                }
            }
            return true;
        }
    }

    /** Carry the same table into mines and along the route instead of recrafting it. */
    static final class RecoverTable implements LegacyTask {
        final BlockPos pos;
        final AcquisitionTask pickup=new AcquisitionTask(LegacyItems.parse("crafting_table"),1,"get");
        int ticks;
        RecoverTable(BlockPos pos){this.pos=pos;}
        public String describe(){return "pack crafting table";}
        public void stop(LegacyBot bot){bot.cancelMining();bot.releaseMovement();pickup.stop(bot);}
        public boolean tick(LegacyBot bot){
            bot.closeOwnedScreen();
            if(bot.count(LegacyItems.parse("crafting_table"))>0)return true;
            if(++ticks>400)throw new TaskFailure("Could not recover the crafting table at "+pos);
            if(bot.mc.world.getBlockState(pos).getBlock()==Block.get("crafting_table")){bot.mine(pos);return false;}
            bot.cancelMining();pickup.pickup(bot);return false;
        }
    }

    static final class Smelt implements LegacyTask {
        final LegacyItems.Target output, input;
        final int target;
        final AcquisitionTask parent;
        LegacyTask child;
        Transfer transfer;
        int waitUntil, timeout, lastCount = -1;
        Smelt(LegacyItems.Target output, int target, LegacyItems.Target input, AcquisitionTask parent) {
            this.output = output; this.target = target; this.input = input; this.parent = parent;
        }
        public String describe() { return "smelt " + output + " x" + target + (child == null ? "" : " -> " + child.describe()); }
        public void stop(LegacyBot bot) { if (child != null) child.stop(bot); }
        public boolean tick(LegacyBot bot) {
            if (child != null) { if (child.tick(bot)) { child.stop(bot); child = null; } return false; }
            if (bot.ticks < waitUntil) return false;
            waitUntil = bot.ticks + 2;
            if (transfer != null) { if (transfer.tick(bot)) transfer = null; return false; }
            if (bot.count(output) >= target) { bot.closeOwnedScreen(); return true; }
            if (!(bot.mc.player.openScreenHandler instanceof FurnaceScreenHandler)) {
                child = new Open("furnace", FurnaceScreenHandler.class, parent); return false;
            }
            ScreenHandler h = bot.mc.player.openScreenHandler;
            if (h.getSlot(0).getStack() != null && !input.matches(h.getSlot(0).getStack())) throw new TaskFailure("Furnace contains another input; choose an empty furnace.");
            if (h.getSlot(2).getStack() != null && !output.matches(h.getSlot(2).getStack())) throw new TaskFailure("Furnace contains another output; choose an empty furnace.");
            if (h.getSlot(2).getStack() != null) { bot.click(2, 0, 1); return false; }
            int need = Math.min(16, target - bot.count(output));
            int existing = h.getSlot(0).getStack() == null ? 0 : h.getSlot(0).getStack().count;
            if (existing < need && bot.count(input) > 0) {
                transfer = new Transfer(input, 0, Math.min(need - existing, bot.count(input))); return false;
            }
            if (existing == 0) { bot.closeOwnedScreen(); child = parent.obtain(input, need); return false; }
            if (h.getSlot(1).getStack() == null) {
                LegacyItems.Target fuel = null;
                for (String name : new String[]{"coal", "planks", "log", "log2", "stick"}) {
                    LegacyItems.Target candidate = LegacyItems.parse(name);
                    if (candidate.item != input.item && bot.count(candidate) > 0) { fuel = candidate; break; }
                }
                if (fuel == null) {
                    bot.closeOwnedScreen(); child = parent.obtain(LegacyItems.parse("planks"), Math.max(2, (need * 2 + 2) / 3)); return false;
                }
                int burn = FurnaceBlockEntity.getBurnTime(fuel.stack());
                transfer = new Transfer(fuel, 1, Math.min(bot.count(fuel), Math.max(1, (need * 200 + burn - 1) / burn))); return false;
            }
            int have = bot.count(output);
            timeout = lastCount == have ? timeout + 2 : 0; lastCount = have;
            if (timeout > 1000) throw new TaskFailure("Furnace stopped making progress.");
            return false;
        }
    }
}
