package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;

/** 1.8 adaptation of OrderedEntryPreparationTask and ConstructNetherPortalBucketTask. */
final class LegacyOverworldTask implements LegacyTask {
    private final boolean preparationOnly;
    private final LegacyPortalPlan plan=new LegacyPortalPlan();
    private boolean preparationStarted;
    LegacyOverworldTask() {this(false);}
    LegacyOverworldTask(boolean preparationOnly) {this.preparationOnly=preparationOnly;}
    static LegacyOverworldTask afterPreparation() {
        LegacyOverworldTask task=new LegacyOverworldTask();
        task.preparationStarted=true;task.stage=1;task.state="find water, a lava pool and a casting worksite";
        return task;
    }
    // Cast from supported cells toward unsupported cells. Bottom first gives the
    // side columns a base; each completed obsidian block supports the next one.
    // The bottom two blocks sit in the one-deep trench. This is why the visible
    // cobblestone mold is only three blocks tall while the portal is five tall.
    // Coordinates are (sideways, vertical, along the trench). The final two
    // cells are cast far-to-near so the first top obsidian supports the second.
    private static final int[][] FRAME={
            // Bottom, right to left.
            {0,-2,2},{0,-2,1},
            // Right upright, then left upright; both grow bottom-up.
            {0,-1,3},{0,0,3},{0,1,3},{0,-1,0},{0,0,0},{0,1,0},
            // Top, right to left.
            {0,2,2},{0,2,1}
    };
    private static final int[][] TRENCH={{0,-1,0},{0,-1,1},{0,-1,2},{0,-1,3},{0,-2,1},{0,-2,2}};
    private LegacyTask child;
    private int stage,frame,cast,waitUntil,ticks,actionDeadline;
    private BlockPos origin,portal,lavaPool;
    private BlockPos waterSource;
    private int moldCursor,moldCleared,trenchCleared;
    private java.util.List<BlockPos> moldBlocks;
    private boolean moldBuilt;
    private BlockPos openingExitTarget;
    private PathSearch.Pos openingExit;
    private boolean returnToWorksite;
    private boolean waterStarted,waterRecovered;
    private PathSearch.Pos refillExit;
    private int refillWait;
    private int castRetries,returnClimbs;
    private String state="prepare stone tools, wood, food and iron kit";
    /** A stable approach position outside the frame opening. */
    private PathSearch.Pos workStand() {
        // Mold construction may leave a temporary pillar at the first stance.
        // Use a separate clear floor position for water/lava casting trips.
        return new PathSearch.Pos(origin.getX()-2,origin.getY(),origin.getZ()+1);
    }
    public String describe() {return "overworld -> "+state+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();bot.cancelMining();}
    private void get(String item,int count) {child=new AcquisitionTask(LegacyItems.parse(item),count,"get");}
    private static boolean block(LegacyBot bot,BlockPos pos,String id) {
        return String.valueOf(Block.REGISTRY.getIdentifier(bot.mc.world.getBlockState(pos).getBlock())).equals("minecraft:"+id);
    }
    private boolean descendForRefill(LegacyBot bot) {
        if(bot.mc.player.y<=origin.getY()+.2 && bot.mc.player.onGround) {refillExit=null;refillWait=0;return true;}
        if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
        PathSearch.Pos feet=bot.navigation.feet();
        // Step straight off a short construction pillar. A* intentionally
        // dislikes starting on isolated scaffolds, but this is a verified
        // one-block drop onto the clear worksite selected by site().
        if(feet.y>origin.getY() && feet.y<=origin.getY()+3) {
            PathSearch.Pos landing=null;
            for(int radius=1;radius<=2 && landing==null;radius++)
                for(int dx=-radius;dx<=radius && landing==null;dx++) for(int dz=-radius;dz<=radius;dz++) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
                    PathSearch.Pos p=new PathSearch.Pos(feet.x+dx,origin.getY(),feet.z+dz);
                    if(PathSearch.standing(bot.navigation,p.x,p.y,p.z)) {landing=p;break;}
                }
            if(landing==null) throw new TaskFailure("No safe ground beside the portal scaffold.");
            bot.releaseMovement();bot.look(landing.x+.5,origin.getY()+1,landing.z+.5);
            bot.key(bot.mc.options.forwardKey,true);bot.key(bot.mc.options.sneakKey,false);return false;
        }
        if(refillExit==null) {
            java.util.List<PathSearch.Pos> exits=new java.util.ArrayList<>();
            for(int dx=-5;dx<=5;dx++) for(int dz=-5;dz<=5;dz++)
                if(PathSearch.standing(bot.navigation,feet.x+dx,origin.getY(),feet.z+dz))
                    exits.add(new PathSearch.Pos(feet.x+dx,origin.getY(),feet.z+dz));
            exits.sort(java.util.Comparator.comparingDouble(p->p.distance(feet)));
            for(int i=0;i<Math.min(8,exits.size());i++) {
                PathSearch.Pos exit=exits.get(i);
                java.util.List<PathSearch.Pos> path=PathSearch.find(bot.navigation,feet,exit,2000);
                if(!path.isEmpty() && path.get(path.size()-1).equals(exit)) {refillExit=exit;break;}
            }
        }
        if(refillExit!=null) {
            try {bot.navigation.move(refillExit,.3);return false;}
            catch(TaskFailure blocked) {refillExit=null;bot.navigation.reset();}
        }
        child=StoneAccessTask.plan(bot);
        if(child==null) {
            // Cooling water may still be draining after its source was recovered.
            if(refillWait==0) refillWait=bot.ticks;
            if(bot.ticks-refillWait>400) throw new TaskFailure("Cannot safely leave the casting scaffold for a bucket refill.");
            bot.releaseMovement();bot.key(bot.mc.options.sneakKey,true);waitUntil=bot.ticks+10;
        } else refillWait=0;
        return false;
    }
    static BlockPos findSite(LegacyBot bot,BlockPos lavaPool) {
        PathSearch.Pos feet=bot.navigation.feet();
        java.util.List<BlockPos> candidates=new java.util.ArrayList<>();
        for(int dx=-20;dx<=20;dx++) for(int dz=-20;dz<=20;dz++) {
            int x=lavaPool.getX()+dx,z=lavaPool.getZ()+dz;
            int y=SurfaceAccessTask.terrainSurface(bot,x,z);
            if(y>=1 && Math.abs(y-lavaPool.getY())<=5) candidates.add(new BlockPos(x,y,z));
        }
        // Refilling is repeated ten times: minimize that trip, not distance from
        // the player's incidental position after collecting the first bucket.
        final BlockPos pool=lavaPool;
        candidates.sort(java.util.Comparator.comparingDouble(p -> {
            double dx=p.getX()+.5-pool.getX(),dz=p.getZ()-1-pool.getZ();
            return dx*dx+dz*dz+.05*(Math.abs(p.getX()-feet.x)+Math.abs(p.getZ()-feet.z));
        }));
        java.util.List<BlockPos> poolSources=new java.util.ArrayList<>();
        for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++) for(int dy=-1;dy<=1;dy++) {
            BlockPos source=pool.add(dx,dy,dz);
            if(bot.mc.world.isLoaded(source,false) && LegacyFluidTask.source(bot.mc.world.getBlockState(source),"lava")) poolSources.add(source);
        }
        int routeChecks=0;
        for(BlockPos base:candidates) {
            // Falling mold water may spread seven horizontal blocks on flat
            // ground. Keep its landing column outside that range of every
            // source in the connected pool or early casts can consume the
            // lava needed for the rest of the frame.
            BlockPos waterColumn=base.add(1,0,2);int poolDistance=Integer.MAX_VALUE;
            for(BlockPos source:poolSources)
                poolDistance=Math.min(poolDistance,Math.abs(waterColumn.getX()-source.getX())+Math.abs(waterColumn.getZ()-source.getZ()));
            if(poolDistance<=7) continue;
            boolean clear=true;
            for(int x=-2;x<=3 && clear;x++) for(int z=-5;z<=2 && clear;z++) {
                if(!bot.navigation.floor(base.getX()+x,base.getY()-1,base.getZ()+z)) {clear=false;break;}
                for(int y=0;y<=6;y++) if(!bot.navigation.clear(base.getX()+x,base.getY()+y,base.getZ()+z)) {clear=false;break;}
            }
            PathSearch.Pos stand=new PathSearch.Pos(base.getX()-2,base.getY(),base.getZ());
            if(clear && PathSearch.standing(bot.navigation,stand.x,stand.y,stand.z)) {
                if(++routeChecks>16) return null;
                java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,stand,6000);
                if(!route.isEmpty() && route.get(route.size()-1).equals(stand)) return base;
            }
        }
        return null;
    }
    private boolean resumeMold(LegacyBot bot) {
        java.util.Set<BlockPos> rejected=new java.util.HashSet<>();
        for(int attempt=0;attempt<12;attempt++) {
            BlockPos bottom=bot.nearestBlock(state->String.valueOf(Block.REGISTRY.getIdentifier(state.getBlock())).equals("minecraft:obsidian"),24,rejected);
            if(bottom==null) return false;
            rejected.add(bottom);
            for(int z:new int[]{1,2}) {
                BlockPos base=bottom.add(0,2,-z);
                BlockPos water=base.add(1,2,2);
                if(!LegacyFluidTask.source(bot.mc.world.getBlockState(water),"water")) continue;
                boolean matches=true;
                for(int[] d:new int[][]{{1,0,0},{1,0,1},{1,0,3},{1,1,3},{1,2,3},{0,2,3}}) {
                    BlockPos p=base.add(d[0],d[1],d[2]);
                    if(!block(bot,p,"cobblestone")) {matches=false;break;}
                }
                if(!matches) continue;
                BlockPos pool=bot.nearestBlock(state->LegacyFluidTask.source(state,"lava"),32);
                if(pool==null) continue;
                origin=base;lavaPool=pool;waterSource=water;waterStarted=true;moldBuilt=true;
                moldCleared=FRAME.length;trenchCleared=TRENCH.length;frame=0;
                while(frame<FRAME.length && block(bot,origin.add(FRAME[frame][0],FRAME[frame][1],FRAME[frame][2]),"obsidian")) frame++;
                stage=2;returnToWorksite=true;
                bot.say("Continuing the existing portal mold with "+frame+" confirmed frame blocks.");return true;
            }
        }
        return false;
    }
    private static BlockHitResult face(LegacyBot bot,BlockPos support,Direction face,double height) {
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        return faceFrom(bot,eye,support,face,height);
    }
    private static BlockHitResult faceFrom(LegacyBot bot,Vec3d eye,BlockPos support,Direction face,double height) {
        Vec3d aim=new Vec3d(support.getX()+.5+face.getOffsetX()*.499,
                support.getY()+height+face.getOffsetY()*.499,support.getZ()+.5+face.getOffsetZ()*.499);
        double dx=eye.x-aim.x,dy=eye.y-aim.y,dz=eye.z-aim.z;
        if(dx*dx+dy*dy+dz*dz>20.25) return null;
        BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
        // Flowing mold water can sit between the player and the solid support
        // face. Buckets are still allowed to interact with that support, so
        // preserve the intended face when the only obstruction is a fluid.
        if(hit!=null && hit.getBlockPos()!=null
                && bot.mc.world.getBlockState(hit.getBlockPos()).getBlock().getMaterial().isFluid())
            return new BlockHitResult(aim,face,support);
        return hit!=null && support.equals(hit.getBlockPos()) && hit.direction==face ? hit : null;
    }
    private boolean pour(LegacyBot bot,BlockPos target,String fluid) {
        // Use any visible adjacent support. This lets the sparse speed-casting
        // mold grow the portal from its bottom corners instead of constructing
        // a wall behind every future obsidian block.
        Direction[] directions={Direction.DOWN,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP,Direction.NORTH};
        for(Direction towardSupport:directions) {
            BlockPos support=target.offset(towardSupport);
            if(!bot.navigation.floor(support.getX(),support.getY(),support.getZ())) continue;
            Direction face=towardSupport.getOpposite();
            for(double height:new double[]{.85,.5,.1}) {
                BlockHitResult hit=face(bot,support,face,height);
                if(hit==null) continue;
                int slot=bot.findInventory(LegacyItems.parse(fluid+"_bucket"));
                if(slot<0) throw new TaskFailure("Missing "+fluid+" bucket for portal casting.");
                bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
                LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                actionDeadline=bot.ticks+40;return true;
            }
        }
        // Find a real walking stance from which the required face is visible.
        // Deep trench cells cannot be poured from the general work position.
        PathSearch.Pos feet=bot.navigation.feet(),best=null;double bestScore=Double.POSITIVE_INFINITY;
        for(int x=target.getX()-4;x<=target.getX()+4;x++) for(int z=target.getZ()-4;z<=target.getZ()+4;z++)
            for(int y=target.getY()-1;y<=target.getY()+4;y++) {
                if(!PathSearch.standing(bot.navigation,x,y,z)) continue;
                Vec3d eye=new Vec3d(x+.5,y+1.62,z+.5);boolean visible=false;
                for(Direction towardSupport:directions) {
                    BlockPos support=target.offset(towardSupport);
                    if(!bot.navigation.floor(support.getX(),support.getY(),support.getZ())) continue;
                    Direction face=towardSupport.getOpposite();
                    for(double height:new double[]{.85,.5,.1})
                        if(faceFrom(bot,eye,support,face,height)!=null) {visible=true;break;}
                    if(visible) break;
                }
                if(!visible) continue;
                PathSearch.Pos candidate=new PathSearch.Pos(x,y,z);
                java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,candidate,2400);
                if(!feet.equals(candidate) && (route.isEmpty() || !route.get(route.size()-1).equals(candidate))) continue;
                double score=feet.distance(candidate);
                if(score<bestScore) {bestScore=score;best=candidate;}
            }
        if(best==null) throw new TaskFailure("Cannot find a visible support face for portal cell "+target+".");
        bot.navigation.move(best,.2);return false;
    }
    private boolean pourFrom(LegacyBot bot,BlockPos target,BlockPos support,Direction face,String fluid) {
        int slot=bot.findInventory(LegacyItems.parse(fluid+"_bucket"));
        if(slot<0) throw new TaskFailure("Missing "+fluid+" bucket for portal casting.");
        BlockHitResult hit=null;
        for(double height:new double[]{.85,.5,.1}) {hit=face(bot,support,face,height);if(hit!=null) break;}
        if(hit==null) throw new TaskFailure("Cannot reach the portal's "+fluid+" placement face at "+target+".");
        bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
        LegacyFluidTask.syncAim(bot);
        bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
        actionDeadline=bot.ticks+40;return true;
    }
    static boolean defend(LegacyBot bot) {
        net.minecraft.entity.LivingEntity threat=null;double closest=9;
        for(Object entry:bot.mc.world.loadedEntities) {
            if(!(entry instanceof net.minecraft.entity.mob.HostileEntity)) continue;
            // Do not provoke neutral mobs merely because they are nearby.
            if(entry instanceof net.minecraft.entity.mob.EndermanEntity || entry instanceof net.minecraft.entity.mob.ZombiePigmanEntity) continue;
            net.minecraft.entity.LivingEntity mob=(net.minecraft.entity.LivingEntity)entry;
            double distance=bot.mc.player.squaredDistanceTo(mob);
            if(!mob.isAlive() || distance>=closest) continue;
            Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
            Vec3d aim=new Vec3d(mob.x,mob.y+mob.getEyeHeight(),mob.z);
            if(bot.mc.world.rayTrace(eye,aim)!=null) continue;
            threat=mob;closest=distance;
        }
        if(threat==null) return false;
        bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.useKey,false);
        for(String weapon:new String[]{"diamond_sword","iron_sword","stone_sword","wooden_sword","stone_axe"}) {
            int slot=bot.findInventory(LegacyItems.parse(weapon));if(slot>=0) {bot.equip(slot);break;}
        }
        bot.look(threat.x,threat.y+threat.getEyeHeight(),threat.z);
        if(bot.ticks%8==0) {bot.mc.interactionManager.attackEntity(bot.mc.player,threat);bot.mc.player.swingHand();}
        return true;
    }
    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension==-1) {child=null;bot.say("Overworld preparation complete: entered the Nether.");return true;}
        if(bot.mc.player.dimension!=0) throw new TaskFailure("@overworld must start in the Overworld.");
        if(++ticks>72000) throw new TaskFailure("Overworld preparation exceeded its time limit.");
        if(defend(bot)) return false;
        if(bot.mc.player.getHungerManager().getFoodLevel()<18 || bot.mc.player.isUsingItem()) {
            int food=bot.findInventory(LegacyItems.food());
            if(food<0 && bot.mc.player.getHungerManager().getFoodLevel()<=6) food=bot.findInventory(LegacyItems.meat(false));
            if(food>=0) {
                bot.releaseMovement();bot.cancelMining();bot.equip(food);
                if(!bot.mc.player.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                bot.key(bot.mc.options.useKey,true);return false;
            }
        }
        bot.key(bot.mc.options.useKey,false);
        if(child!=null) {
            if(child.tick(bot)) {
                if(child instanceof LegacyFluidTask && ((LegacyFluidTask)child).fluid.equals("lava")) lavaPool=((LegacyFluidTask)child).lastSource();
                if(stage==2 && child instanceof LegacyFluidTask) returnToWorksite=true;
                child.stop(bot);child=null;bot.navigation.reset();
            }
            return false;
        }
        if(bot.ticks<waitUntil) return false;
        bot.closeOwnedScreen();
        if(stage==0) {
            if(!preparationStarted) {
                preparationStarted=true;child=new LegacyPreparationTask(!preparationOnly,plan);return false;
            }
            if(preparationOnly) {state="supplies ready";bot.say("Overworld preparation complete: supplies ready.");return true;}
            stage=1;state="find water, a lava pool and a casting worksite";
        }
        if(stage==1) {
            // Preparation often ends in the stone mine. Leave it before bucket
            // searches, otherwise their walking-only approaches reject surface pools.
            if(SurfaceAccessTask.underground(bot)) {child=new SurfaceAccessTask();return false;}
            portal=bot.nearestBlock(s->String.valueOf(Block.REGISTRY.getIdentifier(s.getBlock())).equals("minecraft:portal"),24);
            if(portal!=null) {while(block(bot,portal.down(),"portal")) portal=portal.down();stage=4;}
            else {
                if(resumeMold(bot)) return false;
                if(!plan.ready()) {child=new LegacyPortalSiteTask(plan);return false;}
                lavaPool=plan.lavaPool;origin=plan.origin;
                int bucketCount=bot.count(LegacyItems.parse("bucket"))+bot.count(LegacyItems.parse("water_bucket"))+bot.count(LegacyItems.parse("lava_bucket"));
                if(bucketCount==0) {get("bucket",1);return false;}
                if(bot.count(LegacyItems.parse("water_bucket"))==0) {
                    if(bot.count(LegacyItems.parse("lava_bucket"))>0) {
                        // A restarted run may already carry lava. Empty it back
                        // into an existing lava cell, never onto dry ground.
                        BlockPos returning=bot.nearestBlock(state->state.getBlock().getMaterial()==net.minecraft.block.material.Material.LAVA,
                                24,java.util.Collections.emptySet(),p->{
                                    for(Direction d:Direction.values()) {
                                        BlockPos support=p.offset(d);
                                        if(bot.navigation.floor(support.getX(),support.getY(),support.getZ())) return true;
                                    }
                                    return false;
                                });
                        if(returning==null) throw new TaskFailure("No supported lava-pool cell to return the carried lava.");
                        state="return carried lava to the pool before collecting water";
                        if(pour(bot,returning,"lava")) waitUntil=bot.ticks+10;
                        return false;
                    }
                    child=new LegacyFluidTask("water");return false;
                }
                if(SurfaceAccessTask.underground(bot)) {child=new SurfaceAccessTask();return false;}
                returnToWorksite=true;
                stage=2;
            }
        }
        if(stage==2) {
            // site() ranks and validates a supported casting location; it does
            // not move the player there. Return to that staging spot after every
            // water/lava refill before placing another mold block.
            PathSearch.Pos stand=workStand();
            if(returnToWorksite) {
                double siteDx=bot.mc.player.x-(origin.getX()+.5),siteDz=bot.mc.player.z-(origin.getZ()+1.5);
                if(siteDx*siteDx+siteDz*siteDz>16 || Math.abs(bot.mc.player.y-origin.getY())>1.2) {
                    try {bot.navigation.move(stand,.5);}
                    catch(TaskFailure blocked) {
                        // A reachable pool-edge ledge may sit below a two-block
                        // bank, even at the casting site elevation. Build a step rather than
                        // abandoning the entire portal after collecting lava.
                        int y=bot.navigation.feet().y;
                        if(y<origin.getY()+3 && returnClimbs++<4) {
                            bot.navigation.reset();child=new SurfaceAccessTask(y+1,true);
                        } else throw blocked;
                    }
                    return false;
                }
                bot.releaseMovement();bot.navigation.reset();returnToWorksite=false;returnClimbs=0;
            }
            if(frame>=FRAME.length) {
                if(!waterRecovered) {
                    if(bot.count(LegacyItems.parse("water_bucket"))>0
                            && !LegacyFluidTask.source(bot.mc.world.getBlockState(waterSource),"water")) {
                        waterRecovered=true;actionDeadline=bot.ticks+40;return false;
                    }
                    if(bot.count(LegacyItems.parse("bucket"))==0) throw new TaskFailure("Need the empty bucket to recover the mold water.");
                    child=new LegacyFluidTask("water",waterSource);return false;
                }
                if(bot.count(LegacyItems.parse("water_bucket"))==0) {
                    if(bot.ticks<actionDeadline) return false;
                    throw new TaskFailure("Could not recover the mold water with the single bucket.");
                }
                stage=3;state="clear portal interior and approach";return false;
            }
            state=moldBuilt ? "cast portal frame ("+(frame+1)+"/10)" : "practice building the cobblestone portal mold";
            BlockPos cell=origin.add(FRAME[frame][0],FRAME[frame][1],FRAME[frame][2]);
            if(cast==0) {
                if(!moldBuilt) {
                    if(moldBlocks==null) {
                        moldBlocks=new java.util.ArrayList<>();
                        // Exact coordinates recorded from the user's manual
                        // build: two lower side blocks, a three-high far
                        // upright, and one top block over the trench.
                        moldBlocks.add(origin.add(1,0,0));
                        moldBlocks.add(origin.add(1,0,1));
                        moldBlocks.add(origin.add(1,0,3));
                        moldBlocks.add(origin.add(1,1,3));
                        moldBlocks.add(origin.add(1,2,3));
                        moldBlocks.add(origin.add(0,2,3));
                    }
                    while(moldCursor<moldBlocks.size()) {
                        BlockPos mold=moldBlocks.get(moldCursor);
                        if(!bot.navigation.floor(mold.getX(),mold.getY(),mold.getZ())) {child=new LegacyPlaceTask(mold);return false;}
                        moldCursor++;
                    }
                    moldBuilt=true;
                }
                while(trenchCleared<TRENCH.length) {
                    int[] t=TRENCH[trenchCleared];BlockPos trench=origin.add(t[0],t[1],t[2]);
                    if(!bot.mc.world.isAir(trench)) {
                        if(bot.mc.world.getBlockState(trench).getBlock().getMaterial().isFluid()) {trenchCleared++;continue;}
                        bot.mine(trench);return false;
                    }
                    trenchCleared++;
                }
                while(moldCleared<FRAME.length) {
                    BlockPos opening=origin.add(FRAME[moldCleared][0],FRAME[moldCleared][1],FRAME[moldCleared][2]);
                    if(!bot.mc.world.isAir(opening)) {
                        if(bot.mc.world.getBlockState(opening).getBlock().getMaterial().isFluid()) {moldCleared++;continue;}
                        PathSearch.Pos feet=bot.navigation.feet();
                        boolean playerOccupies=(opening.getX()==feet.x && opening.getZ()==feet.z
                                && opening.getY()>=feet.y-1 && opening.getY()<=feet.y+1);
                        if(playerOccupies) {
                            if(!opening.equals(openingExitTarget)) {openingExitTarget=opening;openingExit=null;}
                            if(openingExit==null) {
                                java.util.List<PathSearch.Pos> exits=new java.util.ArrayList<>();
                                java.util.Set<BlockPos> frameOpenings=new java.util.HashSet<>();
                                for(int[] f:FRAME) frameOpenings.add(origin.add(f[0],f[1],f[2]));
                                for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++) {
                                    if(Math.abs(dx)+Math.abs(dz)<2) continue;
                                    PathSearch.Pos p=new PathSearch.Pos(feet.x+dx,feet.y,feet.z+dz);
                                    if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z)
                                            || frameOpenings.contains(new BlockPos(p.x,p.y-1,p.z))) continue;
                                    java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,p,1800);
                                    if(!route.isEmpty() && route.get(route.size()-1).equals(p)) exits.add(p);
                                }
                                exits.sort(java.util.Comparator.comparingDouble(feet::distance));
                                if(!exits.isEmpty()) openingExit=exits.get(0);
                                else throw new TaskFailure("No supported place to step before clearing portal footing "+opening);
                            }
                            bot.navigation.move(openingExit,.15);
                            return false;
                        }
                        openingExitTarget=null;openingExit=null;
                        bot.mine(opening);return false;
                    }
                    openingExitTarget=null;openingExit=null;
                    moldCleared++;
                }
                if(!waterStarted) {
                    // Feed water into the portal interior. The backing wall is
                    // already complete, so the source stays in the 2-wide
                    // opening and flows down against every frame cell.
                    if(bot.count(LegacyItems.parse("water_bucket"))==0) {
                        if(!descendForRefill(bot)) return false;
                        child=new LegacyFluidTask("water");return false;
                    }
                    // The bucket must hit the top face of the arm. Stand two
                    // blocks above ground on a temporary viewing pillar so the
                    // face is actually visible, then descend before refilling.
                    // Put the water on the rear/top of the arm, as shown in
                    // the second reference image. It spills forward into the
                    // portal opening and down into the trench.
                    waterSource=origin.add(1,2,2);
                    BlockPos anchor=origin.add(1,2,3);
                    if(pourFrom(bot,waterSource,anchor,Direction.NORTH,"water")) waterStarted=true;
                    return false;
                }
                if(!LegacyFluidTask.source(bot.mc.world.getBlockState(waterSource),"water"))
                    throw new TaskFailure("Water source did not settle in the portal's interior channel.");
                // This route deliberately reuses one bucket. While it contains
                // lava there cannot also be an empty bucket in inventory.
                if(bot.count(LegacyItems.parse("lava_bucket"))==0) {
                    if(!descendForRefill(bot)) return false;
                    child=new LegacyFluidTask("lava",lavaPool,12);return false;
                }
                bot.cancelMining();
                // The mold's supported faces are deliberately built within bucket
                // reach. Avoid walking onto its narrow scaffold for each cast.
                if(pour(bot,cell,"lava")) cast=1;
                return false;
            }
            if(cast==1) {
                // Client-side bucket prediction is not confirmation. Let the
                // server's block and inventory updates arrive before advancing.
                if(bot.ticks<actionDeadline) return false;
                if(!block(bot,cell,"obsidian")) {
                    if(bot.count(LegacyItems.parse("lava_bucket"))>0 && ++castRetries<=3) {
                        cast=0;bot.navigation.reset();return false;
                    }
                    throw new TaskFailure("Water did not cast lava at "+cell+" into obsidian.");
                }
                castRetries=0;
                // The bucket is empty after pouring lava. Refill it from the
                // pool for the next frame block while the water remains in place.
                frame++;cast=0;waitUntil=bot.ticks+5;return false;
            }
            cast=1;actionDeadline=bot.ticks+40;return false;
        }
        if(stage==3) {
            for(int y=-1;y<=1;y++) for(int z=1;z<=2;z++) {
                BlockPos p=origin.add(0,y,z);
                if(!bot.mc.world.isAir(p)) {
                    if(bot.mc.world.getBlockState(p).getBlock().getMaterial().isFluid()) {bot.releaseMovement();return false;}
                    bot.mine(p);return false;
                }
            }
            bot.cancelMining();portal=origin.add(0,-1,1);stage=4;state="ignite and enter portal";
        }
        if(stage==4) {
            if(!block(bot,portal,"portal")) {
                BlockPos floor=portal.down();
                if(bot.mc.player.squaredDistanceToCenter(floor)>20) {
                    bot.navigation.move(new PathSearch.Pos(portal.getX(),portal.getY(),portal.getZ()),.4);return false;
                }
                int slot=bot.findInventory(LegacyItems.parse("flint_and_steel"));
                if(slot<0) {get("flint_and_steel",1);return false;}
                Vec3d top=new Vec3d(floor.getX()+.5,floor.getY()+.999,floor.getZ()+.5);
                bot.equip(slot);bot.releaseMovement();bot.look(top.x,top.y,top.z);LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),floor,Direction.UP,top);
                waitUntil=bot.ticks+10;return false;
            }
            bot.navigation.move(new PathSearch.Pos(portal.getX(),portal.getY(),portal.getZ()),.2);
        }
        return false;
    }
}
