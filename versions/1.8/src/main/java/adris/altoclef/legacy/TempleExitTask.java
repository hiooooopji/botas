package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.math.BlockPos;

/** Temple looting finishes on supported outdoor ground outside the structure. */
final class TempleExitTask implements LegacyTask {
    private final SurfaceStructureScan.Site temple;
    private LegacyTask child;
    private int ticks,failures;
    TempleExitTask(SurfaceStructureScan.Site temple) {this.temple=temple;}
    public String describe() {return "leave temple for open surface"+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.cancelMining();bot.releaseMovement();bot.navigation.reset();}
    boolean outside(LegacyBot bot) {
        PathSearch.Pos p=bot.navigation.feet();
        return bot.mc.player.onGround && !temple.contains(new BlockPos(p.x,p.y,p.z))
                && PathSearch.standing(bot.navigation,p.x,p.y,p.z)
                && SurfaceAccessTask.terrainSurface(bot,p.x,p.z)<=p.y;
    }
    public boolean tick(LegacyBot bot) {
        if(++ticks>6000) throw new TaskFailure("Could not safely leave the desert temple.");
        if(outside(bot)) {stop(bot);return true;}
        if(child!=null) {
            try {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}}
            catch(TaskFailure failure) {
                child.stop(bot);child=null;
                if(++failures>=3) throw failure;
            }
            return false;
        }
        if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
        PathSearch.Pos from=PathSearch.groundedStart(bot.navigation,bot.mc.player.x,bot.navigation.feet().y,bot.mc.player.z);
        int[] b=temple.bounds;
        List<PathSearch.Pos> exits=new ArrayList<>();
        for(int x=b[0]-3;x<=b[3]+3;x+=2) for(int z=b[2]-3;z<=b[5]+3;z+=2) {
            if(x>=b[0] && x<=b[3] && z>=b[2] && z<=b[5]) continue;
            int y=SurfaceAccessTask.terrainSurface(bot,x,z);
            if(y>0 && PathSearch.standing(bot.navigation,x,y,z)) exits.add(new PathSearch.Pos(x,y,z));
        }
        exits.sort(Comparator.comparingDouble(from::distance));
        for(int i=0;i<Math.min(16,exits.size());i++) {
            PathSearch.Pos exit=exits.get(i);
            List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,exit,3000);
            if(!route.isEmpty() && route.get(route.size()-1).equals(exit)) {
                child=new MovementTasks.GoTo(exit.x,exit.y,exit.z,false);return false;
            }
        }
        if(SurfaceAccessTask.underground(bot)) {child=new SurfaceAccessTask();return false;}
        if(exits.isEmpty()) throw new TaskFailure("No loaded dry ground outside the temple.");
        PathSearch.Pos exit=exits.get(0);
        child=new StructureTravelTask(new BlockPos(exit.x,exit.y,exit.z),1);
        return false;
    }
}
