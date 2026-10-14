package adris.altoclef.legacy;

import net.minecraft.block.Block;
import adris.altoclef.legacy.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.*;

/** Digs vertical shafts and level corridors to underground resources. */
final class StoneAccessTask implements LegacyTask {
    private final List<PathSearch.Pos> steps;
    private int step, ticks;
    private boolean announced;
    private final boolean iron;
    private String resource;
    private PathSearch.Pos surfaceApproach;
    private StoneAccessTask(List<PathSearch.Pos> steps) { this(steps, false); }
    private StoneAccessTask(List<PathSearch.Pos> steps, boolean iron) { this.steps = steps; this.iron = iron; this.resource=iron ? "iron ore" : "stone"; }
    public String describe() { if(surfaceApproach!=null) return "walk above "+resource+" before digging down"; return "dig to " + resource + " (step " + (step + 1) + "/" + steps.size() + ")"; }
    public void stop(LegacyBot bot) { bot.cancelMining(); bot.releaseMovement(); bot.navigation.reset(); }

    static StoneAccessTask plan(LegacyBot bot) {
        return planAt(bot,bot.navigation.feet(),false);
    }
    static StoneAccessTask planDeep(LegacyBot bot) {return planAt(bot,bot.navigation.feet(),true);}
    private static StoneAccessTask planAt(LegacyBot bot,PathSearch.Pos start,boolean denseCorridor) {
        if (!PathSearch.standing(bot.navigation, start.x, start.y, start.z)) return null;
        List<PathSearch.Pos> route=new ArrayList<>();
        for(int depth=1;depth<=8;depth++) {
            PathSearch.Pos next=new PathSearch.Pos(start.x,start.y-depth,start.z);
            if(!safeStep(bot,next,false)) return null;
            route.add(next);
            BlockState state=LegacyWorld.state(bot.mc.world,new BlockPos(next.x,next.y,next.z));
            String name=String.valueOf(Block.REGISTRY.getId(state.getBlock()));
            if((name.equals("minecraft:stone") && state.getData()==0) || name.equals("minecraft:cobblestone")) {
                // Stop one block deeper when that produces a two-high stone
                // corridor. Each horizontal step then yields two cobblestone
                // instead of wasting the upper mining action on surface dirt.
                PathSearch.Pos lower=new PathSearch.Pos(next.x,next.y-1,next.z);
                if(denseCorridor && safeStep(bot,lower,false)) {
                    BlockState lowerState=LegacyWorld.state(bot.mc.world,new BlockPos(lower.x,lower.y,lower.z));
                    String lowerName=String.valueOf(Block.REGISTRY.getId(lowerState.getBlock()));
                    if((lowerName.equals("minecraft:stone") && lowerState.getData()==0)
                            || lowerName.equals("minecraft:cobblestone")) route.add(lower);
                }
                return new StoneAccessTask(route);
            }
        }
        return null;
    }
    static LegacyTask nearby(LegacyBot bot) {
        PathSearch.Pos from=bot.navigation.feet();
        int searches=0;
        for(int radius=1;radius<=12;radius++) for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
            for(int dy=0;dy<=4;dy++) for(int sign:new int[]{1,-1}) {
                PathSearch.Pos p=new PathSearch.Pos(from.x+dx,from.y+dy*sign,from.z+dz);
                if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z) || planAt(bot,p,false)==null) continue;
                List<PathSearch.Pos> walk=PathSearch.find(bot.navigation,from,p,1200);
                if(!walk.isEmpty() && walk.get(walk.size()-1).equals(p)) return new LegacyTask() {
                    private StoneAccessTask dig;
                    public String describe() {return dig==null ? "walk to nearby safe stone shaft" : dig.describe();}
                    public void stop(LegacyBot b) {if(dig!=null) dig.stop(b);b.releaseMovement();b.navigation.reset();}
                    public boolean tick(LegacyBot b) {
                        if(dig!=null) return dig.tick(b);
                        if(b.navigation.move(p,.2)) {
                            dig=plan(b);
                            if(dig==null) throw new TaskFailure("Nearby stone shaft changed before mining.");
                        }
                        return false;
                    }
                };
                if(++searches>=12) return null;
            }
        }
        return null;
    }
    static StoneAccessTask planOre(LegacyBot bot,BlockPos ore,String resource) {
        StoneAccessTask result=planIron(bot,ore);
        if(result!=null) {
            result.resource=resource;
            result=preferSurfaceApproach(bot,result);
        }
        return result;
    }
    private static int diggingCost(LegacyBot bot,List<PathSearch.Pos> route) {
        int cost=0;
        for(PathSearch.Pos p:route) {
            cost++;
            for(int dy=0;dy<=1;dy++) if(!bot.navigation.clear(p.x,p.y+dy,p.z)) cost+=10;
        }
        return cost;
    }
    private static StoneAccessTask preferSurfaceApproach(LegacyBot bot,StoneAccessTask tunnel) {
        PathSearch.Pos from=bot.navigation.feet(),goal=tunnel.steps.get(tunnel.steps.size()-1);
        if(Math.abs(from.x-goal.x)+Math.abs(from.z-goal.z)<4) return tunnel;
        int surface=SurfaceAccessTask.terrainSurface(bot,goal.x,goal.z);
        if(surface<=goal.y || surface-goal.y>24) return tunnel;
        PathSearch.Pos above=new PathSearch.Pos(goal.x,surface,goal.z);
        if(!PathSearch.standing(bot.navigation,above.x,above.y,above.z)) return tunnel;
        List<PathSearch.Pos> shaft=new ArrayList<>();
        for(int y=surface-1;y>=goal.y;y--) {
            PathSearch.Pos p=new PathSearch.Pos(goal.x,y,goal.z);
            if(!safeStep(bot,p,true)) return tunnel;
            shaft.add(p);
        }
        List<PathSearch.Pos> walk=PathSearch.find(bot.navigation,from,above,6000);
        if(walk.isEmpty() || !walk.get(walk.size()-1).equals(above)
                || walk.size()+diggingCost(bot,shaft)>=diggingCost(bot,tunnel.steps)) return tunnel;
        StoneAccessTask result=new StoneAccessTask(shaft,true);
        result.resource=tunnel.resource;result.surfaceApproach=above;return result;
    }
    /** Lower an isolated pillar one block at a time, retaining a solid landing. */
    static StoneAccessTask leavePillar(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        if(!bot.mc.field_3805.onGround || SurfaceAccessTask.underground(bot)) return null;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            for(int dy=-2;dy<=1;dy++)
                if(PathSearch.standing(bot.navigation,feet.x+d[0],feet.y+dy,feet.z+d[1])) return null;
        for(int depth=3;depth<=24;depth++)
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                if(PathSearch.standing(bot.navigation,feet.x+d[0],feet.y-depth,feet.z+d[1]))
                    return planOre(bot,new BlockPos(feet.x,feet.y-1,feet.z),"safe pillar descent");
        return null;
    }
    static StoneAccessTask leaveCanopy(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        if(!bot.mc.field_3805.onGround) return null;
        String support=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,
                new BlockPos(feet.x,feet.y-1,feet.z)).getBlock()));
        if(!(support.contains("leaves") || support.equals("minecraft:log") || support.equals("minecraft:log2"))) return null;
        for(int r=0;r<=6;r++) for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r) continue;
            int x=feet.x+dx,z=feet.z+dz,ground=SurfaceAccessTask.terrainSurface(bot,x,z);
            if(ground<1 || ground>=feet.y-2 || feet.y-ground>24) continue;
            String base=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,new BlockPos(x,ground,z)).getBlock()));
            if(!base.equals("minecraft:log") && !base.equals("minecraft:log2")) continue;
            StoneAccessTask route=planOre(bot,new BlockPos(x,ground,z),"tree canopy descent");
            if(route!=null) return route;
        }
        return null;
    }
    /** Bounded digging search: level corridors and vertical one-block descents. */
    static StoneAccessTask planIron(LegacyBot bot, BlockPos ore) {
        PathSearch.Pos start = bot.navigation.feet();
        if (!PathSearch.standing(bot.navigation, start.x, start.y, start.z)) return null;
        PathSearch.Pos goal = new PathSearch.Pos(ore.x, ore.y, ore.z);
        if (goal.y > start.y || start.y - goal.y > 24) return null;
        Map<PathSearch.Pos, PathSearch.Pos> previous = new HashMap<>();
        Map<PathSearch.Pos, Integer> cost = new HashMap<>();
        class Node {
            final PathSearch.Pos p; final int g, score;
            Node(PathSearch.Pos p, int g) { this.p=p; this.g=g; score=g+Math.abs(p.x-goal.x)+Math.abs(p.z-goal.z)+Math.abs(p.y-goal.y); }
        }
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.<Node>comparingInt(n -> n.score).thenComparingInt(n -> n.p.y));
        open.add(new Node(start,0)); cost.put(start,0);
        int budget=4096;
        while (!open.isEmpty() && budget-- > 0) {
            Node n=open.poll();
            if (n.g != cost.get(n.p)) continue;
            if (n.p.equals(goal)) {
                List<PathSearch.Pos> route=new ArrayList<>();
                for (PathSearch.Pos p=goal; !p.equals(start); p=previous.get(p)) route.add(p);
                Collections.reverse(route);
                return route.isEmpty() ? null : new StoneAccessTask(route,true);
            }
            for (int[] d : new int[][]{{0,-1,0},{0,-2,0},{0,-3,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}) {
                PathSearch.Pos p=new PathSearch.Pos(n.p.x+d[0],n.p.y+d[1],n.p.z+d[2]);
                if (p.y<goal.y || Math.abs(p.x-start.x)>24 || Math.abs(p.z-start.z)>24 || n.g>=64) continue;
                int g=n.g+1;
                if (g>=cost.getOrDefault(p,Integer.MAX_VALUE) || !safeStep(bot,p,true)) continue;
                cost.put(p,g); previous.put(p,n.p); open.add(new Node(p,g));
            }
        }
        return null;
    }
    private static boolean safeStep(LegacyBot bot, PathSearch.Pos p, boolean iron) {
        if (p.y < 1 || !bot.navigation.floor(p.x, p.y - 1, p.z)) return false;
        BlockPos ceiling = new BlockPos(p.x, p.y + 3, p.z);
        if (!LegacyWorld.isLoaded(bot.mc.world,ceiling, false)) return false;
        Block above = LegacyWorld.state(bot.mc.world,ceiling).getBlock();
        if (above instanceof net.minecraft.block.FallingBlock || above.getMaterial().isFluid()) return false;
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos pos = new BlockPos(p.x, p.y + dy, p.z);
            if (!LegacyWorld.isLoaded(bot.mc.world,pos, false)) return false;
            BlockState state = LegacyWorld.state(bot.mc.world,pos);
            String id = String.valueOf(Block.REGISTRY.getId(state.getBlock()));
            if (!bot.navigation.clear(pos.x,pos.y,pos.z) && !id.equals("minecraft:dirt") && !id.equals("minecraft:grass")
                    && !id.equals("minecraft:stone") && !id.equals("minecraft:cobblestone")
                    && !id.equals("minecraft:sandstone") && !id.equals("minecraft:stone_slab")
                    && !id.equals("minecraft:double_stone_slab") && !id.equals("minecraft:sandstone_stairs")
                    && !id.equals("minecraft:wool") && !id.equals("minecraft:log") && !id.equals("minecraft:log2")
                    && !id.equals("minecraft:leaves") && !id.equals("minecraft:leaves2") && !(!iron && id.equals("minecraft:sand"))
                    && !(iron && (id.equals("minecraft:iron_ore") || id.equals("minecraft:coal_ore")
                            || id.equals("minecraft:sandstone") || id.equals("minecraft:stained_hardened_clay")
                            || id.equals("minecraft:hardened_clay") || id.equals("minecraft:planks")))) return false;
            for (Direction d : Direction.values()) {
                BlockPos adjacent = LegacyPos.offset(pos,d);
                if (!LegacyWorld.isLoaded(bot.mc.world,adjacent, false) || LegacyWorld.state(bot.mc.world,adjacent).getBlock().getMaterial().isFluid()) return false;
            }
        }
        return true;
    }
    public boolean tick(LegacyBot bot) {
        if(surfaceApproach!=null) {
            if(++ticks>2400) throw new TaskFailure("Could not reach the surface mining approach.");
            if(bot.navigation.move(surfaceApproach,.1)) {
                bot.releaseMovement();bot.navigation.reset();surfaceApproach=null;
            }
            return false;
        }
        if (!announced) {
            if(!(iron && resource.equals("stone") && steps.size()==1))
                bot.say(iron ? "Digging to underground "+resource+"." : "Digging straight down to underground stone.");
            announced=true;
        }
        if (++ticks > 2400) throw new TaskFailure("Could not finish the underground access route.");
        if (step >= steps.size()) return true;
        PathSearch.Pos next = steps.get(step);
        if (!safeStep(bot, next, iron)) throw new TaskFailure("Mining route terrain changed or is unsafe.");
        PathSearch.Pos feet=bot.navigation.feet();
        if(next.x==feet.x && next.z==feet.z) {
            if(bot.mc.field_3805.onGround && feet.y==next.y) {step++;return step>=steps.size();}
            if(!bot.mc.field_3805.onGround) {bot.releaseMovement();bot.cancelMining();return false;}
            if(feet.y<next.y+1 || feet.y>next.y+3) throw new TaskFailure("Unexpected height in the mining shaft: feet="+feet+", next="+next+", y="+bot.mc.field_3805.boundingBox.minY);
            // At a block edge the player's body can still rest on the neighboring floor
            // after this column is mined. Move fully inside the shaft before descending.
            double dx=next.x+.5-bot.mc.field_3805.x, dz=next.z+.5-bot.mc.field_3805.z;
            if(dx*dx+dz*dz>.0225) {
                bot.cancelMining();bot.releaseMovement();
                bot.look(next.x+.5,bot.mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(bot.mc.field_3805),next.z+.5);
                bot.key(bot.mc.options.forwardKey,true);
                bot.key(bot.mc.options.sneakKey,true);
                return false;
            }
            BlockPos floor=new BlockPos(next.x,feet.y-1,next.z);
            // safeStep verifies the landing beneath a fall of at most three
            // blocks, including the small air gaps inside temple roofs.
            bot.mine(floor);return false;
        }
        // A level corridor needs two blocks of clearance. Trying the third
        // block first can target a face hidden behind the current low ceiling.
        for (int dy = 1; dy >= 0; dy--) {
            BlockPos pos = new BlockPos(next.x, next.y + dy, next.z);
            if (bot.navigation.clear(pos.x,pos.y,pos.z)) continue;
            if (pos.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805)))) throw new TaskFailure("Cannot dig beneath the player.");
            if (bot.hit(pos) == null) {
                if(bot.clearMiningObstruction(pos)) return false;
                throw new TaskFailure("Mining route block is out of reach at "+pos+" from "+feet);
            }
            bot.mine(pos); return false;
        }
        bot.cancelMining();
        if (bot.navigation.move(next, .3)) step++;
        return step >= steps.size();
    }
}
