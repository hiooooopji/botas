package adris.altoclef.legacy;

import java.util.*;

/** Exact unique missing floor blocks for a one-wide crossing, plus a repair reserve. */
public final class NetherBridgeBudget {
    public static List<PathSearch.Pos> supports(PathSearch.Terrain terrain,PathSearch.Pos from,List<PathSearch.Pos> route) {
        Set<PathSearch.Pos> missing=new LinkedHashSet<>();
        for(PathSearch.Pos p:route) {
            if(!terrain.floor(p.x,p.y-1,p.z)) {
                add(terrain,missing,new PathSearch.Pos(p.x,p.y-1,p.z));
            }
            from=p;
        }
        return new ArrayList<>(missing);
    }
    private static void add(PathSearch.Terrain t,Set<PathSearch.Pos> set,PathSearch.Pos p) {
        if(!t.floor(p.x,p.y,p.z)) set.add(p);
    }
    public static int required(int missing) {return missing==0 ? 0 : missing+8;}
    private NetherBridgeBudget() {}
}
