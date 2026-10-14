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
    private PathSearch.Pos progressWaypoint;
    private double bestWaypointDistance;
    private int waypointProgressTick;
    private boolean excavation;
    private boolean diggingApproaches;
    boolean disarmedTempleTnt;

    LegacyNavigation(LegacyBot bot) { this.bot = bot; }
    private boolean valid(int x, int y, int z) {
        return y >= 0 && y < 256 && bot.mc.world.isLoaded(new BlockPos(x, y, z), false);
    }
    @Override public boolean clear(int x,int y,int z) {
        refreshCache();PathSearch.Pos p=new PathSearch.Pos(x,y,z);Boolean cached=clearCache.get(p);
        if(cached!=null) return cached;
        boolean result=uncachedClear(x,y,z);clearCache.put(p,result);return result;
    }
    private boolean uncachedClear(int x, int y, int z) {
        if (!valid(x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState state = bot.mc.world.getBlockState(p);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        // Avoid liquids, fire, webs, cactus and collision shapes rather than guessing passability.
        String name = String.valueOf(Block.REGISTRY.getIdentifier(block));
        if (material == Material.WATER || material == Material.LAVA
                || name.contains("fire") || name.contains("web") || name.contains("cactus") || name.contains("pressure_plate")) return false;
        // In 1.8 a single snow layer has a non-null, zero-height collision box.
        // It is walkable air above its supporting ground, not a solid obstacle.
        if(block instanceof net.minecraft.block.SnowLayerBlock && block.getData(state)==0) return true;
        return block.getCollisionBox(bot.mc.world, p, state) == null;
    }
    @Override public boolean floor(int x,int y,int z) {
        refreshCache();PathSearch.Pos p=new PathSearch.Pos(x,y,z);Boolean cached=floorCache.get(p);
        if(cached!=null) return cached;
        boolean result=uncachedFloor(x,y,z);floorCache.put(p,result);return result;
    }
    private boolean uncachedFloor(int x, int y, int z) {
        if (!valid(x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState state = bot.mc.world.getBlockState(p);
        Block b = state.getBlock();
        String id=String.valueOf(Block.REGISTRY.getIdentifier(b));
        if(id.contains("cactus") || id.equals("minecraft:tnt") && !disarmedTempleTnt || b.getMaterial()==Material.WATER || b.getMaterial()==Material.LAVA) return false;
        // Rendering/full-cube flags exclude leaves despite their solid collision.
        // Require a full supporting top face, so partial slabs/fences stay excluded.
        Box box=b.getCollisionBox(bot.mc.world,p,state);
        return box!=null && box.minX<=x && box.maxX>=x+1 && box.minZ<=z && box.maxZ>=z+1
                && Math.abs(box.maxY-(y+1))<.001;
    }
    PathSearch.Pos feet() {
        return new PathSearch.Pos((int) Math.floor(bot.mc.player.x),
                (int) Math.floor(bot.mc.player.y + 0.01), (int) Math.floor(bot.mc.player.z));
    }
    private void resetPath() {path=Collections.emptyList();destination=null;stalled=0;next=0;excavation=false;progressWaypoint=null;}
    void reset() {resetPath();approachGoal=null;approachBlock=null;approachCandidates=null;approachIndex=0;rejectedApproaches.clear();}

    /** Reuse the surface planner's checked route instead of running A* for it again. */
    void useWalkingRoute(PathSearch.Pos from,List<PathSearch.Pos> route) {
        reset();if(route.isEmpty())return;
        path=new ArrayList<>(route);destination=path.get(path.size()-1);
        if(!from.equals(feet()))path.add(0,from);
        lastX=bot.mc.player.x;lastY=bot.mc.player.y;lastZ=bot.mc.player.z;lastPlan=bot.ticks;
    }

    boolean move(PathSearch.Pos target, double tolerance) {
        return move(target,tolerance,false);
    }
    /** Construction needs accurate stops beside beds, stairs and narrow platforms. */
    boolean movePrecisely(PathSearch.Pos target,double tolerance) {
        return move(target,tolerance,true);
    }
    private boolean move(PathSearch.Pos target, double tolerance,boolean precision) {
        if(bot.nearbySupplies.tick(bot,target)) return false;
        double dx = target.x + 0.5 - bot.mc.player.x, dz = target.z + 0.5 - bot.mc.player.z;
        if (bot.mc.player.onGround && dx * dx + dz * dz <= tolerance * tolerance && Math.abs(bot.mc.player.y - target.y) < .2) {
            bot.releaseMovement(); resetPath(); return true;
        }
        double moved = Math.abs(bot.mc.player.x - lastX) + Math.abs(bot.mc.player.y-lastY) + Math.abs(bot.mc.player.z - lastZ);
        lastX = bot.mc.player.x; lastY=bot.mc.player.y; lastZ = bot.mc.player.z;
        stalled = moved < 0.015 ? stalled + 1 : 0;
        if (stalled > 80) throw new TaskFailure("Movement is blocked near " + feet() + ".");
        // Keep the current landing waypoint during a jump/drop instead of replanning from air.
        if (!bot.mc.player.onGround && (next>=path.size() || !target.equals(destination))) {
            bot.releaseMovement();return false;
        }
        if (bot.mc.player.onGround && (!target.equals(destination) || next >= path.size() || stalled == 20)) {
            PathSearch.Pos start=PathSearch.groundedStart(this,bot.mc.player.x,feet().y,bot.mc.player.z);
            boolean direct=start.y==target.y && start.distance(target)<=48
                    && PathSearch.walkStraight(this,bot.mc.player.x,bot.mc.player.z,target);
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
        // Follow a straight diagonal across open ground instead of yawing at every
        // cardinal A* step. Sweep the full body so corners and drops remain protected.
        if(bot.mc.player.onGround) for(int i=Math.min(path.size()-1,next+12);i>next;i--) {
            PathSearch.Pos ahead=path.get(i);
            boolean level=ahead.y==feet().y;
            for(int j=next;j<=i && level;j++) level=path.get(j).y==ahead.y;
            if(level && PathSearch.walkStraight(this,bot.mc.player.x,bot.mc.player.z,ahead)) {next=i;break;}
        }
        // A drop waypoint is not a stopping point. Target the checked lower
        // corridor before takeoff so airborne steering doesn't turn back to its center.
        if(!excavation)next=PathSearch.downhillLookahead(this,path,next,bot.mc.player.x,bot.mc.player.y,bot.mc.player.z);
        PathSearch.Pos waypoint = path.get(next);
        dx = waypoint.x + 0.5 - bot.mc.player.x; dz = waypoint.z + 0.5 - bot.mc.player.z;
        boolean turn=false;
        if(next+1<path.size()) {
            PathSearch.Pos ahead=path.get(next+1);
            turn=ahead.y!=waypoint.y || (Math.abs(dx)>Math.abs(dz) ? ahead.z!=waypoint.z : ahead.x!=waypoint.x);
        }
        double radius=turn ? .15 : .35;
        if(waypoint.equals(target)) radius=Math.min(radius,tolerance);
        if (bot.mc.player.onGround && dx * dx + dz * dz < radius*radius && Math.abs(bot.mc.player.y - waypoint.y) < .2) {
            next++;
            if (next >= path.size()) { bot.releaseMovement(); return false; }
            if(!excavation)next=PathSearch.downhillLookahead(this,path,next,bot.mc.player.x,bot.mc.player.y,bot.mc.player.z);
            waypoint = path.get(next);
            if(excavation && clearStep(waypoint)) return false;
            dx = waypoint.x + .5 - bot.mc.player.x; dz = waypoint.z + .5 - bot.mc.player.z;
        }
        if (!PathSearch.standing(this, waypoint.x, waypoint.y, waypoint.z)) {
            path = Collections.emptyList(); bot.releaseMovement(); return false;
        }
        double remaining=dx*dx+dz*dz+Math.pow(bot.mc.player.y-waypoint.y,2);
        if(!waypoint.equals(progressWaypoint)) {
            progressWaypoint=waypoint;bestWaypointDistance=remaining;waypointProgressTick=bot.ticks;
        } else if(remaining<bestWaypointDistance-.02) {
            bestWaypointDistance=remaining;waypointProgressTick=bot.ticks;
        } else if(bot.ticks-waypointProgressTick>80) {
            bot.releaseMovement();
            throw new TaskFailure("No progress toward walking step "+waypoint+"; rejecting repeated jumps.");
        }
        TravelSteering steering=TravelSteering.aim(dx,dz,bot.mc.player.velocityX,bot.mc.player.velocityZ,
                bot.mc.player.onGround,bot.mc.player.yaw);
        bot.mc.player.yaw=steering.yaw;
        bot.mc.player.pitch=10;
        boolean forward=steering.forward;
        holdSprint(forward,precision);
        bot.key(bot.mc.options.forwardKey,forward);
        bot.key(bot.mc.options.backKey,false);bot.key(bot.mc.options.leftKey,false);bot.key(bot.mc.options.rightKey,false);
        bot.key(bot.mc.options.sneakKey,false);
        boolean uphill=waypoint.y>bot.mc.player.y+.35;
        float direct=(float)Math.toDegrees(Math.atan2(-dx,dz));
        float difference=TravelSteering.wrap(direct-steering.yaw);
        // Repeat jumps whenever the checked travel corridor has landing/braking room.
        // Lava proximity, health, inventory and command type do not reduce travel speed.
        boolean travelJump=!precision && forward && !uphill && Math.abs(difference)<5 && waypoint.y==feet().y
                && bot.mc.player.getHungerManager().getFoodLevel()>6 && !bot.mc.player.isUsingItem()
                && PathSearch.sprintJumpClear(this,bot.mc.player.x,bot.mc.player.z,waypoint);
        if(travelJump && bot.mc.player.onGround) bot.travelSprintJumps++;
        bot.key(bot.mc.options.jumpKey,bot.mc.player.onGround && (travelJump || uphill && forward
                && Math.abs(difference)<15
                && (bot.mc.player.getHungerManager().getFoodLevel()>6 || dx*dx+dz*dz<=1.3225)));
        return false;
    }

    private void holdSprint(boolean forward,boolean precision) {
        bot.sprint(forward && !precision && bot.mc.player.getHungerManager().getFoodLevel()>6
                && !bot.mc.player.isUsingItem());
        // Keep the binding held through turns and jumps; vanilla still applies
        // hunger and crouching restrictions. Stops and pauses release it.
        bot.key(bot.mc.options.sprintKey,forward && !precision && bot.mc.player.getHungerManager().getFoodLevel()>6);
    }

    boolean approach(BlockPos block) {
        if (bot.mc.player.onGround && bot.hit(block) != null) { bot.releaseMovement();reset();return true; }
        if(!block.equals(approachBlock)) {
            approachBlock=block;approachGoal=null;approachCandidates=null;approachIndex=0;diggingApproaches=false;rejectedApproaches.clear();resetPath();
        }
        if(approachGoal==null) {
            if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
            PathSearch.Pos from=PathSearch.groundedStart(this,bot.mc.player.x,feet().y,bot.mc.player.z);
            if(approachCandidates==null) {
            List<PathSearch.Pos> candidates=new ArrayList<>();
            for(int x=block.getX()-2;x<=block.getX()+2;x++)
                for(int z=block.getZ()-2;z<=block.getZ()+2;z++)
                    for(int y=block.getY()-3;y<=block.getY()+1;y++) {
                        PathSearch.Pos p=new PathSearch.Pos(x,y,z);
                        if(rejectedApproaches.contains(p) || !PathSearch.standing(this,x,y,z)) continue;
                        net.minecraft.util.hit.BlockHitResult hit=bot.hitFrom(new Vec3d(x+.5,y+1.62,z+.5),block);
                        if(hit!=null && block.equals(hit.getBlockPos())) candidates.add(p);
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
        if(bot.protectsTowerBase(p) || !valid(p.getX(),p.getY(),p.getZ()) || p.equals(new BlockPos(bot.mc.player).down())
                || bot.mc.world.getBlockEntity(p)!=null) return false;
        BlockState state=bot.mc.world.getBlockState(p);Block block=state.getBlock();
        String id=String.valueOf(Block.REGISTRY.getIdentifier(block));
        if(!Arrays.asList("minecraft:dirt","minecraft:grass","minecraft:stone","minecraft:cobblestone",
                "minecraft:sandstone","minecraft:red_sandstone","minecraft:hardened_clay","minecraft:stained_hardened_clay",
                "minecraft:leaves","minecraft:leaves2","minecraft:log","minecraft:log2","minecraft:snow_layer").contains(id)
                && !(bot.strongholdLoot() && Arrays.asList("minecraft:stonebrick","minecraft:bookshelf","minecraft:planks","minecraft:web","minecraft:fence").contains(id))) return false;
        if(block.getMaterial()==Material.STONE) {
            boolean tool=false;
            for(net.minecraft.item.ItemStack stack:bot.mc.player.inventory.main)
                if(LegacyItems.usableTool(stack) && stack.isEffectiveOn(block)) {tool=true;break;}
            if(!tool) return false;
        }
        if(bot.mc.world.getBlockState(p.up()).getBlock() instanceof FallingBlock) return false;
        for(Direction d:Direction.values()) {
            BlockPos adjacent=p.offset(d);
            if(!bot.mc.world.isLoaded(adjacent,false) || bot.mc.world.getBlockState(adjacent).getBlock().getMaterial().isFluid()) return false;
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
                if(!LegacyNavigation.this.clear(block.getX(),block.getY(),block.getZ())) removed.add(block);
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
        if(!bot.mc.player.onGround) return false;
        List<BlockPos> obstacles=new ArrayList<>();
        if(next.y>feet().y) obstacles.add(new BlockPos(feet().x,feet().y+2,feet().z));
        obstacles.add(new BlockPos(next.x,next.y+1,next.z));
        obstacles.add(new BlockPos(next.x,next.y,next.z));
        boolean blocked=false;
        for(BlockPos p:obstacles) {
            if(clear(p.getX(),p.getY(),p.getZ())) continue;
            blocked=true;
            if(!safeBreak(p)) {bot.cancelMining();resetPath();throw new TaskFailure("Route obstruction cannot be safely mined at "+p);}
            if(bot.hit(p)!=null) {bot.mine(p);return true;}
        }
        if(blocked) {bot.cancelMining();resetPath();throw new TaskFailure("Route obstruction is out of mining reach");}
        bot.cancelMining();return false;
    }
}
