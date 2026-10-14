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
            BlockPos support=LegacyPos.offset(target,d);Direction face=LegacyDirection.opposite(d);
            adris.altoclef.legacy.BlockState state=LegacyWorld.state(bot.mc.world,support);
            net.minecraft.util.math.Box box=LegacyWorld.collisionBox(state.getBlock(),bot.mc.world,support,state);
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
                Vec3d aim=Vec3d.of(x,y,z);
                double dx=eye.x-aim.x,dy=eye.y-aim.y,dz=eye.z-aim.z;
                if(dx*dx+dy*dy+dz*dz>20.25) continue;
                BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
                if(hit!=null && support.equals(LegacyPos.hit(hit)) && net.minecraft.util.math.Direction.getById(hit.side)==face) return hit;
            }
        }
        return null;
    }
    public boolean tick(LegacyBot bot) {
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;}return false;}
        if(++ticks>1200) throw new TaskFailure("Could not reach construction position "+target);
        if(LegacyWorld.state(bot.mc.world,target).getBlock()==net.minecraft.block.Block.get(material)) return true;
        if(bot.ticks<waitUntil) return false;
        // Do not judge reachability from a falling/jumping eye position. The
        // mold builder may finish a path on a one-block ledge, and the next
        // placement tick can run before vanilla has settled the player.
        boolean airborne=!bot.mc.field_3805.onGround;
        int slot=bot.findInventory(LegacyItems.parse(material));
        if(slot<0) {child=new AcquisitionTask(LegacyItems.parse(material),material.equals("cobblestone") ? 32 : 4,"get");return false;}
        PathSearch.Pos feet=bot.navigation.feet();
        net.minecraft.util.math.Box playerBox=bot.mc.field_3805.boundingBox;
        boolean occupyingTarget=playerBox.maxX>target.x+.001 && playerBox.minX<target.x+1-.001
                && playerBox.maxY>target.y+.001 && playerBox.minY<target.y+1-.001
                && playerBox.maxZ>target.z+.001 && playerBox.minZ<target.z+1-.001;
        if(occupyingTarget) {
            // Portal moulds can call for a block in the player's current air
            // cell. Step sideways onto a supported neighboring cell; pillaring
            // up here only returns to the same placement target and loops.
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                PathSearch.Pos side=new PathSearch.Pos(feet.x+d[0],feet.y,feet.z+d[1]);
                if(Math.abs(side.x-target.x)+Math.abs(side.z-target.z)
                        <=Math.abs(feet.x-target.x)+Math.abs(feet.z-target.z)) continue;
                if(!PathSearch.standing(bot.navigation,side.x,side.y,side.z)) continue;
                java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,side,1000);
                if(!route.isEmpty() && route.get(route.size()-1).equals(side)) {
                    bot.navigation.move(side,.15);return false;
                }
            }
            throw new TaskFailure("No supported side-step away from construction block "+target);
        }
        if(!LegacyWorld.isAir(bot.mc.world,target) && !(replaceFluid && LegacyWorld.state(bot.mc.world,target).getBlock().getMaterial().isFluid())) {
            net.minecraft.block.Block obstruction=LegacyWorld.state(bot.mc.world,target).getBlock();
            // Flowers are passable plants but their 1.7 material is not marked
            // replaceable. Clear them just like grass before placing the mold.
            if(!obstruction.getMaterial().isFluid() && (obstruction.getMaterial().isReplaceable()
                    || bot.navigation.clear(target.x,target.y,target.z))) {
                bot.mine(target);return false;
            }
            throw new TaskFailure("Construction position contains "+net.minecraft.block.Block.REGISTRY.getId(obstruction)+": "+target);
        }
        Vec3d eye=Vec3d.of(bot.mc.field_3805.x,bot.mc.field_3805.boundingBox.minY+LegacyPos.eyeHeight(bot.mc.field_3805),bot.mc.field_3805.z);
        BlockHitResult hit=hit(bot,eye);
        if(hit!=null && LegacyWorld.canPlace(bot.mc.world,net.minecraft.block.Block.get(material),target,false,net.minecraft.util.math.Direction.getById(hit.side),null,bot.mc.field_3805.inventory.main[slot])) {
            bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);
            LegacyInteraction.rightClick(bot.mc.interactionManager,bot.mc.field_3805,bot.mc.world,bot.mc.field_3805.inventory.getMainHandStack(),LegacyPos.hit(hit),net.minecraft.util.math.Direction.getById(hit.side),hit.pos);
            waitUntil=bot.ticks+4;return false;
        }
        // A jump-place can leave the 1.8 client reporting onGround=false while
        // standing on the new scaffold. A visible face is safe to click above;
        // only defer route planning and further climbing until physics settles.
        // Keep the previous walking input through a jump toward the selected
        // stance. Releasing it here strands the player in repeated vertical jumps.
        if(airborne) return false;
        PathSearch.Pos best=null;double score=Double.POSITIVE_INFINITY;
        for(int x=target.x-4;x<=target.x+4;x++) for(int z=target.z-4;z<=target.z+4;z++)
            for(int y=target.y-3;y<=target.y+3;y++) {
                if(!PathSearch.standing(bot.navigation,x,y,z) || hit(bot,Vec3d.of(x+.5,y+1.62,z+.5))==null) continue;
                if(x==target.x && z==target.z && (y==target.y || y+1==target.y)) continue;
                PathSearch.Pos p=new PathSearch.Pos(x,y,z);
                if(rejectedStances.contains(p)) continue;
                double cost=feet.distance(p);
                if(cost>=score) continue;
                java.util.List<PathSearch.Pos> route=PathSearch.find(bot.navigation,feet,p,2000);
                if(!feet.equals(p) && (route.isEmpty() || !route.get(route.size()-1).equals(p))) continue;
                score=cost;best=p;
        }
        if(best==null && feet.y<target.y) {
            // Do not path onto a narrow intermediate ledge: those jump/drop
            // approaches oscillate in the legacy navigator. The current cell is
            // already a validated standing position, so scaffold vertically
            // here to the height needed for this placement.
            child=new SurfaceAccessTask(target.y-1,true);
            return false;
        }
        if(best==null) {
            StringBuilder faces=new StringBuilder();
            for(Direction d:Direction.values()) {
                BlockPos p=LegacyPos.offset(target,d);
                adris.altoclef.legacy.BlockState state=LegacyWorld.state(bot.mc.world,p);
                if(LegacyWorld.collisionBox(state.getBlock(),bot.mc.world,p,state)!=null) faces.append(d).append('@').append(p).append(' ');
            }
            throw new TaskFailure("No reachable placement face for "+target+" from "+feet+
                    " grounded="+bot.mc.field_3805.onGround+" supports="+faces);
        }
        // A center-point ray can graze a neighboring block while the real player
        // is still slightly off center. Try another stance if arrival does not
        // yield an actual usable face, rather than repeatedly declaring arrival.
        if(bot.navigation.move(best,.1)) rejectedStances.add(best);
        return false;
    }
}
