package adris.altoclef.legacy;

import java.util.List;

/** Travels between scan areas; acquisition rescans during movement, not only at waypoints. */
final class ResourceExploration {
    private ExplorationPlan plan;
    private PathSearch.Pos destination;
    private int legStarted;
    private LegacyTask descent;
    private final boolean unbounded;
    ResourceExploration() { this(false); }
    ResourceExploration(boolean unbounded) { this.unbounded=unbounded; }
    void suspend() { destination = null; }
    void tick(LegacyBot bot, String resource) {
        if(descent!=null) {
            if(descent.tick(bot)) {descent.stop(bot);descent=null;destination=null;bot.navigation.reset();}
            return;
        }
        PathSearch.Pos feet = bot.navigation.feet();
        if(bot.mc.field_3805.onGround && !PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z)) {
            PathSearch.Pos supported=PathSearch.groundedStart(bot.navigation,bot.mc.field_3805.x,feet.y,bot.mc.field_3805.z);
            if(!supported.equals(feet)) {
                // A body can still stand on a furnace/table edge while its
                // center is over air. Center on that support before exploring.
                bot.navigation.move(supported,.15);
                return;
            }
        }
        // Continue steering an existing jump. Releasing forward in midair
        // makes uphill exploration jump in place forever at the same ledge.
        if(!bot.mc.field_3805.onGround) {
            if(destination!=null) {
                try {bot.navigation.move(destination,.5);}
                catch(TaskFailure blocked) {destination=null;bot.releaseMovement();bot.navigation.reset();}
            } else bot.releaseMovement();
            return;
        }
        // Chunk arrival is not a failed exploration direction.
        if(!PathSearch.standing(bot.navigation,feet.x,feet.y,feet.z)) {
            bot.releaseMovement();return;
        }
        if (plan == null) {
            plan = new ExplorationPlan(feet.x, feet.z,unbounded);
            bot.say("Searching farther for " + resource + ". Use @stop to cancel.");
            bot.releaseMovement(); bot.navigation.reset();
        }
        if (destination == null) {
            descent=CanopyDropTask.plan(bot);
            if(descent==null) descent=StoneAccessTask.leaveCanopy(bot);
            if(descent!=null) {bot.cancelMining();bot.releaseMovement();return;}
            PathSearch.Pos next = plan.next(feet.y);
            if (next == null) throw new TaskFailure("Could not find " + resource + " after exploring accessible nearby terrain.");
            // A scan direction need not itself be a standing cell. Search toward
            // it and use a reachable endpoint, including routes down hills.
            List<PathSearch.Pos> route = PathSearch.explore(bot.navigation, feet, next, 2000);
            if (route.isEmpty() || route.get(route.size() - 1).distance(feet) < 4) {
                // Leaves or a dirt bank can enclose an otherwise safe ledge.
                // Navigation can excavate a checked route; let it try instead
                // of cycling through every scan direction without moving.
                destination=next;
            } else {
            // A partial route can advance toward the scan area without repeatedly hitting an unreachable goal.
            destination = route.get(route.size() - 1);
            }
            bot.navigation.reset(); legStarted = bot.ticks;
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
