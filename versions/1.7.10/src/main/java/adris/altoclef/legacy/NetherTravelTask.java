package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;

/** Executes short, budgeted walk/dig/bridge segments over known Nether terrain. */
final class NetherTravelTask implements LegacyTask {
    private final PathSearch.Pos goal;
    private final boolean fortressFloor;
    private final Set<PathSearch.Pos> blockedSteps=new HashSet<>();
    private List<PathSearch.Pos> route=Collections.emptyList();
    private PathSearch.Pos segmentStart;
    private final ArrayDeque<PathSearch.Pos> supports=new ArrayDeque<>();
    private LegacyTask gather;
    private int next,ticks,waitUntil,placeTicks;
    private boolean descended,sealed;
    private String status="plan route";
    NetherTravelTask(BlockPos goal) {this(goal,true);}
    NetherTravelTask(BlockPos goal,boolean fortressFloor) {this.goal=new PathSearch.Pos(goal.x,goal.y,goal.z);this.fortressFloor=fortressFloor;}
    public String describe() {return (fortressFloor ? "travel to fortress " : "travel to portal ")+goal+" -> "+status+(gather==null ? "" : " -> "+gather.describe());}
    public void stop(LegacyBot bot) {if(gather!=null) gather.stop(bot);bot.releaseMovement();bot.cancelMining();bot.navigation.reset();}
    private boolean diggable(LegacyBot bot,int x,int y,int z) {
        BlockPos p=new BlockPos(x,y,z);
        if(x==goal.x && y==goal.y-1 && z==goal.z) return false;
        if(!LegacyWorld.isLoaded(bot.mc.world,p,false) || y<2 || y>124) return false;
        String id=String.valueOf(Block.REGISTRY.getId(LegacyWorld.state(bot.mc.world,p).getBlock()));
        if(!id.equals("minecraft:netherrack") && !id.equals("minecraft:nether_brick")) return false;
        for(Direction d:Direction.values()) if(LegacyWorld.state(bot.mc.world,LegacyPos.offset(p,d)).getBlock().getMaterial().isFluid()) return false;
        return true;
    }
    private boolean buildable(LegacyBot bot,int x,int y,int z) {
        BlockPos p=new BlockPos(x,y,z);
        return y>31 && y<120 && LegacyWorld.isLoaded(bot.mc.world,p,false)
                && (LegacyWorld.isAir(bot.mc.world,p) || !fortressFloor
                    && LegacyWorld.state(bot.mc.world,p).getBlock().getMaterial()==Material.LAVA
                    && bot.navigation.floor(x,y-1,z));
    }
    private PathSearch.Terrain terrain(LegacyBot bot) {
        return new PathSearch.Terrain() {
            public boolean clear(int x,int y,int z) {return !blockedSteps.contains(new PathSearch.Pos(x,y,z)) && (bot.navigation.clear(x,y,z) || diggable(bot,x,y,z));}
            public boolean floor(int x,int y,int z) {return bot.navigation.floor(x,y,z) || buildable(bot,x,y,z);}
            public double clearanceCost(int x,int y,int z) {
                return (bot.navigation.clear(x,y,z) ? 0 : 8)+(bot.navigation.floor(x,y-1,z) ? 0 : 8);
            }
            public boolean canStep(PathSearch.Pos from,PathSearch.Pos to) {
                if(bot.navigation.floor(to.x,to.y-1,to.z)) return true;
                return to.y==from.y || !fortressFloor && to.y==from.y+1
                        && bot.navigation.floor(to.x,to.y-2,to.z);
            }
        };
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>36000) throw new TaskFailure("Fortress travel exceeded its time limit.");
        if(gather!=null) {
            if(gather.tick(bot)) {gather.stop(bot);gather=null;route=Collections.emptyList();bot.navigation.reset();}
            return false;
        }
        if(!bot.mc.field_3805.onGround) {
            // Keep steering toward the checked landing during a jump. Releasing
            // forward on takeoff strands the player against a one-block step.
            if(next<route.size() && supports.isEmpty()) bot.navigation.move(route.get(next),.18);
            else bot.releaseMovement();
            return false;
        }
        PathSearch.Pos feet=bot.navigation.feet();
        if(descended && !sealed && feet.y<=goal.y+1) {
            sealed=true;status="seal descent shaft against lava";
            String seal="netherrack";
            for(String material:new String[]{"netherrack","cobblestone","dirt"})
                if(bot.count(LegacyItems.parse(material))>0) {seal=material;break;}
            gather=new LegacyPlaceTask(new BlockPos(feet.x,feet.y+2,feet.z),seal,true);return false;
        }
        if(feet.equals(goal) && (!fortressFloor && PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z) || LegacyWorld.state(bot.mc.world,new BlockPos(feet.x,feet.y-1,feet.z)).getBlock()==Block.get("nether_brick"))) return true;
        if(feet.y>goal.y && Math.abs(feet.x-goal.x)+Math.abs(feet.z-goal.z)<=2
                && descend(bot,feet)) return false;
        if(!supports.isEmpty()) {status="bridge: "+supports.size()+" blocks left in this step";place(bot,supports.peek());return false;}
        if(next>=route.size()) {
            // A portal above a lava-covered ceiling may require walking away
            // from it to reach a dry ascent. Allow that bounded local detour.
            route=PathSearch.excavate(terrain(bot),feet,goal,!fortressFloor && feet.distance(goal)<16 ? 24000 : 8000);next=0;
            segmentStart=feet;
            if(route.isEmpty() || route.get(route.size()-1).distance(goal)>=feet.distance(goal))
                throw new TaskFailure("No safe loaded route toward "+(fortressFloor ? "fortress" : "portal")+" from "+feet);
            // Budget a whole crossing through its landing, never just the
            // first 24 blocks of an unfinished bridge over lava.
            int end=-1;
            for(int i=0;i<route.size();i++) {
                PathSearch.Pos p=route.get(i);
                if(bot.navigation.floor(p.x,p.y-1,p.z)) {end=i;if(i>=23) break;}
            }
            if(end<0) throw new TaskFailure("No supported bridge landing found in loaded route; staying on land.");
            route=new ArrayList<>(route.subList(0,end+1));
            int blocks=NetherBridgeBudget.supports(bot.navigation,feet,route).size();
            int required=NetherBridgeBudget.required(blocks);
            if(blocks>0) bot.say("Bridge section needs "+blocks+" blocks + 8 reserve; carrying "+bot.count(LegacyItems.parse("netherrack"))+" netherrack.");
            if(bot.count(LegacyItems.parse("netherrack"))<required) {
                status="gather bridge budget "+required;gather=new AcquisitionTask(LegacyItems.parse("netherrack"),required,"get");return false;
            }
        }
        PathSearch.Pos step=route.get(next);
        if(feet.distance(step)>4) {route=Collections.emptyList();next=0;return false;}
        List<BlockPos> clear=new ArrayList<>();
        if(step.y>feet.y) clear.add(new BlockPos(feet.x,feet.y+2,feet.z));
        for(int y=Math.max(feet.y+1,step.y+1);y>=step.y;y--) clear.add(new BlockPos(step.x,y,step.z));
        for(BlockPos p:clear) if(!bot.navigation.clear(p.x,p.y,p.z)) {
            if(!diggable(bot,p.x,p.y,p.z)) {route=Collections.emptyList();next=0;return false;}
            status="clear safe passage";
            try {bot.mine(p);} catch(TaskFailure blocked) {
                if(blockedSteps.size()>=32) throw blocked;
                blockedSteps.add(step);route=Collections.emptyList();next=0;
                bot.cancelMining();bot.navigation.reset();
            }
            return false;
        }
        bot.cancelMining();
        if(!bot.navigation.floor(step.x,step.y-1,step.z)) {
            supports.addAll(NetherBridgeBudget.supports(bot.navigation,next==0 ? segmentStart : route.get(next-1),Collections.singletonList(step)));
            placeTicks=0;return false;
        }
        status="walk segment "+(next+1)+"/"+route.size();
        if(bot.navigation.move(step,.18)) next++;
        return false;
    }
    private boolean descend(LegacyBot bot,PathSearch.Pos feet) {
        BlockPos floor=new BlockPos(feet.x,feet.y-1,feet.z);
        if(!diggable(bot,floor.x,floor.y,floor.z)) return false;
        int landing=-1;
        for(int depth=1;depth<=4;depth++) {
            int y=feet.y-depth;
            if(depth>1 && !bot.navigation.clear(feet.x,y,feet.z)) return false;
            if(bot.navigation.floor(feet.x,y-1,feet.z)) {landing=y;break;}
        }
        if(landing<goal.y || landing<0 || feet.y-landing>3 && bot.mc.field_3805.getHealth()<=6) return false;
        // Every cell of the fall and its solid landing is checked before mining.
        // A four-block fall into a fortress corridor is allowed at healthy HP.
        for(int y=landing;y<feet.y;y++) for(Direction side:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST})
            if(LegacyWorld.state(bot.mc.world,LegacyPos.offset(new BlockPos(feet.x,y,feet.z),side)).getBlock().getMaterial().isFluid()) return false;
        double dx=feet.x+.5-bot.mc.field_3805.x,dz=feet.z+.5-bot.mc.field_3805.z;
        status="dig down to fortress, checked landing Y="+landing;
        bot.releaseMovement();
        if(dx*dx+dz*dz>.0225) {
            bot.look(feet.x+.5,bot.mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(bot.mc.field_3805),feet.z+.5);
            bot.key(bot.mc.options.sneakKey,true);bot.key(bot.mc.options.forwardKey,true);return true;
        }
        descended=true;route=Collections.emptyList();next=0;bot.mine(floor);return true;
    }
    private void place(LegacyBot bot,PathSearch.Pos p) {
        if(bot.ticks<waitUntil) return;
        if(bot.navigation.floor(p.x,p.y,p.z)) {supports.remove();placeTicks=0;return;}
        if(++placeTicks>100) throw new TaskFailure("Bridge placement blocked at "+p);
        int slot=bot.findInventory(LegacyItems.parse("netherrack"));
        if(slot<0) throw new TaskFailure("Bridge reserve exhausted; stopped on supported ground.");
        BlockPos target=new BlockPos(p.x,p.y,p.z);
        if(!buildable(bot,p.x,p.y,p.z)) throw new TaskFailure("Bridge route changed at "+p);
        bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.sneakKey,true);
        Vec3d eye=Vec3d.of(bot.mc.field_3805.x,bot.mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(bot.mc.field_3805),bot.mc.field_3805.z);
        for(Direction side:Direction.values()) {
            BlockPos support=LegacyPos.offset(target,side);Direction face=LegacyDirection.opposite(side);
            if(!bot.navigation.floor(support.x,support.y,support.z)) continue;
            Vec3d aim=Vec3d.of(support.x+.5+face.getOffsetX()*.499,support.y+.5+face.getOffsetY()*.499,support.z+.5+face.getOffsetZ()*.499);
            BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
            if(hit==null || !support.equals(LegacyPos.hit(hit)) || net.minecraft.util.math.Direction.getById(hit.side)!=face || LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,support)>20) continue;
            bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);LegacyFluidTask.syncAim(bot);
            LegacyInteraction.rightClick(bot.mc.interactionManager,bot.mc.field_3805,bot.mc.world,bot.mc.field_3805.inventory.getMainHandStack(),support,face,hit.pos);
            waitUntil=bot.ticks+4;return;
        }
        // Crouch to the edge until the support's side face is visible. Sneak
        // prevents walking off; never sprint/jump while extending a bridge.
        if(LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,target)>9) throw new TaskFailure("Bridge support is out of reach at "+p);
        bot.look(p.x+.5,bot.mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(bot.mc.field_3805),p.z+.5);
        bot.key(bot.mc.options.forwardKey,true);
    }
}
