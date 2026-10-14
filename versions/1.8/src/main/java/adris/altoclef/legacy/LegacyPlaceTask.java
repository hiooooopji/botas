package adris.altoclef.legacy;

import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;

/** Places a construction block using reachable faces, walking/jumping onto existing scaffolding. */
final class LegacyPlaceTask implements LegacyTask {
    private final BlockPos target;
    private final String material;
    private final boolean replaceFluid;
    private LegacyTask child;
    private int ticks,waitUntil;
    private final java.util.Set<PathSearch.Pos> rejectedStances=new java.util.HashSet<>();
    LegacyPlaceTask(BlockPos target) {this(target,"cobblestone");}
    LegacyPlaceTask(BlockPos target,String material) {this(target,material,material.equals("netherrack"));}
    LegacyPlaceTask(BlockPos target,String material,boolean replaceFluid) {this.target=target;this.material=material;this.replaceFluid=replaceFluid;}
    public String describe() {return "place "+material+" at "+target+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();}
    private BlockHitResult hit(LegacyBot bot,Vec3d eye) {
        for(Direction d:Direction.values()) {
            BlockPos support=target.offset(d);Direction face=d.getOpposite();
            net.minecraft.block.BlockState state=bot.mc.world.getBlockState(support);
            net.minecraft.util.math.Box box=state.getBlock().getCollisionBox(bot.mc.world,support,state);
            if(box==null) continue;
            // Sample the face instead of aiming only at its center: the previous
            // mold block often occludes the center ray while a clear edge remains.
            for(double a:new double[]{-.32,0,.32}) for(double b:new double[]{-.32,0,.32}) {
                double x=(box.minX+box.maxX)/2, y=(box.minY+box.maxY)/2, z=(box.minZ+box.maxZ)/2;
                if(face.getOffsetX()>0) x=box.maxX-.001;
                if(face.getOffsetX()<0) x=box.minX+.001;
                if(face.getOffsetY()>0) y=box.maxY-.001;
                if(face.getOffsetY()<0) y=box.minY+.001;
                if(face.getOffsetZ()>0) z=box.maxZ-.001;
                if(face.getOffsetZ()<0) z=box.minZ+.001;
                if(face.getOffsetX()==0) x+=a; else if(face.getOffsetY()==0) y+=a; else z+=a;
                if(face.getOffsetZ()==0) z+=b; else if(face.getOffsetY()==0) y+=b; else x+=b;
                Vec3d aim=new Vec3d(x,y,z);
                double dx=eye.x-aim.x,dy=eye.y-aim.y,dz=eye.z-aim.z;
                if(dx*dx+dy*dy+dz*dz>20.25) continue;
                BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
                if(hit!=null && support.equals(hit.getBlockPos()) && hit.direction==face) return hit;
            }
        }
        return null;
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>1200) throw new TaskFailure("Could not reach construction position "+target);
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;}return false;}
        if(bot.mc.world.getBlockState(target).getBlock()==net.minecraft.block.Block.get(material)) return true;
        if(bot.ticks<waitUntil) return false;
        // Do not judge reachability from a falling/jumping eye position. The
        // mold builder may finish a path on a one-block ledge, and the next
        // placement tick can run before vanilla has settled the player.
        boolean airborne=!bot.mc.player.onGround;
        int slot=bot.findInventory(LegacyItems.parse(material));
        if(slot<0) {child=new AcquisitionTask(LegacyItems.parse(material),material.equals("cobblestone") ? 32 : 4,"get");return false;}
        PathSearch.Pos feet=bot.navigation.feet();
        net.minecraft.util.math.Box playerBox=bot.mc.player.getBoundingBox();
        boolean occupyingTarget=playerBox.maxX>target.getX()+.001 && playerBox.minX<target.getX()+1-.001
                && playerBox.maxY>target.getY()+.001 && playerBox.minY<target.getY()+1-.001
                && playerBox.maxZ>target.getZ()+.001 && playerBox.minZ<target.getZ()+1-.001;
        if(occupyingTarget) {
            // Portal moulds can call for a block in the player's current air
            // cell. Step sideways onto a supported neighboring cell; pillaring
            // up here only returns to the same placement target and loops.
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                PathSearch.Pos side=new PathSearch.Pos(feet.x+d[0],feet.y,feet.z+d[1]);
                if(Math.abs(side.x-target.getX())+Math.abs(side.z-target.getZ())
                        <=Math.abs(feet.x-target.getX())+Math.abs(feet.z-target.getZ())) continue;
                if(!PathSearch.standing(bot.navigation,side.x,side.y,side.z)) continue;
                java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,side,1000);
                if(!route.isEmpty() && route.get(route.size()-1).equals(side)) {
                    bot.navigation.move(side,.15);return false;
                }
            }
            throw new TaskFailure("No supported side-step away from construction block "+target);
        }
        if(!bot.mc.world.isAir(target) && !(replaceFluid && bot.mc.world.getBlockState(target).getBlock().getMaterial().isFluid())) {
            net.minecraft.block.Block obstruction=bot.mc.world.getBlockState(target).getBlock();
            if(!obstruction.getMaterial().isFluid() && obstruction.isReplaceable(bot.mc.world,target)) {
                bot.mine(target);return false;
            }
            throw new TaskFailure("Construction position contains a non-solid obstacle: "+target);
        }
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        BlockHitResult hit=hit(bot,eye);
        if(hit!=null && bot.mc.world.canBlockBePlaced(net.minecraft.block.Block.get(material),target,false,hit.direction,null,bot.mc.player.inventory.main[slot])) {
            bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
            bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),hit.getBlockPos(),hit.direction,hit.pos);
            waitUntil=bot.ticks+4;return false;
        }
        // A jump-place can leave the 1.8 client reporting onGround=false while
        // standing on the new scaffold. A visible face is safe to click above;
        // only defer route planning and further climbing until physics settles.
        if(airborne) {bot.releaseMovement();return false;}
        PathSearch.Pos best=null;double score=Double.POSITIVE_INFINITY;
        for(int x=target.getX()-4;x<=target.getX()+4;x++) for(int z=target.getZ()-4;z<=target.getZ()+4;z++)
            for(int y=target.getY()-3;y<=target.getY()+3;y++) {
                if(!PathSearch.standing(bot.navigation,x,y,z) || hit(bot,new Vec3d(x+.5,y+1.62,z+.5))==null) continue;
                if(x==target.getX() && z==target.getZ() && (y==target.getY() || y+1==target.getY())) continue;
                PathSearch.Pos p=new PathSearch.Pos(x,y,z);
                if(rejectedStances.contains(p)) continue;
                double cost=feet.distance(p);
                if(cost>=score) continue;
                java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,p,2000);
                if(!feet.equals(p) && (route.isEmpty() || !route.get(route.size()-1).equals(p))) continue;
                score=cost;best=p;
        }
        if(best==null && feet.y<target.getY()) {
            // Do not path onto a narrow intermediate ledge: those jump/drop
            // approaches oscillate in the legacy navigator. The current cell is
            // already a validated standing position, so scaffold vertically
            // here to the height needed for this placement.
            child=new SurfaceAccessTask(target.getY()-1,true);
            return false;
        }
        if(best==null) {
            StringBuilder faces=new StringBuilder();
            for(Direction d:Direction.values()) {
                BlockPos p=target.offset(d);
                net.minecraft.block.BlockState state=bot.mc.world.getBlockState(p);
                if(state.getBlock().getCollisionBox(bot.mc.world,p,state)!=null) faces.append(d).append('@').append(p).append(' ');
            }
            throw new TaskFailure("No reachable placement face for "+target+" from "+feet+
                    " grounded="+bot.mc.player.onGround+" supports="+faces);
        }
        // A center-point ray can graze a neighboring block while the real player
        // is still slightly off center. Try another stance if arrival does not
        // yield an actual usable face, rather than repeatedly declaring arrival.
        if(bot.navigation.move(best,.1)) rejectedStances.add(best);
        return false;
    }
}
