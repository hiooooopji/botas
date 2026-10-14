package adris.altoclef.legacy;

import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Builds the first section exactly where it was demonstrated, relative to a safe site. */
final class LegacyTowerBaseTask implements LegacyTask {
    private LegacyTask child;
    private BlockPos origin;
    private ArrayDeque<int[]> sites;
    private int stage, ticks, digDepth, placeIndex, nextAction, lavaAttempts, repairPasses;
    private boolean lowerAscentDone;
    private String status="gather tower materials";

    BlockPos origin() { return origin; }
    boolean lowerAscentDone() { return lowerAscentDone; }
    boolean protects(BlockPos pos) {
        if(origin==null) return false;
        for(TowerBasePlan.Cell cell:TowerBasePlan.PLACEMENTS)
            if(cell.at(origin).equals(pos)) return true;
        return pos.equals(origin.down()) || pos.equals(origin.down(3));
    }
    public String describe() { return "tower base -> "+status+(child==null ? "" : " -> "+child.describe()); }
    public void stop(LegacyBot bot) {
        if(child!=null) child.stop(bot);
        bot.cancelMining();bot.releaseMovement();bot.navigation.reset();
    }

    private boolean obtain(LegacyBot bot, String id, int amount) {
        LegacyItems.Target target=LegacyItems.parse(id);
        if(bot.count(target)>=amount) return true;
        child=new AcquisitionTask(target,amount,"get");return false;
    }

    private boolean supplies(LegacyBot bot) {
        status="gather 8 slabs, leaves, trapdoor, chest and lava";
        if(!obtain(bot,"wooden_slab",8)) return false;
        if(bot.count(LegacyItems.anyLeaves())<TowerBasePlan.leaves()+12) {
            child=new AcquisitionTask(LegacyItems.anyLeaves(),TowerBasePlan.leaves()+12,"get");return false;
        }
        if(!obtain(bot,"trapdoor",1) || !obtain(bot,"chest",1)) return false;
        if(bot.count(LegacyItems.parse("lava_bucket"))==0) {
            if(!obtain(bot,"bucket",1)) return false;
            child=new LegacyFluidTask("lava");return false;
        }
        return true;
    }

    private boolean safeSite(LegacyBot bot, BlockPos p) {
        if(p.getY()<4 || p.getY()>245) return false;
        for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) {
            if(!bot.navigation.floor(p.getX()+x,p.getY()-1,p.getZ()+z)) return false;
            for(int y=0;y<=5;y++) if(!bot.navigation.clear(p.getX()+x,p.getY()+y,p.getZ()+z)) return false;
        }
        for(int d=1;d<=3;d++) {
            BlockPos shaft=p.down(d);
            Block block=bot.mc.world.getBlockState(shaft).getBlock();
            if(block==Block.get("bedrock") || block.getMaterial().isFluid()
                    || bot.mc.world.getBlockEntity(shaft)!=null) return false;
        }
        return bot.navigation.floor(p.getX(),p.getY()-4,p.getZ());
    }

    private boolean findSite(LegacyBot bot) {
        status="find a flat, solid tower site";
        if(sites==null) {
            sites=new ArrayDeque<>();PathSearch.Pos feet=bot.navigation.feet();
            for(int r=0;r<=24;r++) for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++)
                if(Math.max(Math.abs(dx),Math.abs(dz))==r) sites.add(new int[]{feet.x+dx,feet.z+dz});
        }
        PathSearch.Pos feet=bot.navigation.feet();
        for(int i=0;i<20 && !sites.isEmpty();i++) {
            int[] spot=sites.removeFirst();int y=SurfaceAccessTask.terrainSurface(bot,spot[0],spot[1]);
            BlockPos candidate=new BlockPos(spot[0],y,spot[1]);
            if(!safeSite(bot,candidate)) continue;
            PathSearch.Pos stand=new PathSearch.Pos(candidate.getX()+1,y,candidate.getZ());
            List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,stand,3000);
            if(!feet.equals(stand) && (route.isEmpty() || !route.get(route.size()-1).equals(stand))) continue;
            origin=candidate;bot.say("Tower base center: "+origin+"; shaft depth 3.");return true;
        }
        if(sites.isEmpty()) throw new TaskFailure("No flat, solid tower site within 24 blocks.");
        return false;
    }

    private boolean lavaVisible(LegacyBot bot, Vec3d eye, BlockPos target, boolean pour) {
        for(Direction side:new Direction[]{Direction.WEST,Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.DOWN}) {
            BlockPos support=target.offset(side);
            BlockState state=bot.mc.world.getBlockState(support);
            if(state.getBlock().getCollisionBox(bot.mc.world,support,state)==null) continue;
            Direction face=side.getOpposite();
            for(double height:new double[]{.85,.5,.15}) {
                Vec3d aim=new Vec3d(support.getX()+.5+face.getOffsetX()*.499,
                        support.getY()+height+face.getOffsetY()*.499,
                        support.getZ()+.5+face.getOffsetZ()*.499);
                double dx=eye.x-aim.x,dy=eye.y-aim.y,dz=eye.z-aim.z;
                if(dx*dx+dy*dy+dz*dz>20.25) continue;
                BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
                if(hit==null || !support.equals(hit.getBlockPos()) || hit.direction!=face) continue;
                if(pour) {
                    int slot=bot.findInventory(LegacyItems.parse("lava_bucket"));
                    if(slot<0) throw new TaskFailure("Lava bucket disappeared before pouring into tower shaft.");
                    bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
                    LegacyFluidTask.syncAim(bot);
                    bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,
                            bot.mc.player.inventory.getMainHandStack());
                    nextAction=bot.ticks+12;lavaAttempts++;
                }
                return true;
            }
        }
        return false;
    }

    private void pourLava(LegacyBot bot) {
        status="place lava at bottom of three-block shaft";
        BlockPos target=origin.down(3);
        if(LegacyFluidTask.source(bot.mc.world.getBlockState(target),"lava")) {stage=5;return;}
        if(bot.ticks<nextAction) return;
        if(lavaAttempts>=3 || bot.count(LegacyItems.parse("lava_bucket"))==0)
            throw new TaskFailure("Lava was not placed at the bottom of the tower shaft.");
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        if(lavaVisible(bot,eye,target,true)) return;
        PathSearch.Pos feet=bot.navigation.feet(),best=null;
        for(int[] offset:new int[][]{{1,0},{0,1},{-1,0},{0,-1},{2,0},{0,2}}) {
            PathSearch.Pos stance=new PathSearch.Pos(origin.getX()+offset[0],origin.getY(),origin.getZ()+offset[1]);
            if(!PathSearch.standing(bot.navigation,stance.x,stance.y,stance.z)) continue;
            Vec3d from=new Vec3d(stance.x+.5,stance.y+bot.mc.player.getEyeHeight(),stance.z+.5);
            if(!lavaVisible(bot,from,target,false)) continue;
            List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,stance,2000);
            if(feet.equals(stance) || !route.isEmpty() && route.get(route.size()-1).equals(stance)) {best=stance;break;}
        }
        if(best==null) throw new TaskFailure("No reachable view into the tower shaft for lava placement.");
        child=new MovementTasks.GoTo(best.x,best.y,best.z);
    }

    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension!=0) throw new TaskFailure("Build the tower base in the Overworld.");
        if(++ticks>72000) throw new TaskFailure("Tower base construction timed out.");
        if(child!=null) {
            if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}
            return false;
        }
        if(stage==0) {if(supplies(bot)) stage=1;return false;}
        if(stage==1) {
            if(findSite(bot)) {child=new MovementTasks.GoTo(origin.getX()+1,origin.getY(),origin.getZ());stage=2;}
            return false;
        }
        if(stage==2) {stage=3;return false;}
        if(stage==3) {
            status="dig tower shaft "+digDepth+"/3";
            if(digDepth==3) {bot.cancelMining();stage=4;return false;}
            BlockPos pos=origin.down(digDepth+1);
            if(bot.mc.world.isAir(pos)) {digDepth++;return false;}
            if(bot.hit(pos)==null) {
                // The third block is just beyond reach from the middle of the
                // neighboring square. Edge toward the opening while the entire
                // player's center remains on the solid east bank.
                double edge=origin.getX()+1.08;
                if(digDepth==2 && bot.mc.player.onGround && bot.mc.player.x>edge
                        && Math.abs(bot.mc.player.z-(origin.getZ()+.5))<.25) {
                    bot.look(origin.getX()+.5,bot.mc.player.y+bot.mc.player.getEyeHeight(),origin.getZ()+.5);
                    bot.key(bot.mc.options.forwardKey,true);return false;
                }
                bot.releaseMovement();
                throw new TaskFailure("Cannot safely reach shaft block "+pos+" from the side.");
            }
            bot.releaseMovement();
            bot.mine(pos);return false;
        }
        if(stage==4) {pourLava(bot);return false;}
        if(stage==5) {
            status="cover lava shaft with trapdoor";
            BlockPos trapdoor=origin.down();
            if(bot.mc.world.getBlockState(trapdoor).getBlock()!=Block.get("trapdoor")) {
                child=new LegacyPlaceTask(trapdoor,"trapdoor");return false;
            }
            stage=6;return false;
        }
        if(stage==6) {
            status="build recorded slab and leaf rings "+placeIndex+"/"+TowerBasePlan.PLACEMENTS.size();
            if(placeIndex==18 && !lowerAscentDone) {stage=12;return false;}
            if(placeIndex<TowerBasePlan.PLACEMENTS.size()) {
                TowerBasePlan.Cell cell=TowerBasePlan.PLACEMENTS.get(placeIndex);
                BlockPos pos=cell.at(origin);
                if(TowerBasePlan.matches(bot.mc.world,origin,cell)) {placeIndex++;return false;}
                if(!bot.mc.world.isAir(pos)) throw new TaskFailure("Tower blueprint cell is occupied: "+pos);
                if(placeIndex>=20) {
                    // Work from the completed lower leaf ring, under the new roof.
                    // The column occupies the first roof cell's floor position.
                    int sx=cell.x==0 && cell.z==-1 ? 1 : cell.x;
                    int sz=cell.x==0 && cell.z==-1 ? -1 : cell.z;
                    PathSearch.Pos stance=new PathSearch.Pos(origin.getX()+sx,origin.getY()+2,origin.getZ()+sz);
                    if(!bot.navigation.feet().equals(stance)) {
                        child=new MovementTasks.GoTo(stance.x,stance.y,stance.z);return false;
                    }
                }
                String item=cell.block;
                if(item.equals("leaves")) {
                    int slot=bot.findInventory(LegacyItems.anyLeaves());
                    if(slot<0) throw new TaskFailure("Tower leaves ran out before the base was finished.");
                    item=LegacyItems.id(bot.mc.player.inventory.main[slot].getItem());
                }
                child=new LegacyPlaceTask(pos,item);return false;
            }
            stage=7;return false;
        }
        if(stage==8) {
            status="move along lower ring for final ascent";
            child=new MovementTasks.GoTo(origin.getX()+1,origin.getY()+2,origin.getZ());
            stage=9;return false;
        }
        if(stage==9) {
            status="place upper-ring access leaf";
            BlockPos access=origin.add(2,1,0);
            Block block=bot.mc.world.getBlockState(access).getBlock();
            if(block==Block.get("leaves") || block==Block.get("leaves2")) {stage=10;return false;}
            if(!bot.mc.world.isAir(access)) throw new TaskFailure("Upper-ring access is blocked at "+access);
            int slot=bot.findInventory(LegacyItems.anyLeaves());
            if(slot<0) throw new TaskFailure("No leaf available for upper-ring access.");
            child=new LegacyPlaceTask(access,LegacyItems.id(bot.mc.player.inventory.main[slot].getItem()));
            return false;
        }
        if(stage==10) {
            status="step onto upper-ring access leaf";
            child=new MovementTasks.GoTo(origin.getX()+2,origin.getY()+2,origin.getZ());
            stage=11;return false;
        }
        if(stage==11) {
            status="climb alongside the tower";
            child=new SurfaceAccessTask(origin.getY()+5,true);
            stage=16;return false;
        }
        if(stage==16) {
            status="step onto the upper leaf ring";
            child=new MovementTasks.GoTo(origin.getX()+1,origin.getY()+5,origin.getZ());
            stage=17;return false;
        }
        if(stage==17) {
            PathSearch.Pos feet=bot.navigation.feet();
            if(!bot.mc.player.onGround || feet.x!=origin.getX()+1 || feet.y!=origin.getY()+5
                    || feet.z!=origin.getZ() || !bot.navigation.floor(feet.x,feet.y-1,feet.z))
                throw new TaskFailure("Bot did not finish on the upper leaf ring.");
            bot.say("Tower base built at "+origin+"; standing on top of its upper leaf ring.");
            return true;
        }
        if(stage==12) {
            status="place one temporary leaf for lower-ring access";
            BlockPos step=origin.add(2,0,0);
            Block block=bot.mc.world.getBlockState(step).getBlock();
            if(block==Block.get("leaves") || block==Block.get("leaves2")) {stage=13;return false;}
            if(!bot.mc.world.isAir(step)) throw new TaskFailure("Lower-ring access is blocked at "+step);
            int slot=bot.findInventory(LegacyItems.anyLeaves());
            if(slot<0) throw new TaskFailure("No leaf available for lower-ring access.");
            child=new LegacyPlaceTask(step,LegacyItems.id(bot.mc.player.inventory.main[slot].getItem()));
            return false;
        }
        if(stage==13) {
            status="step onto temporary leaf";
            child=new MovementTasks.GoTo(origin.getX()+2,origin.getY()+1,origin.getZ());
            stage=14;return false;
        }
        if(stage==14) {
            status="step onto first eight-leaf ring";
            child=new MovementTasks.GoTo(origin.getX()+1,origin.getY()+2,origin.getZ());
            stage=15;return false;
        }
        if(stage==15) {
            status="remove temporary lower-ring access leaf";
            BlockPos step=origin.add(2,0,0);
            if(bot.mc.world.isAir(step)) {lowerAscentDone=true;bot.cancelMining();stage=6;return false;}
            if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
            if(bot.hit(step)==null) throw new TaskFailure("Cannot reach temporary lower-ring leaf "+step);
            bot.mine(step);return false;
        }
        for(int i=0;i<TowerBasePlan.PLACEMENTS.size();i++) {
            TowerBasePlan.Cell cell=TowerBasePlan.PLACEMENTS.get(i);
            if(TowerBasePlan.matches(bot.mc.world,origin,cell)) continue;
            if(++repairPasses>3) throw new TaskFailure("Tower base repeatedly changed at "+cell.at(origin));
            bot.say("Repairing tower base cell at "+cell.at(origin)+" (found "+
                    bot.mc.world.getBlockState(cell.at(origin)).getBlock()+").");
            placeIndex=i;stage=6;return false;
        }
        if(bot.mc.world.getBlockState(origin.down()).getBlock()!=Block.get("trapdoor")
                || !bot.mc.world.isAir(origin.down(2))
                || !LegacyFluidTask.source(bot.mc.world.getBlockState(origin.down(3)),"lava"))
            throw new TaskFailure("Tower shaft was not completed safely.");
        stage=8;
        return false;
    }
}
