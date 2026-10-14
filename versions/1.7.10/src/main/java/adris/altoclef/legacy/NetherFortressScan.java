package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.world.World;
import net.minecraft.structure.NetherFortressStructure;
import net.minecraft.world.gen.feature.FeatureState;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;

/** Incremental integrated-server scan; never reads server terrain on the client thread. */
final class NetherFortressScan {
    private static final class Probe extends NetherFortressStructure {
        boolean at(World w,int x,int z) {world=w;return shouldStartAt(x,z);}
    }
    private final BlockPos start;
    private final ArrayDeque<BlockPos> chunks=new ArrayDeque<>();
    private final ArrayDeque<int[]> pieces=new ArrayDeque<>();
    private int phase;
    private BlockPos best;
    volatile String progress="read structure data";
    NetherFortressScan(BlockPos start) {this.start=start;}
    BlockPos result() {return best;}
    boolean advance(World w) {
        progress="scan phase "+phase+", "+chunks.size()+" chunks, "+pieces.size()+" pieces remaining";
        if(phase==0) {
            // Vanilla has already recorded structures around the loaded portal.
            // Inspect those first instead of generating every candidate in 32 chunks.
            FeatureState existing=(FeatureState)w.getOrCreateState(FeatureState.class,"Fortress");
            if(existing!=null && !existing.getFeatures().getKeys().isEmpty()) {phase=1;return false;}
            Probe probe=new Probe();Set<BlockPos> unique=new HashSet<>();
            for(int x=(start.x>>4)-32;x<=(start.x>>4)+32;x++)
                for(int z=(start.z>>4)-32;z<=(start.z>>4)+32;z++)
                    if(probe.at(w,x,z)) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) unique.add(new BlockPos(x+dx,0,z+dz));
            List<BlockPos> ordered=new ArrayList<>(unique);
            ordered.sort(Comparator.comparingLong(p->distance(p.x*16,p.z*16)));
            chunks.addAll(ordered);phase=1;return false;
        }
        if(!chunks.isEmpty()) {
            BlockPos p=chunks.remove();w.getChunk(p.x,p.z);
            FeatureState generated=(FeatureState)w.getOrCreateState(FeatureState.class,"Fortress");
            if(generated!=null && !generated.getFeatures().getKeys().isEmpty()) chunks.clear();
            return false;
        }
        if(phase==1) {
            FeatureState state=(FeatureState)w.getOrCreateState(FeatureState.class,"Fortress");
            if(state!=null) for(Object key:state.getFeatures().getKeys()) {
                NbtList children=state.getFeatures().getCompound((String)key).getList("Children",10);
                for(int i=0;i<children.size();i++) {
                    int[] b=children.getCompound(i).getIntArray("BB");
                    if(b.length==6 && distance((b[0]+b[3])/2,(b[2]+b[5])/2)<=512L*512) pieces.add(b);
                }
            }
            List<int[]> sorted=new ArrayList<>(pieces);
            sorted.sort(Comparator.comparingLong(b->distance(Math.max(b[0],Math.min(start.x,b[3])),Math.max(b[2],Math.min(start.z,b[5])))));
            pieces.clear();pieces.addAll(sorted);
            phase=2;return false;
        }
        if(pieces.isEmpty()) return true;
        int[] b=pieces.remove();
        // Choose actual walkable brick, not a bounding-box center inside a wall.
        for(int x=b[0];x<=b[3];x+=2) for(int z=b[2];z<=b[5];z+=2)
            for(int y=b[4];y>=b[1];y--) {
                BlockPos floor=new BlockPos(x,y,z);
                if(LegacyWorld.state(w,floor).getBlock()!=net.minecraft.block.Block.get("nether_brick")
                        || !LegacyWorld.isAir(w,LegacyPos.up(floor)) || !LegacyWorld.isAir(w,LegacyPos.up(LegacyPos.up(floor)))) continue;
                if(best==null || distance(x,z)<distance(best.x,best.z)) best=LegacyPos.up(floor);
                break;
            }
        return best!=null;
    }
    private long distance(int x,int z) {long dx=x-start.x,dz=z-start.z;return dx*dx+dz*dz;}
}
