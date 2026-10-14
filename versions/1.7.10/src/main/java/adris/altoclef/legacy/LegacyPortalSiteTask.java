package adris.altoclef.legacy;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.math.BlockPos;

/** Finds a reachable, clear casting site beside a surface lava pool without requiring a bucket. */
final class LegacyPortalSiteTask implements LegacyTask {
    private final LegacyPortalPlan plan;
    private final Set<BlockPos> rejected=new HashSet<>();
    private final ResourceExploration exploration=new ResourceExploration(true);
    private LoadedResourceScan scan;
    private LegacyTask child;
    private BlockPos approachingPool;
    private int ticks,bankClimbs;
    LegacyPortalSiteTask(LegacyPortalPlan plan) {this.plan=plan;}
    public String describe() {return "find a lava pool and portal worksite"+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();bot.navigation.reset();}
    public boolean tick(LegacyBot bot) {
        if(plan.ready()) return true;
        if(++ticks>24000) throw new TaskFailure("Could not find a reachable surface lava pool and portal worksite.");
        if(child!=null) {
            try {
                if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();scan=null;}
            } catch(TaskFailure blocked) {
                if(approachingPool==null) throw blocked;
                child.stop(bot);child=null;rejected.add(approachingPool);approachingPool=null;
                bot.navigation.reset();scan=null;
            }
            return false;
        }
        PathSearch.Pos feet=bot.navigation.feet();
        // Tree tops and adjacent hills are not ascent targets. Normal navigation
        // handles surface steps; only a genuinely enclosed mine needs an exit.
        if(SurfaceAccessTask.underground(bot)) {child=new SurfaceAccessTask();return false;}
        BlockPos pool=approachingPool;
        if(pool==null) pool=bot.nearestBlock(s->LegacyFluidTask.source(s,"lava"),24,rejected,
                p->LegacyWorld.hasDirectSunlight(bot.mc.world,LegacyPos.up(p)) && LegacyFluidTask.hasSources(bot,p,"lava",10));
        if(pool==null) {
            if(scan==null || scan.exhausted()) scan=new LoadedResourceScan(bot.navigation.feet());
            pool=scan.poll(bot,(p,s)->!rejected.contains(p) && LegacyFluidTask.source(s,"lava")
                    && LegacyWorld.hasDirectSunlight(bot.mc.world,LegacyPos.up(p)) && LegacyFluidTask.hasSources(bot,p,"lava",10));
        }
        if(pool!=null) {
            if(Math.abs(pool.x-feet.x)+Math.abs(pool.z-feet.z)>24) {
                PathSearch.Pos shore=null;double best=Double.POSITIVE_INFINITY;
                for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))<6) continue;
                    int x=pool.x+dx,z=pool.z+dz;
                    int y=SurfaceAccessTask.terrainSurface(bot,x,z);
                    if(Math.abs(y-pool.y)>5 || !PathSearch.standing(bot.navigation,x,y,z)) continue;
                    PathSearch.Pos candidate=new PathSearch.Pos(x,y,z);
                    double distance=feet.distance(candidate);
                    if(distance<best) {shore=candidate;best=distance;}
                }
                if(shore!=null) {
                    approachingPool=pool;bot.navigation.reset();
                    child=new MovementTasks.GoTo(shore.x,shore.y,shore.z);return false;
                }
            }
            approachingPool=null;
            BlockPos origin=LegacyOverworldTask.findSite(bot,pool);
            if(origin!=null) {
                plan.lavaPool=pool;plan.origin=origin;
                bot.say("Portal worksite selected beside lava at "+pool+".");
                return true;
            }
            // A low shelf beside lava may have no walking exit until we
            // raise the feet one block. Only try this while supported and
            // below nearby dry terrain; never pillar just because scanning failed.
            int bank=feet.y;
            for(int[] d:new int[][]{{2,0},{-2,0},{0,2},{0,-2}}) {
                int top=SurfaceAccessTask.terrainSurface(bot,feet.x+d[0],feet.z+d[1]);
                if(top>feet.y && top<=feet.y+2 && PathSearch.standing(bot.navigation,feet.x+d[0],top,feet.z+d[1])) bank=Math.max(bank,top);
            }
            if(bank>feet.y && bankClimbs++<3 && PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z)) {
                child=new SurfaceAccessTask(feet.y+1,true);return false;
            }
            rejected.add(pool);return false;
        }
        exploration.tick(bot,"surface lava pool and portal worksite");
        return false;
    }
}
