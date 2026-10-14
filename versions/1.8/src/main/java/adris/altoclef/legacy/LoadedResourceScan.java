package adris.altoclef.legacy;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import java.util.*;
import java.util.function.BiPredicate;

/** Near-first loaded-chunk scan, all 16 sections, with the modern scanner's time/work budget. */
final class LoadedResourceScan {
    private final ResourceScan chunks;
    private final int[] sections = new int[16];
    private PathSearch.Pos chunk;
    private int section, index;
    LoadedResourceScan(PathSearch.Pos origin) {this(origin,32);}
    LoadedResourceScan(PathSearch.Pos origin,int radius) {
        chunks=new ResourceScan(origin.x>>4,origin.z>>4,radius,0,0);
        List<Integer> order=new ArrayList<>();
        for(int i=0;i<16;i++) order.add(i);
        order.sort(Comparator.comparingInt(i -> Math.abs(i-(origin.y>>4))));
        for(int i=0;i<16;i++) sections[i]=order.get(i);
    }
    boolean exhausted() { return chunk==null && chunks.exhausted(); }
    BlockPos poll(LegacyBot bot, BiPredicate<BlockPos,BlockState> matches) {
        long deadline=System.nanoTime()+4000000L;
        int visited=0;
        while(visited<65536 && System.nanoTime()<deadline) {
            if(chunk==null) {
                chunk=chunks.poll(8192,p -> true); section=index=0;
                if(chunk==null) return null;
            }
            if(!bot.mc.world.isLoaded(new BlockPos(chunk.x*16,64,chunk.z*16),false)) {chunk=null;continue;}
            ChunkSection[] storage=bot.mc.world.getChunk(chunk.x,chunk.z).getBlockStorage();
            if(section>=16) {chunk=null;continue;}
            int sy=sections[section];
            ChunkSection blocks=sy<storage.length ? storage[sy] : null;
            if(blocks==null || blocks.isEmpty()) {section++;index=0;continue;}
            for(int batch=0;batch<128 && index<4096 && visited<65536;batch++,visited++) {
                int n=index++,x=n&15,z=(n>>4)&15,y=n>>8;
                BlockState state=blocks.getBlockState(x,y,z);
                BlockPos pos=new BlockPos(chunk.x*16+x,sy*16+y,chunk.z*16+z);
                if(matches.test(pos,state)) return pos;
            }
            if(index>=4096) {section++;index=0;}
        }
        return null;
    }
}
