package adris.altoclef.legacy;

import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.*;
import java.util.*;

final class LegacyNavigation implements PathSearch.Terrain {
    private final LegacyBot bot;
    private List<PathSearch.Pos> path = Collections.emptyList();
    private PathSearch.Pos destination, approachGoal;
    private BlockPos approachBlock;
    private List<PathSearch.Pos> approachCandidates;
    private int approachIndex,cacheTick=-1;
    private final Map<PathSearch.Pos,Boolean> clearCache=new HashMap<>(),floorCache=new HashMap<>();
    private void refreshCache() {if(cacheTick!=bot.ticks) {cacheTick=bot.ticks;clearCache.clear();floorCache.clear();}}
    private final Set<PathSearch.Pos> rejectedApproaches=new HashSet<>();
    private int next, stalled, lastPlan;
    private double lastX, lastY, lastZ;
    private boolean excavation;
    private boolean diggingApproaches;
    private PathSearch.Pos distantApproach;
    private BoatSprintTask sprintBoat;
    private int nextBoatAttempt;

    LegacyNavigation(LegacyBot bot) { this.bot = bot; }
    void deferBoatForCombat() {
        nextBoatAttempt=bot.ticks+100;
        if(sprintBoat!=null) {sprintBoat.stop(bot);sprintBoat=null;}
    }
    private boolean valid(int x, int y, int z) {
        return y >= 0 && y < 256 && LegacyWorld.isLoaded(bot.mc.world,new BlockPos(x, y, z), false);
    }
    @Override public boolean clear(int x,int y,int z) {
        refreshCache();PathSearch.Pos p=new PathSearch.Pos(x,y,z);Boolean cached=clearCache.get(p);
        if(cached!=null) return cached;
        boolean result=uncachedClear(x,y,z);clearCache.put(p,result);return result;
    }
    private boolean uncachedClear(int x, int y, int z) {
        if (!valid(x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState state = LegacyWorld.state(bot.mc.world,p);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        // Avoid liquids, fire, webs, cactus and collision shapes rather than guessing passability.
        String name = String.valueOf(Block.REGISTRY.getId(block));
        if (material == Material.WATER || material == Material.LAVA
                || name.contains("fire") || name.contains("web") || name.contains("cactus") || name.contains("pressure_plate")) return false;
        // In 1.8 a single snow layer has a non-null, zero-height collision box.
        // It is walkable air above its supporting ground, not a solid obstacle.
        if(block instanceof net.minecraft.block.SnowLayerBlock && state.getData()==0) return true;
        return LegacyWorld.collisionBox(block,bot.mc.world, p, state) == null;
    }
    @Override public boolean floor(int x,int y,int z) {
        refreshCache();PathSearch.Pos p=new PathSearch.Pos(x,y,z);Boolean cached=floorCache.get(p);
        if(cached!=null) return cached;
        boolean result=uncachedFloor(x,y,z);floorCache.put(p,result);return result;
    }
    private boolean uncachedFloor(int x, int y, int z) {
        if (!valid(x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState state = LegacyWorld.state(bot.mc.world,p);
        Block b = state.getBlock();
        String id=String.valueOf(Block.REGISTRY.getId(b));
        if(id.equals("minecraft:farmland")) return true;
        if(id.contains("cactus") || id.equals("minecraft:tnt") || b.getMaterial()==Material.WATER || b.getMaterial()==Material.LAVA) return false;
        // Rendering/full-cube flags exclude leaves despite their solid collision.
        // Require a full supporting top face, so partial slabs/fences stay excluded.
        Box box=LegacyWorld.collisionBox(b,bot.mc.world,p,state);
        return box!=null && box.minX<=x && box.maxX>=x+1 && box.minZ<=z && box.maxZ>=z+1
                && Math.abs(box.maxY-(y+1))<.001;
    }
    PathSearch.Pos feet() {
        return new PathSearch.Pos((int) Math.floor(bot.mc.field_3805.x),
                (int) Math.floor(bot.mc.field_3805.boundingBox.minY + 0.1), (int) Math.floor(bot.mc.field_3805.z));
    }
    private void resetPath() {path=Collections.emptyList();destination=null;stalled=0;next=0;excavation=false;}
    void reset() {if(sprintBoat!=null) {sprintBoat.stop(bot);sprintBoat=null;}resetPath();approachGoal=null;approachBlock=null;approachCandidates=null;approachIndex=0;distantApproach=null;rejectedApproaches.clear();}

    boolean move(PathSearch.Pos target, double tolerance) {
        if(!bot.glitchless && sprintBoat==null && bot.ticks>=nextBoatAttempt) {
            nextBoatAttempt=bot.ticks+40;
            if(!nearLava()) sprintBoat=BoatSprintTask.plan(bot,target);
            if(sprintBoat!=null) nextBoatAttempt=bot.ticks+1200;
        }
        if(sprintBoat!=null) {
            if(!sprintBoat.tick(bot)) return false;
            sprintBoat.stop(bot);sprintBoat=null;resetPath();
        }
        if(bot.nearbySupplies.tick(bot,target)) return false;
        double dx = target.x + 0.5 - bot.mc.field_3805.x, dz = target.z + 0.5 - bot.mc.field_3805.z;
        if (bot.mc.field_3805.onGround && dx * dx + dz * dz <= tolerance * tolerance && Math.abs(bot.mc.field_3805.boundingBox.minY - target.y) < .2) {
            bot.releaseMovement(); resetPath(); return true;
        }
        double moved = Math.abs(bot.mc.field_3805.x - lastX) + Math.abs(bot.mc.field_3805.boundingBox.minY-lastY) + Math.abs(bot.mc.field_3805.z - lastZ);
        lastX = bot.mc.field_3805.x; lastY=bot.mc.field_3805.boundingBox.minY; lastZ = bot.mc.field_3805.z;
        stalled = moved < 0.015 ? stalled + 1 : 0;
        if (stalled > 80) throw new TaskFailure("Movement is blocked near " + feet() + ".");
        // Keep the current landing waypoint during a jump/drop instead of replanning from air.
        if (!bot.mc.field_3805.onGround && (next>=path.size() || !target.equals(destination))) {
            bot.releaseMovement();return false;
        }
        if (bot.mc.field_3805.onGround && (!target.equals(destination) || next >= path.size() || stalled == 20)) {
            PathSearch.Pos start=PathSearch.groundedStart(this,bot.mc.field_3805.x,feet().y,bot.mc.field_3805.z);
            boolean direct=start.y==target.y && start.distance(target)<=48
                    && PathSearch.walkStraight(this,bot.mc.field_3805.x,bot.mc.field_3805.z,target);
            path = start.equals(target) || direct ? new ArrayList<>(Collections.singletonList(target)) : PathSearch.find(this, start, target, 6000);
            excavation=false;
            if(path.isEmpty() || !path.get(path.size()-1).equals(target) && path.get(path.size()-1).distance(start)<4) {
                List<PathSearch.Pos> dug=digRoute(start,target,2000);
                if(!dug.isEmpty() && (path.isEmpty() || dug.get(dug.size()-1).distance(target)<path.get(path.size()-1).distance(target))) {
                    path=dug;excavation=true;
                }
            }
            if(!start.equals(feet())) path.add(0,start);
            destination = target; next = 0; lastPlan = bot.ticks;
            if (path.isEmpty()) throw new TaskFailure("No safe walking route to " + target + " in loaded terrain.");
        }
        if(excavation && clearStep(path.get(next))) return false;
        if(!excavation) bot.cancelMining();
        // Follow a straight diagonal across open ground instead of yawing at every
        // cardinal A* step. Sweep the full body so corners and drops remain protected.
        if(bot.mc.field_3805.onGround) for(int i=Math.min(path.size()-1,next+8);i>next;i--) {
            PathSearch.Pos ahead=path.get(i);
            boolean level=ahead.y==feet().y;
            for(int j=next;j<=i && level;j++) level=path.get(j).y==ahead.y;
            if(level && PathSearch.walkStraight(this,bot.mc.field_3805.x,bot.mc.field_3805.z,ahead)) {next=i;break;}
        }
        PathSearch.Pos waypoint = path.get(next);
        dx = waypoint.x + 0.5 - bot.mc.field_3805.x; dz = waypoint.z + 0.5 - bot.mc.field_3805.z;
        boolean turn=false;
        if(next+1<path.size()) {
            PathSearch.Pos ahead=path.get(next+1);
            turn=ahead.y!=waypoint.y || (Math.abs(dx)>Math.abs(dz) ? ahead.z!=waypoint.z : ahead.x!=waypoint.x);
        }
        double radius=turn ? .15 : .35;
        if(waypoint.equals(target)) radius=Math.min(radius,tolerance);
        if (bot.mc.field_3805.onGround && dx * dx + dz * dz < radius*radius && Math.abs(bot.mc.field_3805.boundingBox.minY - waypoint.y) < .2) {
            next++;
            if (next >= path.size()) { bot.releaseMovement(); return false; }
            waypoint = path.get(next);
            if(excavation && clearStep(waypoint)) return false;
            dx = waypoint.x + .5 - bot.mc.field_3805.x; dz = waypoint.z + .5 - bot.mc.field_3805.z;
        }
        if (!PathSearch.standing(this, waypoint.x, waypoint.y, waypoint.z)) {
            path = Collections.emptyList(); bot.releaseMovement(); return false;
        }
        // Once above the landing center, stop pushing past it and reversing direction
        // every tick while gravity finishes the drop.
        if(!bot.mc.field_3805.onGround) {
            // Correct drift with movement keys, keeping the camera steady during a fall.
            double yaw=Math.toRadians(bot.mc.field_3805.yaw);
            double forward=-dx*Math.sin(yaw)+dz*Math.cos(yaw);
            double left=dx*Math.cos(yaw)+dz*Math.sin(yaw);
            bot.releaseMovement();
            if(dx*dx+dz*dz>=.0225) {
                bot.key(bot.mc.options.forwardKey,forward>.08);
                bot.key(bot.mc.options.backKey,forward<-.08);
                bot.key(bot.mc.options.leftKey,left>.08);
                bot.key(bot.mc.options.rightKey,left<-.08);
                holdSprint(forward>.08,nearLava());
            }
            return false;
        }
        float desired=(float)Math.toDegrees(Math.atan2(-dx,dz));
        float difference=(desired-bot.mc.field_3805.yaw)%360;
        if(difference>180) difference-=360;
        if(difference< -180) difference+=360;
        bot.mc.field_3805.yaw+=Math.max(-25,Math.min(25,difference));
        bot.mc.field_3805.pitch = 10;
        boolean uphill=waypoint.y>bot.mc.field_3805.boundingBox.minY+.35;
        boolean forward=Math.abs(difference)<(uphill ? 15 : 50);
        boolean precision=waypoint.y==feet().y && radius<.2 && dx*dx+dz*dz<.36;
        boolean lavaEdge=nearLava();
        // Crouching prevents stepping off even a one-block lip. The path has
        // already checked the landing and swept drop corridor; release sneak
        // for this deliberate descent, while still disabling sprint near lava.
        boolean safeDescent=waypoint.y<feet().y && feet().y-waypoint.y<=2
                && Math.abs(dx)<1.6 && Math.abs(dz)<1.6
                && PathSearch.standing(this,waypoint.x,waypoint.y,waypoint.z);
        holdSprint(forward,precision || lavaEdge);
        bot.key(bot.mc.options.forwardKey, forward);
        bot.key(bot.mc.options.backKey,false);bot.key(bot.mc.options.leftKey,false);bot.key(bot.mc.options.rightKey,false);
        // Centering for mining/pillaring may have held sneak. Walking must release it,
        // otherwise Minecraft's edge protection prevents stepping down.
        bot.key(bot.mc.options.sneakKey, precision || lavaEdge && !safeDescent);
        // Finish turning before takeoff: sprint jumps otherwise launch along
        // the previous heading, and repeated early jumps can prevent the turn.
        bot.key(bot.mc.options.jumpKey, bot.mc.field_3805.onGround && uphill && forward
                && (bot.mc.field_3805.getHungerManager().getFoodLevel()>6 || dx*dx+dz*dz<=1.3225));
        return false;
    }

    private boolean nearLava() {
        PathSearch.Pos p=feet();
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=-2;dy<=1;dy++) {
            BlockPos cell=new BlockPos(p.x+dx,p.y+dy,p.z+dz);
            if(LegacyWorld.isLoaded(bot.mc.world,cell,false) && LegacyWorld.state(bot.mc.world,cell).getBlock().getMaterial()==Material.LAVA) return true;
        }
        return false;
    }
    private void holdSprint(boolean forward,boolean precision) {
        bot.sprint(forward && !precision && bot.mc.field_3805.getHungerManager().getFoodLevel()>6
                && !bot.mc.field_3805.isUsingItem());
        // Keep the binding held through turns and jumps; vanilla still applies
        // hunger and crouching restrictions. Stops and pauses release it.
        bot.key(bot.mc.options.sprintKey,forward && !precision && bot.mc.field_3805.getHungerManager().getFoodLevel()>6);
    }

    boolean approach(BlockPos block) {
        if (bot.mc.field_3805.onGround && bot.hit(block) != null) { bot.releaseMovement();reset();return true; }
        if(!block.equals(approachBlock)) {
            approachBlock=block;approachGoal=null;approachCandidates=null;approachIndex=0;diggingApproaches=false;rejectedApproaches.clear();distantApproach=null;resetPath();
        }
        double horizontal=Math.hypot(block.x-bot.mc.field_3805.x,block.z-bot.mc.field_3805.z);
        if(horizontal>8) {
            // A known distant tree does not need a complete path to a mining
            // face yet. Make checked walking progress toward it, retain the
            // target, and resolve the final face once the player is nearby.
            if(distantApproach==null && bot.mc.field_3805.onGround) {
                PathSearch.Pos from=feet(),goal=new PathSearch.Pos(block.x,from.y,block.z);
                List<PathSearch.Pos> route=PathSearch.explore(this,from,goal,2400);
                if(!route.isEmpty()) {
                    PathSearch.Pos end=route.get(route.size()-1);
                    if(Math.hypot(end.x-block.x,end.z-block.z)<horizontal-2) distantApproach=end;
                }
            }
            if(distantApproach!=null) {
                if(move(distantApproach,.4)) {distantApproach=null;approachCandidates=null;resetPath();}
                return false;
            }
        } else distantApproach=null;
        if(approachGoal==null) {
            if(!bot.mc.field_3805.onGround) {bot.releaseMovement();return false;}
            PathSearch.Pos from=PathSearch.groundedStart(this,bot.mc.field_3805.x,feet().y,bot.mc.field_3805.z);
            if(approachCandidates==null) {
            List<PathSearch.Pos> candidates=new ArrayList<>();
            for(int x=block.x-2;x<=block.x+2;x++)
                for(int z=block.z-2;z<=block.z+2;z++)
                    for(int y=block.y-3;y<=block.y+3;y++) {
                        PathSearch.Pos p=new PathSearch.Pos(x,y,z);
                        if(rejectedApproaches.contains(p) || !PathSearch.standing(this,x,y,z)) continue;
                        net.minecraft.util.hit.BlockHitResult hit=bot.hitFrom(Vec3d.of(x+.5,y+1.62,z+.5),block);
                        if(hit!=null && block.equals(LegacyPos.hit(hit))) candidates.add(p);
                    }
            candidates.sort(Comparator.comparingDouble(from::distance));
            approachCandidates=candidates;approachIndex=0;
            }
            for(int attempts=0;attempts<3 && approachIndex<approachCandidates.size();attempts++) {
                PathSearch.Pos p=approachCandidates.get(approachIndex++);
                if(rejectedApproaches.contains(p)) continue;
                List<PathSearch.Pos> route=diggingApproaches ? digRoute(from,p,1200) : PathSearch.find(this,from,p,2000);
                if(from.equals(p) || !route.isEmpty() && route.get(route.size()-1).equals(p)) {approachGoal=p;break;}
            }
            if(approachGoal==null) {
                bot.releaseMovement();
                if(approachIndex<approachCandidates.size()) return false;
                if(!diggingApproaches) {diggingApproaches=true;approachIndex=0;return false;}
                throw new TaskFailure("Cannot safely reach block "+block+".");
            }
        }
        try {
            if(move(approachGoal,.1)) {rejectedApproaches.add(approachGoal);approachGoal=null;}
        } catch(TaskFailure blocked) {
            rejectedApproaches.add(approachGoal);approachGoal=null;resetPath();
        }
        return false;
    }

    private boolean safeBreak(BlockPos p) {
        if(bot.protectsTowerBase(p) || !valid(p.x,p.y,p.z) || p.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805)))
                || LegacyWorld.blockEntity(bot.mc.world,p)!=null) return false;
        BlockState state=LegacyWorld.state(bot.mc.world,p);Block block=state.getBlock();
        String id=String.valueOf(Block.REGISTRY.getId(block));
        if(!Arrays.asList("minecraft:dirt","minecraft:grass","minecraft:stone","minecraft:cobblestone",
                "minecraft:sandstone","minecraft:red_sandstone","minecraft:hardened_clay","minecraft:stained_hardened_clay",
                "minecraft:leaves","minecraft:leaves2","minecraft:log","minecraft:log2","minecraft:snow_layer").contains(id)) return false;
        if(block.getMaterial()==Material.STONE) {
            boolean tool=false;
            for(net.minecraft.item.ItemStack stack:bot.mc.field_3805.inventory.main)
                if(LegacyItems.usableTool(stack) && stack.isEffectiveOn(block)) {tool=true;break;}
            if(!tool) return false;
        }
        if(LegacyWorld.state(bot.mc.world,LegacyPos.up(p)).getBlock() instanceof FallingBlock) return false;
        for(Direction d:Direction.values()) {
            BlockPos adjacent=LegacyPos.offset(p,d);
            if(!LegacyWorld.isLoaded(bot.mc.world,adjacent,false) || LegacyWorld.state(bot.mc.world,adjacent).getBlock().getMaterial().isFluid()) return false;
        }
        return true;
    }
    private List<PathSearch.Pos> digRoute(PathSearch.Pos start,PathSearch.Pos goal,int budget) {
        Map<PathSearch.Pos,Boolean> breakable=new HashMap<>();
        PathSearch.Terrain terrain=new PathSearch.Terrain() {
            public boolean clear(int x,int y,int z) {
                if(LegacyNavigation.this.clear(x,y,z)) return true;
                return breakable.computeIfAbsent(new PathSearch.Pos(x,y,z),p -> safeBreak(new BlockPos(x,y,z)));
            }
            public boolean floor(int x,int y,int z) {return LegacyNavigation.this.floor(x,y,z);}
            public double clearanceCost(int x,int y,int z) {return LegacyNavigation.this.clear(x,y,z) ? 0 : 12;}
        };
        List<PathSearch.Pos> route=PathSearch.excavate(terrain,start,goal,budget);
        // This fallback clears a small obstruction, not a tunnel across the map.
        // Walking partial routes remain preferred while they make useful progress.
        if(route.size()>24) return Collections.emptyList();
        Set<BlockPos> removed=new HashSet<>();
        PathSearch.Pos previous=start;
        for(PathSearch.Pos p:route) {
            for(BlockPos block:new BlockPos[]{new BlockPos(p.x,p.y,p.z),new BlockPos(p.x,p.y+1,p.z)})
                if(!LegacyNavigation.this.clear(block.x,block.y,block.z)) removed.add(block);
            if(p.y>previous.y && !LegacyNavigation.this.clear(previous.x,previous.y+2,previous.z)) removed.add(new BlockPos(previous.x,previous.y+2,previous.z));
            previous=p;
        }
        return removed.size()>4 ? Collections.emptyList() : route;
    }
    List<PathSearch.Pos> approachRoute(PathSearch.Pos start,PathSearch.Pos goal,int budget) {
        List<PathSearch.Pos> route=PathSearch.find(this,start,goal,budget);
        return !route.isEmpty() && route.get(route.size()-1).equals(goal) ? route : digRoute(start,goal,budget);
    }
    /** Finish safe head/foot clearance before moving into the next cell. */
    private boolean clearStep(PathSearch.Pos next) {
        if(!bot.mc.field_3805.onGround) return false;
        List<BlockPos> obstacles=new ArrayList<>();
        if(next.y>feet().y) obstacles.add(new BlockPos(feet().x,feet().y+2,feet().z));
        obstacles.add(new BlockPos(next.x,next.y+1,next.z));
        obstacles.add(new BlockPos(next.x,next.y,next.z));
        boolean blocked=false;
        for(BlockPos p:obstacles) {
            if(clear(p.x,p.y,p.z)) continue;
            blocked=true;
            if(!safeBreak(p)) {bot.cancelMining();resetPath();throw new TaskFailure("Route obstruction cannot be safely mined at "+p);}
            if(bot.hit(p)!=null) {bot.mine(p);return true;}
        }
        if(blocked) {bot.cancelMining();resetPath();throw new TaskFailure("Route obstruction is out of mining reach");}
        bot.cancelMining();return false;
    }
}
