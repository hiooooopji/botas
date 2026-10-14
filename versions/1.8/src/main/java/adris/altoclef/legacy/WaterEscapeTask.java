package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;

/** Bounded breadth-first shoreline search; only used when a command starts in water. */
final class WaterEscapeTask implements LegacyTask {
    private final ArrayDeque<PathSearch.Pos> open=new ArrayDeque<>();
    private final Map<PathSearch.Pos,PathSearch.Pos> previous=new HashMap<>();
    private List<PathSearch.Pos> route;
    private int next,ticks;
    private final Set<PathSearch.Pos> rejected=new HashSet<>();
    private PathSearch.Pos tracked;
    private double bestDistance=Double.POSITIVE_INFINITY;
    private int lastProgress;
    private static final int[][] SIDES={{1,0},{-1,0},{0,1},{0,-1}};
    static boolean wet(LegacyBot bot) {
        return water(bot,new BlockPos(bot.mc.player));
    }
    private static boolean water(LegacyBot bot,BlockPos pos) {
        return bot.mc.world.isLoaded(pos,false) && bot.mc.world.getBlockState(pos).getBlock().getMaterial()==Material.WATER;
    }
    public String describe() {return "swim to land";}
    public void stop(LegacyBot bot) {bot.releaseMovement();bot.navigation.reset();}
    private boolean dry(LegacyBot bot,PathSearch.Pos p) {return PathSearch.standing(bot.navigation,p.x,p.y,p.z);}
    private boolean surface(LegacyBot bot,PathSearch.Pos p) {
        return water(bot,new BlockPos(p.x,p.y-1,p.z)) && bot.navigation.clear(p.x,p.y,p.z) && bot.navigation.clear(p.x,p.y+1,p.z);
    }
    private void replan(LegacyBot bot,PathSearch.Pos blocked) {
        rejected.add(blocked);route=null;open.clear();previous.clear();tracked=null;
        bot.releaseMovement();bot.navigation.reset();
    }
    public boolean tick(LegacyBot bot) {
        PathSearch.Pos feetNow=bot.navigation.feet();
        if(bot.mc.player.onGround && !wet(bot) && PathSearch.standing(bot.navigation,
                feetNow.x,feetNow.y,feetNow.z)) return true;
        if(++ticks>2400) throw new TaskFailure("Could not reach a safe shore in loaded terrain.");
        bot.cancelMining();bot.releaseMovement();bot.key(bot.mc.options.jumpKey,true);
        if(previous.isEmpty()) {
            PathSearch.Pos feet=bot.navigation.feet();int y=feet.y;
            while(y<254 && water(bot,new BlockPos(feet.x,y,feet.z))) y++;
            PathSearch.Pos start=new PathSearch.Pos(feet.x,y,feet.z);
            previous.put(start,null);open.add(start);bot.say("Starting in water; swimming to land before gathering.");
        }
        if(route==null) {
            for(int budget=0;budget<256 && !open.isEmpty();budget++) {
                PathSearch.Pos p=open.removeFirst();
                if(dry(bot,p)) {
                    route=new ArrayList<>();
                    for(PathSearch.Pos q=p;q!=null;q=previous.get(q)) route.add(q);
                    Collections.reverse(route);next=Math.min(1,route.size()-1);break;
                }
                for(int[] d:SIDES) for(int dy=-1;dy<=1;dy++) {
                    PathSearch.Pos q=new PathSearch.Pos(p.x+d[0],p.y+dy,p.z+d[1]);
                    // Swimming starts below the nominal surface cell. Do not
                    // treat a bank another full block above that cell as a jump.
                    if(previous.containsKey(q) || rejected.contains(q) || dy>0
                            || !(dry(bot,q) || dy==0 && surface(bot,q))
                            || !bot.navigation.clear(q.x,p.y,q.z) || !bot.navigation.clear(q.x,p.y+1,q.z)) continue;
                    previous.put(q,p);open.addLast(q);
                }
            }
            if(route==null && (open.isEmpty() || previous.size()>65536))
                throw new TaskFailure("No reachable shoreline found in loaded terrain.");
            if(route==null) return false;
        }
        PathSearch.Pos goal=route.get(next);
        if(!(dry(bot,goal) || surface(bot,goal))) {replan(bot,goal);return false;}
        double dx=goal.x+.5-bot.mc.player.x,dz=goal.z+.5-bot.mc.player.z;
        if(dx*dx+dz*dz<.16 && Math.abs(goal.y-bot.mc.player.y)<1 && next+1<route.size()) {goal=route.get(++next);dx=goal.x+.5-bot.mc.player.x;dz=goal.z+.5-bot.mc.player.z;}
        double distance=dx*dx+dz*dz+Math.pow(goal.y-bot.mc.player.y,2);
        if(!goal.equals(tracked)) {tracked=goal;bestDistance=distance;lastProgress=ticks;}
        if(distance<bestDistance-.04) {bestDistance=distance;lastProgress=ticks;}
        if(ticks-lastProgress>60) {replan(bot,goal);return false;}
        bot.mc.player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));bot.mc.player.pitch=-10;
        bot.key(bot.mc.options.forwardKey,true);
        return false;
    }
}
