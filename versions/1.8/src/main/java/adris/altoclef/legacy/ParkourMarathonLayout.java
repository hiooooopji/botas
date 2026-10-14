package adris.altoclef.legacy;

import java.util.*;
import static adris.altoclef.legacy.ParkourPhysics.*;

/** Shared map geometry and rehearsal: x east, z south, y relative to the starting floor. */
public final class ParkourMarathonLayout {
    public static final int[] NEOS={12,32,52};
    public static final int FIRST_LANDING=73,SECOND_LANDING=111,BRIDGE_START=82,BRIDGE_END=98,UP_NEO=118;
    public static final int FINISH_X=121,FINISH_Z=44;
    /** Inclusive block boxes; material 0 stone, 1 obsidian, 2 emerald, 3 gold, 4 diamond, 5 lapis. */
    public static List<int[]> boxes() {
        List<int[]> result=new ArrayList<>();
        result.add(new int[]{0,0,1,65,0,1,0});
        result.add(new int[]{73,0,1,81,0,1,0});
        result.add(new int[]{78,3,1,81,3,1,0});
        result.add(new int[]{98,0,1,103,0,1,0});
        result.add(new int[]{111,0,1,118,0,1,0});
        result.add(new int[]{119,1,1,124,1,4,0});
        for(int x:NEOS)result.add(new int[]{x,1,1,x+2,3,1,1});
        result.add(new int[]{118,1,1,118,4,1,1});
        result.add(new int[]{120,1,8,122,1,12,0});
        result.add(new int[]{120,1,16,122,1,20,0});
        result.add(new int[]{120,2,23,122,2,27,0});
        result.add(new int[]{120,2,31,122,2,35,0});
        result.add(new int[]{120,1,40,122,1,46,0});
        result.add(new int[]{0,0,1,0,0,1,2});
        for(int x:new int[]{65,73,103,111})result.add(new int[]{x,0,1,x,0,1,3});
        for(int x:new int[]{81,98})result.add(new int[]{x,0,1,x,0,1,4});
        result.add(new int[]{119,1,1,119,1,1,5});
        result.add(new int[]{120,1,44,122,1,46,2});
        return result;
    }
    public static List<Solid> geometry() {
        List<Solid> result=new ArrayList<>();
        // Decorative replacements have the same collision boxes as their underlying floor.
        for(int[] b:boxes())if(b[6]<2)result.add(new Solid(b[0],b[1],b[2],b[3]+1,b[4]+1,b[5]+1));
        return result;
    }
    public static ParkourCoursePlan.Tape rehearse(State start,List<Solid> world,int x,int y,int z) {
        List<Solid> geometry=new ArrayList<>(world);List<PathSearch.Pos> goals=new ArrayList<>();
        for(int along:new int[]{17,37,57,62})goals.add(new PathSearch.Pos(x+along,y+1,z+1));
        ParkourCoursePlan.Tape tape=ParkourCoursePlan.through(start,goals,geometry);
        ParkourCoursePlan.extendedGap(tape,new PathSearch.Pos(x+75,y+1,z+1),geometry,x+FIRST_LANDING,y,z+1,3);
        ParkourCoursePlan.append(tape,new PathSearch.Pos(x+79,y+1,z+1),geometry);
        ParkourCoursePlan.bridge(tape,geometry,x+BRIDGE_START,x+BRIDGE_END,y,z+1);
        for(int i=tape.bridgeStart;i<tape.bridgeEnd;i++)if(tape.frames.get(i).placement!=null)geometry.add(tape.frames.get(i).placement.block());
        ParkourCoursePlan.extendedGap(tape,new PathSearch.Pos(x+114,y+1,z+1),geometry,x+SECOND_LANDING,y,z+1,3);
        ParkourCoursePlan.append(tape,new PathSearch.Pos(x+121,y+2,z+2),geometry);
        for(int[] target:new int[][]{{121,2,3},{121,2,10},{121,2,18},{121,3,25},{121,3,33},{121,2,44}})
            ParkourCoursePlan.append(tape,new PathSearch.Pos(x+target[0],y+target[1],z+target[2]),geometry);
        return tape;
    }
}
