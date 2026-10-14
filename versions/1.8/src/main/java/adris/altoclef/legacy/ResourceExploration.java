package adris.altoclef.legacy;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;

/** Travels between scan areas; acquisition rescans during movement, not only at waypoints. */
final class ResourceExploration {
    private ExplorationPlan plan;
    private PathSearch.Pos destination;
    private int legStarted;
    private int lastProgress;
    private double bestDistance;
    private final Set<PathSearch.Pos> rejected = new HashSet<>();
    private final Set<PathSearch.Pos> unsafeShore=new HashSet<>();
    private RiverCrossingTask crossing;
    private WaterEscapeTask waterRecovery;
    private SurfaceStepTask stepRecovery;
    private final boolean unbounded;
    ResourceExploration() { this(false); }
    ResourceExploration(boolean unbounded) { this.unbounded=unbounded; }
    String describe() {
        if(stepRecovery!=null) return stepRecovery.describe();
        if(waterRecovery!=null) return "shore recovery";
        if(crossing!=null) return crossing.describe();
        return "exploring"+(destination==null ? "" : " to "+destination);
    }
    void suspend() { destination = null; crossing=null; waterRecovery=null; stepRecovery=null; }
    void tick(LegacyBot bot, String resource) {
        if(stepRecovery!=null) {
            if(stepRecovery.tick(bot)) {stepRecovery.stop(bot);stepRecovery=null;plan=null;rejected.clear();}
            return;
        }
        if(crossing!=null) {
            try {if(crossing.tick(bot)) {crossing.stop(bot);crossing=null;destination=null;bot.navigation.reset();}}
            catch(TaskFailure failed) {crossing.stop(bot);crossing=null;destination=null;bot.navigation.reset();}
            return;
        }
        if(waterRecovery!=null) {
            if(waterRecovery.tick(bot)) {waterRecovery.stop(bot);waterRecovery=null;destination=null;bot.navigation.reset();}
            return;
        }
        PathSearch.Pos rawFeet=bot.navigation.feet();
        boolean waterBelow=bot.mc.world.getBlockState(new BlockPos(rawFeet.x,rawFeet.y-1,rawFeet.z))
                .getBlock().getMaterial()==Material.WATER;
        if((WaterEscapeTask.wet(bot) || waterBelow) && !PathSearch.standing(bot.navigation,rawFeet.x,rawFeet.y,rawFeet.z)) {
            unsafeShore.add(rawFeet);
            if(destination!=null) rejected.add(destination);
            waterRecovery=new WaterEscapeTask();destination=null;
            return;
        }
        PathSearch.Pos feet = bot.navigation.feet();
        if(bot.mc.player.onGround) feet=PathSearch.groundedStart(bot.navigation,
                bot.mc.player.x,feet.y,bot.mc.player.z);
        if(destination!=null) {
            double dx=bot.mc.player.x-(destination.x+.5),dz=bot.mc.player.z-(destination.z+.5);
            double distance=dx*dx+dz*dz;
            if(distance<bestDistance-.25) {bestDistance=distance;lastProgress=bot.ticks;}
            // This also runs while airborne. Otherwise a repeated jump at a
            // ledge can keep the exploration leg alive forever.
            if(bot.ticks-lastProgress>100) {
                rejected.add(destination);destination=null;
                bot.releaseMovement();bot.navigation.reset();
            }
        }
        // A new path requires footing. An active path must keep receiving
        // airborne ticks, or releasing forward here interrupts every jump.
        if(destination==null && (!bot.mc.player.onGround || !PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z))) {
            bot.releaseMovement();return;
        }
        if (plan == null) {
            plan = new ExplorationPlan(feet.x, feet.z,unbounded);
            bot.say("Searching farther for " + resource + ". Use @stop to cancel.");
            bot.releaseMovement(); bot.navigation.reset();
        }
        if (destination == null) {
            for(int attempt=0;attempt<8 && destination==null;attempt++) {
                PathSearch.Pos next = plan.next(feet.y);
                if (next == null) {
                    stepRecovery=SurfaceStepTask.plan(bot);
                    if(stepRecovery!=null) return;
                    throw new TaskFailure("Could not find " + resource + " after exploring accessible nearby terrain.");
                }
                // A scan direction need not itself be a standing cell. Search toward
                // it and use a reachable endpoint, including routes down hills.
                List<PathSearch.Pos> route = PathSearch.explore(bot.navigation, feet, next, 2000);
                if(route.isEmpty() || route.get(route.size()-1).distance(feet)<4) {
                    RiverCrossingTask water=RiverCrossingTask.plan(bot,new BlockPos(next.x,next.y,next.z));
                    if(water!=null) {crossing=water;return;}
                    continue;
                }
                PathSearch.Pos endpoint=route.get(route.size()-1);
                if(rejected.contains(endpoint)) continue;
                boolean repeatsShore=false;
                for(PathSearch.Pos p:unsafeShore)
                    if(Math.abs(endpoint.x-p.x)<=8 && Math.abs(endpoint.z-p.z)<=8) {repeatsShore=true;break;}
                if(repeatsShore) continue;
                destination=endpoint;
                bot.navigation.reset();legStarted=lastProgress=bot.ticks;
                double dx=bot.mc.player.x-(destination.x+.5),dz=bot.mc.player.z-(destination.z+.5);
                bestDistance=dx*dx+dz*dz;
            }
            if(destination==null) {stepRecovery=SurfaceStepTask.plan(bot);return;}
        }
        try {
            if (bot.navigation.move(destination, .5) || bot.ticks - legStarted > 300) {
                destination = null; bot.releaseMovement(); bot.navigation.reset();
            }
        } catch (TaskFailure blocked) {
            destination = null; bot.releaseMovement(); bot.navigation.reset();
        }
    }
}
