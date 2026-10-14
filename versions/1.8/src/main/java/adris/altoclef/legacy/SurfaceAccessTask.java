package adris.altoclef.legacy;

import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.*;
import java.util.*;

/** Clears the shaft overhead, then jump-places a solid block underneath to climb. */
final class SurfaceAccessTask implements LegacyTask {
    private final Integer targetY;
    private BlockPos base;
    private int ticks, jumpTicks, retries, waitForDrops, steps, centerTicks;
    private boolean announced, placed, checkedWalkingExit;
    private PathSearch.Pos walkingExit;
    private int walkingTicks;
    private int shaftRelocations;
    private List<PathSearch.Pos> relocationRoute;
    private int relocationStep;
    private LegacyTask collect;
    private final int reservedCobblestone, reservedPlanks;
    private final boolean pillarOnly;
    private boolean masonry;
    SurfaceAccessTask allowMasonry() {masonry=true;return this;}
    private int refillTop;
    private int reserved(String name) {
        return name.equals("cobblestone") ? reservedCobblestone : name.equals("planks") ? reservedPlanks : 0;
    }
    SurfaceAccessTask() {this(null,0,0,false);}
    SurfaceAccessTask(int targetY) {this(targetY,0,0,false);}
    SurfaceAccessTask(int targetY,boolean pillarOnly) {this(targetY,0,0,pillarOnly);}
    SurfaceAccessTask(Integer targetY,int cobblestone,int planks) {
        this(targetY,cobblestone,planks,false);
    }
    private SurfaceAccessTask(Integer targetY,int cobblestone,int planks,boolean pillarOnly) {
        this.targetY=targetY;reservedCobblestone=cobblestone;reservedPlanks=planks;this.pillarOnly=pillarOnly;
        checkedWalkingExit=pillarOnly;
    }
    public String describe() { return walkingExit!=null ? "walk out to surface" : "mine/pillar up ("+steps+" blocks)"+(collect==null ? "" : " -> "+collect.describe()); }
    public void stop(LegacyBot bot) {
        if(collect!=null) collect.stop(bot);
        bot.cancelMining(); bot.releaseMovement(); bot.navigation.reset();
    }
    /** Ignore tree canopies, but never accept a roofed mine as a workstation site. */
    static int terrainSurface(LegacyBot bot,int x,int z) {
        for(int y=255;y>=0;y--) {
            BlockPos p=new BlockPos(x,y,z);
            if(!bot.mc.world.isLoaded(p,false)) return -1;
            Block b=bot.mc.world.getBlockState(p).getBlock();
            String id=String.valueOf(Block.REGISTRY.getIdentifier(b));
            if(id.contains("leaves") || id.equals("minecraft:log") || id.equals("minecraft:log2")) continue;
            if(b.getMaterial().isFluid() || b.getCollisionBox(bot.mc.world,p,bot.mc.world.getBlockState(p))!=null) return y+1;
        }
        return -1;
    }
    static boolean underground(LegacyBot bot) {
        if(!bot.mc.player.onGround) return false;
        PathSearch.Pos feet=bot.navigation.feet();
        // onGround can remain true while the body overlaps the last block but the
        // center has already crossed its edge. That unsupported cell is not a shaft.
        if(!PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z)) return false;
        return !atSurface(bot,feet);
    }
    private static boolean atSurface(LegacyBot bot, PathSearch.Pos feet) {
        if(!PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z)) return false;
        int current=terrainSurface(bot,feet.x,feet.z);
        if(current<0 || current>feet.y) return false;
        // A supported, open one-block step is ordinary walking terrain even
        // when higher hills surround the area farther away.
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            int x=feet.x+d[0],z=feet.z+d[1];
            int top=terrainSurface(bot,x,z);
            if(top>=feet.y && top<=feet.y+1
                    && PathSearch.standing(bot.navigation,x,top,z)
                    && bot.navigation.clear(feet.x,feet.y+2,feet.z)) return true;
        }
        // Seeing leaves above a mined shaft does not mean the player has
        // reached ground level. Compare surrounding terrain, ignoring canopy.
        int lowest=255;
        for(int[] d:new int[][]{{4,0},{-4,0},{0,4},{0,-4}}) {
            int top=terrainSurface(bot,feet.x+d[0],feet.z+d[1]);
            if(top<0) return false;
            lowest=Math.min(lowest,top);
        }
        return lowest<=feet.y+2 && SurfaceExitPolicy.exposed(bot.navigation,feet);
    }
    private boolean diggable(LegacyBot bot, BlockPos p) {
        if(bot.protectsTowerBase(p) || !bot.mc.world.isLoaded(p,false)) return false;
        Block b=bot.mc.world.getBlockState(p).getBlock();
        if(bot.navigation.clear(p.getX(),p.getY(),p.getZ())) return true;
        String id=String.valueOf(Block.REGISTRY.getIdentifier(b));
        if(!Arrays.asList("minecraft:stone","minecraft:end_stone","minecraft:cobblestone","minecraft:dirt","minecraft:grass",
                "minecraft:sandstone","minecraft:red_sandstone","minecraft:hardened_clay","minecraft:stained_hardened_clay","minecraft:crafting_table",
                "minecraft:log","minecraft:log2","minecraft:leaves","minecraft:leaves2","minecraft:coal_ore","minecraft:iron_ore").contains(id)
                && !(bot.strongholdLoot() && Arrays.asList("minecraft:stonebrick","minecraft:bookshelf","minecraft:planks","minecraft:web").contains(id))
                && !(masonry && id.equals("minecraft:stonebrick"))) return false;
        if(b.getMaterial()==net.minecraft.block.material.Material.STONE) {
            boolean tool=false;
            for(ItemStack s:bot.mc.player.inventory.main)
                if(s!=null && s.isEffectiveOn(b) && (!s.isDamageable() || s.getDamage()<s.getMaxDamage()-1)) tool=true;
            if(!tool) return false;
        }
        for(Direction d:Direction.values()) {
            BlockPos adjacent=p.offset(d);
            if(!bot.mc.world.isLoaded(adjacent,false) || bot.mc.world.getBlockState(adjacent).getBlock().getMaterial().isFluid()) return false;
        }
        Block ceiling=bot.mc.world.getBlockState(p.up()).getBlock();
        return !(ceiling instanceof FallingBlock);
    }
    private int buildingBlock(LegacyBot bot) {
        for(String name:new String[]{"leaves","leaves2","dirt","sandstone","red_sandstone","hardened_clay","stained_hardened_clay","stone","netherrack","cobblestone","planks"}) {
            LegacyItems.Target material=LegacyItems.parse(name);
            if(bot.count(material)<=reserved(name)) continue;
            int slot=bot.findInventory(material);
            if(slot>=0) return slot;
        }
        return -1;
    }
    private int availableBlocks(LegacyBot bot) {
        int result=0;
        for(String name:new String[]{"leaves","leaves2","dirt","sandstone","red_sandstone","hardened_clay","stained_hardened_clay","stone","netherrack","cobblestone","planks"})
            result+=Math.max(0,bot.count(LegacyItems.parse(name))-reserved(name));
        return result;
    }
    private void refill(LegacyBot bot,int needed) {
        int plankTarget=reservedPlanks+needed;
        if(bot.count(LegacyItems.parse("planks"))+4*bot.count(LegacyItems.anyLogs())>=plankTarget) {
            collect=new AcquisitionTask(LegacyItems.parse("planks"),plankTarget,"craft");
        } else collect=new LocalClimbingBlocks(needed);
    }
    /** Excavates a side recess and walks into it at the SAME height to collect
     * the drops. Never starts resource exploration or digs another shaft. */
    private final class LocalClimbingBlocks implements LegacyTask {
        private final int needed;
        private final Set<BlockPos> attempted=new HashSet<>();
        private WorkstationSpaceTask dig;
        private BlockPos pickup;
        private int waited,ticks;
        LocalClimbingBlocks(int needed) {this.needed=needed;}
        public String describe() {return "mine nearby wall for "+needed+" climbing blocks";}
        public void stop(LegacyBot bot) {if(dig!=null) dig.stop(bot);bot.releaseMovement();bot.cancelMining();}
        public boolean tick(LegacyBot bot) {
            if(++ticks>1200) throw new TaskFailure("Could not collect climbing blocks without descending.");
            if(availableBlocks(bot)>=needed) {stop(bot);return true;}
            if(dig!=null) {
                if(dig.tick(bot)) {dig.stop(bot);pickup=dig.base;dig=null;bot.navigation.reset();}
                return false;
            }
            if(pickup!=null) {
                if(bot.navigation.move(new PathSearch.Pos(pickup.getX(),pickup.getY(),pickup.getZ()),.15)) {
                    if(++waited>=12) {pickup=null;waited=0;}
                }
                return false;
            }
            dig=WorkstationSpaceTask.plan(bot,attempted);
            if(dig==null) throw new TaskFailure("No safe nearby wall material for climbing; refusing another downward shaft.");
            return false;
        }
    }
    private PathSearch.Pos walkingExit(LegacyBot bot,PathSearch.Pos from) {
        List<PathSearch.Pos> candidates=new ArrayList<>();
        for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) for(int dy=-2;dy<=6;dy++) {
            PathSearch.Pos p=new PathSearch.Pos(from.x+dx,from.y+dy,from.z+dz);
            if(targetY!=null && p.y<targetY || p.equals(from) || !PathSearch.standing(bot.navigation,p.x,p.y,p.z)) continue;
            if(atSurface(bot,p)) candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(from::distance));
        for(int i=0;i<Math.min(24,candidates.size());i++) {
            PathSearch.Pos p=candidates.get(i);
            List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,p,2000);
            if(!route.isEmpty() && route.get(route.size()-1).equals(p)) return p;
        }
        return null;
    }
    private PathSearch.Pos safeAscentColumn(LegacyBot bot,PathSearch.Pos from) {
        int searches=0;
        for(int radius=1;radius<=12;radius++) for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
            for(int dy:new int[]{0,1,-1,2,-2}) {
                PathSearch.Pos p=new PathSearch.Pos(from.x+dx,from.y+dy,from.z+dz);
                // In a one-wide deep shaft, alternative columns begin inside
                // stone. Permit a short safe side recess before moving across.
                if(!bot.navigation.floor(p.x,p.y-1,p.z)
                        || !(bot.navigation.clear(p.x,p.y,p.z) || diggable(bot,new BlockPos(p.x,p.y,p.z)))
                        || !(bot.navigation.clear(p.x,p.y+1,p.z) || diggable(bot,new BlockPos(p.x,p.y+1,p.z)))) continue;
                boolean safe=false;
                // Deep rooms may be more than 24 blocks below daylight. Validate
                // the whole column instead of rejecting every alternative shaft.
                int surface=terrainSurface(bot,p.x,p.z);
                for(int y=p.y+2;y<=Math.min(253,Math.max(p.y+2,surface+1));y++) {
                    BlockPos overhead=new BlockPos(p.x,y,p.z);
                    if(bot.navigation.clear(p.x,y,p.z) && bot.mc.world.hasDirectSunlight(overhead)) {safe=true;break;}
                    if(!diggable(bot,overhead)) break;
                }
                if(!safe) continue;
                List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,p,1200);
                if(!route.isEmpty() && route.get(route.size()-1).equals(p)) return p;
                PathSearch.Terrain excavatable=new PathSearch.Terrain() {
                    public boolean clear(int x,int y,int z) {return bot.navigation.clear(x,y,z) || diggable(bot,new BlockPos(x,y,z));}
                    public boolean floor(int x,int y,int z) {return bot.navigation.floor(x,y,z);}
                };
                route=PathSearch.find(excavatable,from,p,1200);
                if(!route.isEmpty() && route.size()<=24 && route.get(route.size()-1).equals(p)) {
                    relocationRoute=new ArrayList<>();PathSearch.Pos previous=from;
                    for(PathSearch.Pos step:route) {
                        // Clear cardinal corridors before following a virtual diagonal.
                        if(step.x!=previous.x && step.z!=previous.z) relocationRoute.add(new PathSearch.Pos(step.x,previous.y,previous.z));
                        relocationRoute.add(step);previous=step;
                    }
                    relocationStep=0;return p;
                }
                if(++searches>=12) return null;
            }
        }
        return null;
    }
    public boolean tick(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        if(++ticks>12000 || steps>=192) throw new TaskFailure("Could not finish vertical ascent within the limit.");
        if(collect!=null) {
            if(collect.tick(bot)) {
                collect.stop(bot);collect=null;waitForDrops=0;bot.navigation.reset();
                int needed=Math.max(0,refillTop-bot.navigation.feet().y);
                if(availableBlocks(bot)<needed) refill(bot,needed);
            }
            return false;
        }
        if(relocationRoute!=null) {
            if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
            if(relocationStep>=relocationRoute.size()) {relocationRoute=null;walkingExit=null;bot.navigation.reset();return false;}
            PathSearch.Pos next=relocationRoute.get(relocationStep);
            List<BlockPos> clear=new ArrayList<>();
            if(next.y>feet.y) clear.add(new BlockPos(feet.x,feet.y+2,feet.z));
            for(int y=Math.max(feet.y+1,next.y+1);y>=next.y;y--) clear.add(new BlockPos(next.x,y,next.z));
            for(BlockPos obstruction:clear) {
                if(bot.navigation.clear(obstruction.getX(),obstruction.getY(),obstruction.getZ())) continue;
                if(!diggable(bot,obstruction) || bot.hit(obstruction)==null) throw new TaskFailure("Cannot safely clear the alternate ascent route.");
                bot.mine(obstruction);return false;
            }
            bot.cancelMining();
            if(bot.navigation.move(next,.2)) relocationStep++;
            return false;
        }
        if(base!=null) {
            // The server may confirm placement on the tick after the click.
            if(!placed && bot.mc.player.y>=base.getY()+1
                    && bot.navigation.floor(base.getX(),base.getY(),base.getZ())) placed=true;
            // 1.8 occasionally leaves onGround false after a jump-place even
            // though the player has settled exactly on the new full block.
            // Accept that stable collision after a short grace period so the
            // climber does not hold itself forever at a one-block ledge.
            boolean settledOnPlaced=placed && feet.y>=base.getY()+1
                    && bot.navigation.floor(feet.x,feet.y-1,feet.z)
                    && Math.abs(bot.mc.player.y-feet.y)<.035 && jumpTicks>8;
            if(placed && (bot.mc.player.onGround || settledOnPlaced) && feet.y>=base.getY()+1) {
                bot.releaseMovement();base=null;steps++;retries=jumpTicks=waitForDrops=centerTicks=0;return false;
            }
            if(++jumpTicks>30) {
                bot.say("Ascent retry at "+base+" player="+bot.mc.player.x+","+bot.mc.player.y+","+bot.mc.player.z
                        +" placed="+placed+" baseAir="+bot.mc.world.isAir(base));
                bot.releaseMovement();base=null;jumpTicks=0;
                if(++retries>3) throw new TaskFailure("Block placement beneath the player was rejected.");
                return false;
            }
            bot.key(bot.mc.options.jumpKey,!placed);
            if(!placed && bot.mc.player.y>base.getY()+1.01 && bot.mc.world.isAir(base)) {
                int slot=buildingBlock(bot);
                if(slot<0) throw new TaskFailure("No building block left for vertical ascent.");
                ItemStack stack=bot.mc.player.inventory.main[slot];
                Block block=Block.getBlockFromItem(stack.getItem());
                if(!bot.mc.world.canBlockBePlaced(block,base,false,Direction.UP,null,stack)) return false;
                BlockPos support=base.down();
                net.minecraft.util.hit.BlockHitResult hit=bot.hit(support);
                if(hit==null || hit.direction!=Direction.UP) return false;
                bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
                bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),support,Direction.UP,hit.pos);
                placed=!bot.mc.world.isAir(base);
                if(placed) bot.key(bot.mc.options.jumpKey,false);
            }
            return false;
        }
        if(walkingExit!=null) {
            if(bot.mc.player.onGround && (targetY==null ? atSurface(bot,feet) : feet.y>=targetY)) {stop(bot);return true;}
            if(++walkingTicks>160) {walkingExit=null;bot.navigation.reset();bot.releaseMovement();}
            else {
                try {if(bot.navigation.move(walkingExit,.3)) walkingExit=null;}
                catch(TaskFailure blocked) {walkingExit=null;bot.navigation.reset();}
                return false;
            }
        }
        if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
        if(targetY==null ? atSurface(bot,feet) : feet.y>=targetY) {stop(bot);return true;}
        if(!checkedWalkingExit) {
            checkedWalkingExit=true;walkingExit=walkingExit(bot,feet);bot.navigation.reset();
            if(walkingExit!=null) return false;
        }
        if(!announced) {bot.say("Mining upward and placing blocks underneath to climb.");announced=true;}
        if(feet.y>=253) throw new TaskFailure("Cannot pillar above build height.");
        double dx=feet.x+.5-bot.mc.player.x, dz=feet.z+.5-bot.mc.player.z;
        // A jump-place only needs the player's hitbox safely over the support.
        // Requiring the exact block center made crouched movement oscillate at
        // scaffold edges and the jump phase never began.
        if(dx*dx+dz*dz>.01) {
            centerTicks=0;bot.releaseMovement();
            bot.look(feet.x+.5,bot.mc.player.y+bot.mc.player.getEyeHeight(),feet.z+.5);
            bot.key(bot.mc.options.forwardKey,true);bot.key(bot.mc.options.sneakKey,true);
            return false;
        }
        bot.releaseMovement();
        if(++centerTicks<2) return false;
        // Jumping needs one extra block of clearance above normal standing headroom.
        for(int dy:new int[]{2,3}) {
            BlockPos p=new BlockPos(feet.x,feet.y+dy,feet.z);
            if(bot.navigation.clear(p.getX(),p.getY(),p.getZ())) continue;
            if(!diggable(bot,p) || bot.hit(p)==null) {
                if(shaftRelocations<3) {
                    walkingExit=safeAscentColumn(bot,feet);walkingTicks=0;
                    if(walkingExit!=null) {
                        shaftRelocations++;bot.cancelMining();bot.navigation.reset();
                        bot.say("Moving through the open tunnel to a safe ascent column.");return false;
                    }
                }
                throw new TaskFailure("Cannot safely mine overhead for vertical ascent at "+p+" ("+Block.REGISTRY.getIdentifier(bot.mc.world.getBlockState(p).getBlock())+").");
            }
            bot.mine(p);waitForDrops=0;return false;
        }
        bot.cancelMining();
        if(buildingBlock(bot)<0) {
            bot.releaseMovement();
            if(++waitForDrops<10) return false;
            // Gather only the remaining climb, on this level. Keep the recipe
            // stack intact and never descend to refill climbing material.
            refillTop=targetY==null ? feet.y+4 : targetY;
            refill(bot,Math.max(1,refillTop-feet.y));
            return false;
        }
        base=new BlockPos(feet.x,feet.y,feet.z);placed=false;jumpTicks=0;
        bot.equip(buildingBlock(bot));
        // Start crouching before takeoff so both client and server see it before
        // the placement click. Otherwise a furnace/table support opens its GUI.
        bot.key(bot.mc.options.sneakKey,true);bot.key(bot.mc.options.jumpKey,true);
        return false;
    }
}
