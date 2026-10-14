package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.world.World;
import net.minecraft.structure.*;
import net.minecraft.world.gen.feature.FeatureState;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;

/** Runs only on the integrated server thread. Reads real generated chest inventories. */
final class SurfaceStructureScan {
    static final int RADIUS=512;
    static final class Loot {
        final BlockPos pos,entry;
        final boolean temple;
        final int iron,picks,buckets,igniters;
        Loot(BlockPos p,BlockPos e,boolean t,int iron,int picks,int buckets,int igniters) {
            pos=p;entry=e;temple=t;this.iron=iron;this.picks=picks;this.buckets=buckets;this.igniters=igniters;
        }
    }
    static int usableIron(Collection<Loot> loot) {
        return usableIron(loot,0,0,0,0);
    }
    static int usableIron(Collection<Loot> loot,int iron,int picks,int buckets,int igniters) {
        for(Loot l:loot) {iron+=l.iron;picks+=l.picks;buckets+=l.buckets;igniters+=l.igniters;}
        return IronKitBudget.oneBucketWithShears(iron,picks,buckets,igniters,0);
    }
    private static final class TempleProbe extends TempleStructure {
        boolean at(World w,int x,int z) {world=w;return shouldStartAt(x,z);}
    }
    private static final class VillageProbe extends VillageStructure {
        boolean at(World w,int x,int z) {world=w;return shouldStartAt(x,z);}
    }
    private final BlockPos spawn;
    private final int radius;
    private final ArrayDeque<BlockPos> chunks=new ArrayDeque<>();
    private final Set<BlockPos> queued=new HashSet<>();
    private final List<NbtCompound> pieces=new ArrayList<>();
    private int phase;
    final List<Loot> loot=new ArrayList<>();
    SurfaceStructureScan(BlockPos spawn) {this(spawn,RADIUS);}
    SurfaceStructureScan(BlockPos spawn,int radius) {this.spawn=spawn;this.radius=radius;}
    private void queue(int x,int z) {BlockPos p=new BlockPos(x,0,z);if(queued.add(p)) chunks.add(p);}
    boolean advance(World world) {
        if(phase==0) {
            TempleProbe temple=new TempleProbe();VillageProbe village=new VillageProbe();
            int reach=radius+128;
            for(int x=(spawn.x-reach)>>4;x<=(spawn.x+reach)>>4;x++)
                for(int z=(spawn.z-reach)>>4;z<=(spawn.z+reach)>>4;z++)
                    if(temple.at(world,x,z) || village.at(world,x,z))
                        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) queue(x+dx,z+dz);
            phase=1;return false;
        }
        // At most one chunk load/generation per server job, keeping scanning bounded.
        if(!chunks.isEmpty()) {BlockPos p=chunks.removeFirst();
            ((net.minecraft.world.chunk.ServerChunkProvider)world.getChunkProvider()).getOrGenerateChunk(p.x,p.z);return false;}
        if(phase==1) {
            for(String name:new String[]{"Temple","Village"}) {
                FeatureState state=(FeatureState)world.getOrCreateState(FeatureState.class,name);
                if(state==null) continue;
                NbtCompound features=state.getFeatures();
                for(Object key:features.getKeys()) {
                    NbtCompound feature=features.getCompound((String)key);
                    NbtList children=feature.getList("Children",10);
                    for(int i=0;i<children.size();i++) {
                        NbtCompound piece=children.getCompound(i);String id=piece.getString("id");
                        if(!id.equals("TeDP") && !id.equals("ViS")) continue;
                        int[] b=piece.getIntArray("BB");if(b.length!=6) continue;
                        int x=Math.max(b[0],Math.min(spawn.x,b[3])),z=Math.max(b[2],Math.min(spawn.z,b[5]));
                        if(distance(x,z)>radius*radius) continue;
                        pieces.add((NbtCompound)piece.copy());
                        for(int cx=(b[0]>>4)-1;cx<=(b[3]>>4)+1;cx++)
                            for(int cz=(b[2]>>4)-1;cz<=(b[5]>>4)+1;cz++) queue(cx,cz);
                    }
                }
            }
            phase=2;return false;
        }
        Set<BlockPos> seen=new HashSet<>();
        for(NbtCompound piece:pieces) {
            int[] b=piece.getIntArray("BB");boolean temple=piece.getString("id").equals("TeDP");
            for(int x=b[0]>>4;x<=b[3]>>4;x++) for(int z=b[2]>>4;z<=b[5]>>4;z++) {
                for(Object value:world.getChunk(x,z).blockEntities.values()) {
                    if(!(value instanceof ChestBlockEntity)) continue;
                    ChestBlockEntity chest=(ChestBlockEntity)value;BlockPos p=new BlockPos(chest.x,chest.y,chest.z);
                    if(p.x<b[0] || p.x>b[3] || p.z<b[2] || p.z>b[5]
                            || distance(p.x,p.z)>radius*radius || !seen.add(p)) continue;
                    if(temple && (p.y<b[1]-20 || p.y>b[4]
                            || Math.abs(p.x-(b[0]+b[3])/2)+Math.abs(p.z-(b[2]+b[5])/2)!=2)) continue;
                    // Blacksmith chests are above ground; reject unrelated subterranean chests.
                    if(!temple && p.y<LegacyWorld.highestBlock(world,p).y-8) continue;
                    int iron=0,picks=0,buckets=0,igniters=0;
                    for(int slot=0;slot<chest.getInvSize();slot++) {
                        ItemStack s=chest.getInvStack(slot);if(s==null) continue;
                        String id=LegacyItems.id(s.getItem());
                        if(id.equals("iron_ingot")) iron+=s.count;
                        if(id.equals("iron_pickaxe")) picks+=s.count;
                        if(id.equals("bucket")) buckets+=s.count;
                        if(id.equals("flint_and_steel")) igniters+=s.count;
                    }
                    if(temple || iron+picks+buckets+igniters>0)
                        loot.add(new Loot(p,LegacyWorld.highestBlock(world,new BlockPos(temple ? (b[0]+b[3])/2+4 : b[0]-2,0,(b[2]+b[5])/2)),temple,iron,picks,buckets,igniters));
                }
            }
        }
        loot.sort(Comparator.comparingLong(l->distance(l.pos.x,l.pos.z)));phase=3;return true;
    }
    private long distance(int x,int z) {long dx=x-spawn.x,dz=z-spawn.z;return dx*dx+dz*dz;}
}
