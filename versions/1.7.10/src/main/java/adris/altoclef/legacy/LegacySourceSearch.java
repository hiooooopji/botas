package adris.altoclef.legacy;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.util.math.*;

/** Finds the nearest source with an existing walking route to a mining face. */
final class LegacySourceSearch {
    static BlockPos accessible(LegacyBot bot,Predicate<BlockState> matches,int radius,
                               Set<BlockPos> rejected,Predicate<BlockPos> allowed) {
        PathSearch.Pos feet=bot.navigation.feet();
        List<BlockPos> candidates=new ArrayList<>();
        for(int x=feet.x-radius;x<=feet.x+radius;x++) for(int z=feet.z-radius;z<=feet.z+radius;z++)
            for(int y=Math.max(1,feet.y-8);y<=Math.min(254,feet.y+10);y++) {
                BlockPos p=new BlockPos(x,y,z);
                if(rejected.contains(p) || !LegacyWorld.isLoaded(bot.mc.world,p,false)
                        || !matches.test(LegacyWorld.state(bot.mc.world,p)) || !allowed.test(p)) continue;
                candidates.add(p);
            }
        candidates.sort(Comparator.comparingDouble(p->LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p)));
        // Bound expensive route probes independently for lower and level/upper
        // sources so a dense buried vein cannot hide all surface alternatives.
        int lower=0,upper=0;
        for(BlockPos p:candidates) {
            if(bot.hit(p)!=null) return p;
            if(p.y<feet.y-2) {if(++lower>8) continue;} else if(++upper>16) continue;
            if(canWalkToFace(bot,p,feet)) return p;
        }
        return null;
    }
    private static boolean canWalkToFace(LegacyBot bot,BlockPos block,PathSearch.Pos from) {
        List<PathSearch.Pos> stances=new ArrayList<>();
        for(int x=block.x-2;x<=block.x+2;x++) for(int z=block.z-2;z<=block.z+2;z++)
            for(int y=block.y-2;y<=block.y+1;y++) {
                if(!PathSearch.standing(bot.navigation,x,y,z)) continue;
                if(bot.hitFrom(Vec3d.of(x+.5,y+1.62,z+.5),block)==null) continue;
                stances.add(new PathSearch.Pos(x,y,z));
            }
        stances.sort(Comparator.comparingDouble(from::distance));
        for(int i=0;i<Math.min(4,stances.size());i++) {
            PathSearch.Pos p=stances.get(i);
            if(from.equals(p)) return true;
            List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,p,1800);
            if(!route.isEmpty() && route.get(route.size()-1).equals(p)) return true;
        }
        return false;
    }
}
