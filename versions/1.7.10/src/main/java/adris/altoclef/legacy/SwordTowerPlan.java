package adris.altoclef.legacy;

import java.util.*;

/** Two-block fighting clearance, non-spawning leaf roof, and a recoverable reset pillar. */
public final class SwordTowerPlan {
    public static final int RESET_HEIGHT=131;
    public static final int PILLAR_BLOCKS=RESET_HEIGHT-3;
    public static List<PathSearch.Pos> shelter() {
        List<PathSearch.Pos> result=new ArrayList<>();
        for(int y=0;y<2;y++) for(int x:new int[]{-2,2}) for(int z:new int[]{-2,2}) result.add(new PathSearch.Pos(x,y,z));
        // Work from the supported south-east corner across the roof.
        for(int distance=0;distance<=8;distance++) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
            if(4-x-z==distance) result.add(new PathSearch.Pos(x,2,z));
        result.add(new PathSearch.Pos(3,0,2));
        result.add(new PathSearch.Pos(3,1,2));
        result.add(new PathSearch.Pos(4,0,2));
        return Collections.unmodifiableList(result);
    }
    public static int leavesRequired() {return shelter().size()+PILLAR_BLOCKS+12;}
    private SwordTowerPlan() {}
}
