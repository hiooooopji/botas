package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;

/** Short emergency escape from a spreading lava flow, without aiming over a lava sea. */
final class NetherLavaEscape {
    private PathSearch.Pos goal;
    private int until;
    private final Set<PathSearch.Pos> rejected=new HashSet<>();
    String describe() {return goal==null ? "check nearby lava" : "avoid lava toward "+goal;}
    private static boolean lava(LegacyBot b,int x,int y,int z) {
        return b.mc.world.getBlockState(new BlockPos(x,y,z)).getBlock().getMaterial()==Material.LAVA;
    }
    private static boolean threatened(LegacyBot b,PathSearch.Pos p) {
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=0;dy<=2;dy++)
            if(lava(b,p.x+dx,p.y+dy,p.z+dz)) {
                net.minecraft.util.math.Vec3d fluid=new net.minecraft.util.math.Vec3d(p.x+dx+.5,p.y+dy+.5,p.z+dz+.5);
                // Solid walls and the sealed shaft roof stop the flow. Do not
                // repeatedly flee lava that cannot reach the walking corridor.
                for(double height:new double[]{.25,1.5})
                    if(b.mc.world.rayTrace(new net.minecraft.util.math.Vec3d(p.x+.5,p.y+height,p.z+.5),fluid)==null) return true;
            }
        return false;
    }
    boolean tick(LegacyBot bot) {
        PathSearch.Pos feet=bot.navigation.feet();
        boolean wet=lava(bot,feet.x,feet.y,feet.z);
        if(!wet && goal==null && !threatened(bot,feet)) return false;
        if(goal!=null && (bot.ticks>until || !PathSearch.standing(bot.navigation,goal.x,goal.y,goal.z))) {rejected.add(goal);goal=null;bot.navigation.reset();}
        if(goal==null) {
            PathSearch.Terrain escape=new PathSearch.Terrain() {
                public boolean clear(int x,int y,int z) {return bot.navigation.clear(x,y,z) || lava(bot,x,y,z);}
                public boolean floor(int x,int y,int z) {return bot.navigation.floor(x,y,z);}
            };
            List<PathSearch.Pos> choices=new ArrayList<>();
            for(int dx=-6;dx<=6;dx++) for(int dz=-6;dz<=6;dz++) for(int dy=-1;dy<=2;dy++) {
                PathSearch.Pos p=new PathSearch.Pos(feet.x+dx,feet.y+dy,feet.z+dz);
                if(!rejected.contains(p) && PathSearch.standing(bot.navigation,p.x,p.y,p.z) && !threatened(bot,p)) choices.add(p);
            }
            choices.sort(Comparator.comparingDouble(feet::distance));
            for(int i=0;i<Math.min(16,choices.size());i++) {
                PathSearch.Pos p=choices.get(i);List<PathSearch.Pos> path=PathSearch.find(wet ? escape : bot.navigation,feet,p,1500);
                if(!path.isEmpty() && path.get(path.size()-1).equals(p)) {goal=p;until=bot.ticks+160;break;}
            }
        }
        if(goal==null) return false;
        if(!wet) {
            try {if(bot.navigation.move(goal,.2)) {goal=null;bot.navigation.reset();}return true;}
            catch(TaskFailure blocked) {rejected.add(goal);goal=null;bot.navigation.reset();return false;}
        }
        bot.releaseMovement();bot.cancelMining();
        bot.look(goal.x+.5,bot.mc.player.y+bot.mc.player.getEyeHeight(),goal.z+.5);
        bot.key(bot.mc.options.forwardKey,true);bot.key(bot.mc.options.jumpKey,true);return true;
    }
}
