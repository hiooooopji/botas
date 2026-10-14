package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.FeatureState;

/** Locate a real fortress spawner, approach its platform, and collect seven rods. */
final class LegacyBlazeTask implements LegacyTask {
    private final BlockPos fortress;
    private AcquisitionTask drops=new AcquisitionTask(LegacyItems.parse("blaze_rod"),7,"get");
    private final ArrayDeque<int[]> rooms=new ArrayDeque<>();
    private Future<BlockPos[]> pending;
    private boolean initialized;
    private BlockPos spawner,stand;
    private LegacyTask travel;
    private int rods,ticks,nextHuntScan,nextDropRetry;
    private net.minecraft.entity.mob.BlazeEntity hunted;
    private PathSearch.Pos pursuit;
    private int pursuitUntil;
    private final Map<Integer,Integer> rejectedBlazes=new HashMap<>();
    private final Set<BlockPos> rejectedStands=new HashSet<>();
    LegacyBlazeTask(BlockPos fortress) {this.fortress=fortress;}
    public String describe() {return "blaze rods "+rods+"/7 -> "+(hunted!=null && pursuit!=null ? "approach nearby blaze at "+(int)hunted.x+" "+(int)hunted.y+" "+(int)hunted.z : travel!=null ? travel.describe() : spawner==null ? "locate blaze spawner" : "hunt near spawner "+spawner);}
    public void stop(LegacyBot bot) {if(travel!=null) travel.stop(bot);if(pending!=null) pending.cancel(false);bot.releaseMovement();bot.cancelMining();}
    public boolean tick(LegacyBot bot) {
        rods=bot.count(LegacyItems.parse("blaze_rod"));
        if(rods>=7) return true;
        if(++ticks>36000) throw new TaskFailure("Blaze hunt timed out with "+rods+"/7 rods.");
        if(bot.ticks>=nextDropRetry) {
            nextDropRetry=bot.ticks+200;drops=new AcquisitionTask(LegacyItems.parse("blaze_rod"),7,"get");
        }
        if(drops.pickup(bot)) {bot.cancelMining();return false;}
        if(pursueNearbyBlaze(bot)) return false;
        if(spawner!=null && (stand==null || !PathSearch.standing(bot.navigation,stand.x,stand.y,stand.z))) {
            BlockPos replacement=null;double best=Double.MAX_VALUE;
            for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++) for(int dy=-2;dy<=1;dy++) {
                BlockPos p=LegacyPos.add(spawner,dx,dy,dz);
                if(rejectedStands.contains(p) || LegacyWorld.state(bot.mc.world,LegacyPos.down(p)).getBlock()!=Block.get("nether_brick")
                        || !PathSearch.standing(bot.navigation,p.x,p.y,p.z)) continue;
                double cost=LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p)+Math.abs(dy)*16;
                if(cost<best) {best=cost;replacement=p;}
            }
            if(replacement!=null) {
                if(travel!=null) {travel.stop(bot);travel=null;}
                stand=replacement;
            }
        }
        if(travel!=null) {
            try {if(travel.tick(bot)) {travel.stop(bot);travel=null;}}
            catch(TaskFailure blocked) {
                travel.stop(bot);travel=null;
                if(stand==null || rejectedStands.size()>=24) throw blocked;
                rejectedStands.add(stand);stand=null;bot.navigation.reset();
            }
            return false;
        }
        if(spawner!=null) {
            if(stand==null) throw new TaskFailure("No safe standing position remains near the blaze spawner.");
            // Stay well inside the vanilla 16-block spawner activation radius.
            if(LegacyPos.squaredDistance(bot.mc.field_3805,stand.x+.5,stand.y,stand.z+.5)>.36) {
                PathSearch.Pos target=new PathSearch.Pos(stand.x,stand.y,stand.z);
                List<PathSearch.Pos> path=PathSearch.find(bot.navigation,bot.navigation.feet(),target,3000);
                travel=!path.isEmpty() && path.get(path.size()-1).equals(target)
                        ? new MovementTasks.GoTo(target.x,target.y,target.z) : new NetherTravelTask(stand);
            }
            else bot.releaseMovement();
            return false;
        }
        bot.releaseMovement();
        if(pending==null) {
            pending=LegacyServerTasks.submit(bot.mc.getServer(),(java.util.concurrent.Callable<BlockPos[]>)()->scan(bot.mc.getServer().getWorld(-1)));
            return false;
        }
        if(!pending.isDone()) return false;
        BlockPos[] found;
        try {found=pending.get();} catch(Exception e) {throw new TaskFailure("Blaze spawner scan failed: "+e.getMessage());}
        pending=null;
        if(found==null) return false;
        if(found.length==0) throw new TaskFailure("No usable blaze spawner found in this fortress.");
        spawner=found[0];stand=found[1];travel=new NetherTravelTask(stand);
        bot.say("Blaze spawner at "+spawner+". Hunting until seven rods are collected.");return false;
    }
    private boolean pursueNearbyBlaze(LegacyBot bot) {
        if(hunted!=null && (!hunted.isAlive() || bot.ticks>pursuitUntil
                || LegacyPos.squaredDistance(bot.mc.field_3805,hunted)>40*40)) {
            if(hunted.isAlive()) rejectedBlazes.put(hunted.getEntityId(),bot.ticks+100);
            hunted=null;pursuit=null;bot.navigation.reset();
        }
        if(bot.ticks>=nextHuntScan && bot.mc.field_3805.onGround) {
            nextHuntScan=bot.ticks+20;
            List<net.minecraft.entity.mob.BlazeEntity> blazes=new ArrayList<>();
            for(Object e:bot.mc.world.loadedEntities) if(e instanceof net.minecraft.entity.mob.BlazeEntity) {
                net.minecraft.entity.mob.BlazeEntity blaze=(net.minecraft.entity.mob.BlazeEntity)e;
                if(blaze.isAlive() && LegacyPos.squaredDistance(bot.mc.field_3805,blaze)<=32*32
                        && rejectedBlazes.getOrDefault(blaze.getEntityId(),0)<=bot.ticks) blazes.add(blaze);
            }
            blazes.sort(Comparator.comparingDouble(b->LegacyPos.squaredDistance(bot.mc.field_3805,b)));
            PathSearch.Pos from=bot.navigation.feet();
            for(net.minecraft.entity.mob.BlazeEntity blaze:blazes) {
                List<PathSearch.Pos> candidates=new ArrayList<>();
                for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=-4;dy<=1;dy++) {
                    PathSearch.Pos p=new PathSearch.Pos((int)Math.floor(blaze.x)+dx,(int)Math.floor(blaze.y)+dy,(int)Math.floor(blaze.z)+dz);
                    if(PathSearch.standing(bot.navigation,p.x,p.y,p.z)) candidates.add(p);
                }
                candidates.sort(Comparator.comparingDouble(p->from.distance(p)+Math.abs(p.y-blaze.y)*2));
                for(int i=0;i<Math.min(16,candidates.size());i++) {
                    PathSearch.Pos p=candidates.get(i);
                    if(p.equals(from)) continue;
                    List<PathSearch.Pos> path=PathSearch.find(bot.navigation,from,p,2000);
                    if(path.isEmpty() || !path.get(path.size()-1).equals(p)) continue;
                    if(hunted!=blaze) pursuitUntil=bot.ticks+200;
                    hunted=blaze;pursuit=p;
                    if(travel!=null) {travel.stop(bot);travel=null;}
                    break;
                }
                if(hunted==blaze && pursuit!=null) break;
            }
        }
        if(hunted==null || pursuit==null) return false;
        try {bot.navigation.move(pursuit,.25);return true;}
        catch(TaskFailure blocked) {
            rejectedBlazes.put(hunted.getEntityId(),bot.ticks+100);hunted=null;pursuit=null;bot.navigation.reset();return false;
        }
    }
    private BlockPos[] scan(World world) {
        if(!initialized) {
            initialized=true;
            FeatureState state=(FeatureState)world.getOrCreateState(FeatureState.class,"Fortress");
            if(state!=null) for(Object key:state.getFeatures().getKeys()) {
                NbtCompound feature=state.getFeatures().getCompound((String)key);
                int[] bounds=feature.getIntArray("BB");
                if(bounds.length!=6 || fortress.x<bounds[0] || fortress.x>bounds[3]
                        || fortress.z<bounds[2] || fortress.z>bounds[5]) continue;
                NbtList children=feature.getList("Children",10);
                for(int i=0;i<children.size();i++) {
                    NbtCompound child=children.getCompound(i);
                    if(child.getString("id").equals("NeMT")) rooms.add(child.getIntArray("BB"));
                }
            }
            List<int[]> sorted=new ArrayList<>(rooms);
            sorted.sort(Comparator.comparingDouble(b->distance((b[0]+b[3])/2,(b[2]+b[5])/2)));
            rooms.clear();rooms.addAll(sorted);
        }
        if(rooms.isEmpty()) return new BlockPos[0];
        int[] b=rooms.remove();
        for(int cx=b[0]>>4;cx<=b[3]>>4;cx++) for(int cz=b[2]>>4;cz<=b[5]>>4;cz++) world.getChunk(cx,cz);
        for(int x=b[0];x<=b[3];x++) for(int z=b[2];z<=b[5];z++) for(int y=b[1];y<=b[4];y++) {
            BlockPos p=new BlockPos(x,y,z);
            if(LegacyWorld.state(world,p).getBlock()!=Block.get("mob_spawner")) continue;
            BlockPos best=null;double cost=Double.MAX_VALUE;
            for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++) for(int dy=-2;dy<=1;dy++) {
                BlockPos q=LegacyPos.add(p,dx,dy,dz);
                if(LegacyWorld.state(world,LegacyPos.down(q)).getBlock()!=Block.get("nether_brick") || !LegacyWorld.isAir(world,q) || !LegacyWorld.isAir(world,LegacyPos.up(q))) continue;
                double d=distance(q.x,q.z);if(d<cost) {cost=d;best=q;}
            }
            if(best!=null) return new BlockPos[]{p,best};
        }
        return rooms.isEmpty() ? new BlockPos[0] : null;
    }
    private double distance(int x,int z) {double dx=x-fortress.x,dz=z-fortress.z;return dx*dx+dz*dz;}
}
