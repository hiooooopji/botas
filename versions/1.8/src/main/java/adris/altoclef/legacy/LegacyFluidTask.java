package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.BlockHitResult;

/** Collects a source using the normal bucket item interaction. */
final class LegacyFluidTask implements LegacyTask {
    final String fluid;
    private final BlockPos preferred;
    private final int minimumSources;
    private LoadedResourceScan scan;
    private final ResourceExploration exploration=new ResourceExploration(true);
    private final java.util.Set<BlockPos> rejected=new java.util.HashSet<>();
    private BlockPos source;
    private int ticks,waitUntil,useAttempts;
    private BlockPos approachSource;
    private PathSearch.Pos approachStance;
    private int approachDeadline;
    private final java.util.Set<PathSearch.Pos> rejectedStances=new java.util.HashSet<>();
    private boolean checkedNearby;
    LegacyFluidTask(String fluid) { this(fluid,null); }
    LegacyFluidTask(String fluid,BlockPos preferred) { this(fluid,preferred,0); }
    LegacyFluidTask(String fluid,BlockPos preferred,int minimumSources) { this.fluid=fluid;this.preferred=preferred;this.minimumSources=minimumSources; }
    BlockPos lastSource() {return source;}
    public String describe() { return "collect "+fluid+" bucket"+(approachStance==null ? "" : " -> approach "+approachStance); }
    public void stop(LegacyBot bot) {bot.releaseMovement();bot.navigation.reset();}
    static boolean source(BlockState state,String fluid) {
        String id=String.valueOf(Block.REGISTRY.getIdentifier(state.getBlock()));
        return (id.equals("minecraft:"+fluid) || id.equals("minecraft:flowing_"+fluid)) && state.getBlock().getData(state)==0;
    }
    static boolean hasSources(LegacyBot bot,BlockPos start,String fluid,int minimumSources) {
        if(minimumSources==0) return true;
        java.util.Set<BlockPos> visited=new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue=new java.util.ArrayDeque<>();queue.add(start);
        int found=0;
        while(!queue.isEmpty() && visited.size()<256) {
            BlockPos p=queue.removeFirst();
            if(!visited.add(p) || !bot.mc.world.isLoaded(p,false) || !source(bot.mc.world.getBlockState(p),fluid)) continue;
            if(++found>=minimumSources) return true;
            for(Direction d:Direction.values()) queue.add(p.offset(d));
        }
        return false;
    }
    private boolean enoughSources(LegacyBot bot,BlockPos start) {return hasSources(bot,start,fluid,minimumSources);}
    // 0 = unreachable, 2 = used bucket after sending the ordinary look packet.
    static int take(LegacyBot bot,BlockPos pos) {
        return take(bot,pos,false);
    }
    static int take(LegacyBot bot,BlockPos pos,boolean steady) {
        PathSearch.Pos feet=bot.navigation.feet();
        if(!bot.mc.player.onGround || !bot.navigation.floor(feet.x,feet.y-1,feet.z)) return 0;
        int slot=bot.findInventory(LegacyItems.parse("bucket"));
        if(slot<0) throw new TaskFailure("An empty bucket is required.");
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        Vec3d aim=null;
        for(double height:new double[]{.85,.5}) {
            Vec3d candidate=new Vec3d(pos.getX()+.5,pos.getY()+height,pos.getZ()+.5);
            BlockHitResult hit=bot.mc.world.rayTrace(eye,candidate,true);
            if(hit!=null && pos.equals(hit.getBlockPos())) {aim=candidate;break;}
        }
        if(aim==null || bot.mc.player.squaredDistanceToCenter(pos)>25) return 0;
        bot.releaseMovement();bot.equip(slot);bot.look(aim.x,aim.y,aim.z);
        bot.key(bot.mc.options.sneakKey,steady || source(bot.mc.world.getBlockState(pos),"lava"));
        syncAim(bot);
        bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
        return 2;
    }
    static void syncAim(LegacyBot bot) {
        // Buckets raycast on the server. Order the normal look packet before use,
        // including while currents move the player; waiting for a stable pose can loop.
        bot.mc.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerPositionLookC2SPacket(
                bot.mc.player.x,bot.mc.player.y,bot.mc.player.z,
                bot.mc.player.yaw,bot.mc.player.pitch,bot.mc.player.onGround));
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>12000) throw new TaskFailure("Could not reach a "+fluid+" source.");
        if(bot.count(LegacyItems.parse(fluid+"_bucket"))>0) return true;
        if(bot.ticks<waitUntil) return false;
        bot.closeOwnedScreen();
        if(source!=null && !source(bot.mc.world.getBlockState(source),fluid)) source=null;
        if(source==null) {
            if(!checkedNearby) {
                checkedNearby=true;
                if(preferred!=null) for(int r=0;r<=6 && source==null;r++)
                    for(int x=-r;x<=r && source==null;x++) for(int z=-r;z<=r && source==null;z++) {
                        BlockPos p=preferred.add(x,0,z);
                        if(!rejected.contains(p) && bot.mc.world.isLoaded(p,false) && source(bot.mc.world.getBlockState(p),fluid) && enoughSources(bot,p)) source=p;
                    }
                if(source==null) source=bot.nearestBlock(s->source(s,fluid),24,rejected,p->enoughSources(bot,p));
            }
        }
        if(source==null) {
            
            if(scan==null || scan.exhausted()) scan=new LoadedResourceScan(bot.navigation.feet());
            source=scan.poll(bot,(p,s)->!rejected.contains(p) && source(s,fluid) && enoughSources(bot,p));
            if(source==null) {exploration.tick(bot,fluid+" source");return false;}
            exploration.suspend();bot.navigation.reset();
        }
        if(!source.equals(approachSource)) {approachSource=source;approachStance=null;rejectedStances.clear();useAttempts=0;}
        int result=useAttempts<3 ? take(bot,source) : 0;
        if(result>0) {useAttempts++;waitUntil=bot.ticks+10;return false;}
        if(useAttempts>=3) {rejectedStances.add(bot.navigation.feet());approachStance=null;}
        if(approachStance!=null && !PathSearch.standing(bot.navigation,approachStance.x,approachStance.y,approachStance.z)) approachStance=null;
        if(approachStance!=null && bot.ticks>approachDeadline) {
            rejectedStances.add(approachStance);approachStance=null;useAttempts=0;bot.navigation.reset();
        }
        PathSearch.Pos best=approachStance;double score=Double.POSITIVE_INFINITY;
        if(best==null)
        for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++) for(int dy=-2;dy<=2;dy++) {
            int x=source.getX()+dx,y=source.getY()+dy,z=source.getZ()+dz;
            if(!PathSearch.standing(bot.navigation,x,y,z)) continue;
            Vec3d eye=new Vec3d(x+.5,y+bot.mc.player.getEyeHeight(),z+.5),aim=new Vec3d(source.getX()+.5,source.getY()+.85,source.getZ()+.5);
            double reach=(eye.x-aim.x)*(eye.x-aim.x)+(eye.y-aim.y)*(eye.y-aim.y)+(eye.z-aim.z)*(eye.z-aim.z);
            if(reach>20) continue;
            BlockHitResult hit=bot.mc.world.rayTrace(eye,aim,true);
            if(hit==null || !source.equals(hit.getBlockPos())) continue;
            PathSearch.Pos candidate=new PathSearch.Pos(x,y,z);
            if(rejectedStances.contains(candidate)) continue;
            double cost=candidate.distance(bot.navigation.feet());
            if(cost<score) {score=cost;best=candidate;}
        }
        if(approachStance==null && best!=null) approachDeadline=bot.ticks+160;
        approachStance=best;
        if(best==null) {
            org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("No dry bucket approach to {}",source);
            rejected.add(source);source=null;checkedNearby=false;return false;
        }
        try {
            if(bot.navigation.move(best,.1)) {rejectedStances.add(best);approachStance=null;useAttempts=0;}
        } catch(TaskFailure e) {
            org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("Bucket approach to {} failed: {}",source,e.getMessage());
            // One side of a pool may be cut off by the water mold or its
            // temporary scaffold. Keep the source and try another visible dry
            // stance before rejecting the entire lava pool.
            rejectedStances.add(best);approachStance=null;useAttempts=0;bot.navigation.reset();
        }
        return false;
    }
}
