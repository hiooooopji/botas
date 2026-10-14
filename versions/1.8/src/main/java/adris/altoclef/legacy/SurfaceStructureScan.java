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
        final int iron,picks,buckets,igniters,bones;
        Loot(BlockPos p,BlockPos e,boolean t,int iron,int picks,int buckets,int igniters,int bones) {
            pos=p;entry=e;temple=t;this.iron=iron;this.picks=picks;this.buckets=buckets;this.igniters=igniters;this.bones=bones;
        }
    }
    static final class Site {
        final BlockPos center,entry;
        final boolean temple;
        final int[] bounds;
        Site(int[] bounds,boolean temple,World world) {
            this.bounds=bounds.clone();this.temple=temple;
            center=new BlockPos((bounds[0]+bounds[3])/2,0,(bounds[2]+bounds[5])/2);
            int x=temple ? center.getX()+4 : bounds[0]-2;
            entry=world.getHighestBlock(new BlockPos(x,0,center.getZ()));
        }
        boolean contains(BlockPos p) {return p.getX()>=bounds[0] && p.getX()<=bounds[3]
                && p.getZ()>=bounds[2] && p.getZ()<=bounds[5];}
    }
    static int usableIron(Collection<Loot> loot) {
        return usableIron(loot,0,0,0,0);
    }
    static int usableIron(Collection<Loot> loot,int iron,int picks,int buckets,int igniters) {
        for(Loot l:loot) {iron+=l.iron;picks+=l.picks;buckets+=l.buckets;igniters+=l.igniters;}
        return IronKitBudget.usable(iron,picks,buckets,igniters);
    }
    private static final class TempleProbe extends TempleStructure {
        boolean at(World w,int x,int z) {world=w;return shouldStartAt(x,z);}
    }
    private static final class VillageProbe extends VillageStructure {
        boolean at(World w,int x,int z) {world=w;return shouldStartAt(x,z);}
    }
    private final BlockPos spawn;
    private final boolean templesOnly;
    private final ArrayDeque<BlockPos> chunks=new ArrayDeque<>();
    private final Set<BlockPos> queued=new HashSet<>();
    private final List<NbtCompound> pieces=new ArrayList<>();
    private int phase;
    final List<Loot> loot=new ArrayList<>();
    final List<Loot> chests=new ArrayList<>();
    final List<Site> sites=new ArrayList<>();
    SurfaceStructureScan(BlockPos spawn) {this(spawn,false);}
    SurfaceStructureScan(BlockPos spawn,boolean templesOnly) {this.spawn=spawn;this.templesOnly=templesOnly;}
    private void queue(int x,int z) {BlockPos p=new BlockPos(x,0,z);if(queued.add(p)) chunks.add(p);}
    boolean advance(World world) {
        if(phase==0) {
            TempleProbe temple=new TempleProbe();VillageProbe village=new VillageProbe();
            int reach=RADIUS+128;
            for(int x=(spawn.getX()-reach)>>4;x<=(spawn.getX()+reach)>>4;x++)
                for(int z=(spawn.getZ()-reach)>>4;z<=(spawn.getZ()+reach)>>4;z++)
                    if(temple.at(world,x,z) || !templesOnly && village.at(world,x,z))
                        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) queue(x+dx,z+dz);
            phase=1;return false;
        }
        // At most one chunk load/generation per server job, keeping scanning bounded.
        if(!chunks.isEmpty()) {BlockPos p=chunks.removeFirst();world.getChunk(p.getX(),p.getZ());return false;}
        if(phase==1) {
            for(String name:templesOnly?new String[]{"Temple"}:new String[]{"Temple","Village"}) {
                FeatureState state=(FeatureState)world.getOrCreateState(FeatureState.class,name);
                if(state==null) continue;
                NbtCompound features=state.getFeatures();
                for(Object key:features.getKeys()) {
                    NbtCompound feature=features.getCompound((String)key);
                    NbtList children=feature.getList("Children",10);
                    if(name.equals("Village") && !children.isEmpty()) {
                        int[] whole=feature.getIntArray("BB");
                        if(whole.length!=6) {
                            whole=new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,
                                    Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
                            for(int j=0;j<children.size();j++) {
                                int[] part=children.getCompound(j).getIntArray("BB");
                                if(part.length!=6) continue;
                                for(int k=0;k<3;k++) {whole[k]=Math.min(whole[k],part[k]);whole[k+3]=Math.max(whole[k+3],part[k+3]);}
                            }
                        }
                        if(whole[0]!=Integer.MAX_VALUE) {
                            int nearX=Math.max(whole[0],Math.min(spawn.getX(),whole[3]));
                            int nearZ=Math.max(whole[2],Math.min(spawn.getZ(),whole[5]));
                            if(distance(nearX,nearZ)<=RADIUS*RADIUS) sites.add(new Site(whole,false,world));
                        }
                    }
                    for(int i=0;i<children.size();i++) {
                        NbtCompound piece=children.getCompound(i);String id=piece.getString("id");
                        if(!id.equals("TeDP") && !id.equals("ViS")) continue;
                        int[] b=piece.getIntArray("BB");if(b.length!=6) continue;
                        int x=Math.max(b[0],Math.min(spawn.getX(),b[3])),z=Math.max(b[2],Math.min(spawn.getZ(),b[5]));
                        if(distance(x,z)>RADIUS*RADIUS) continue;
                        if(id.equals("TeDP")) sites.add(new Site(b,true,world));
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
                for(Object value:world.getChunk(x,z).getBlockEntities().values()) {
                    if(!(value instanceof ChestBlockEntity)) continue;
                    ChestBlockEntity chest=(ChestBlockEntity)value;BlockPos p=chest.getPos();
                    if(p.getX()<b[0] || p.getX()>b[3] || p.getZ()<b[2] || p.getZ()>b[5]
                            || distance(p.getX(),p.getZ())>RADIUS*RADIUS || !seen.add(p)) continue;
                    if(temple && (p.getY()<b[1]-20 || p.getY()>b[4]
                            || Math.abs(p.getX()-(b[0]+b[3])/2)+Math.abs(p.getZ()-(b[2]+b[5])/2)!=2)) continue;
                    // Blacksmith chests are above ground; reject unrelated subterranean chests.
                    if(!temple && p.getY()<world.getHighestBlock(p).getY()-8) continue;
                    int iron=0,picks=0,buckets=0,igniters=0,bones=0;
                    for(int slot=0;slot<chest.getInvSize();slot++) {
                        ItemStack s=chest.getInvStack(slot);if(s==null) continue;
                        String id=LegacyItems.id(s.getItem());
                        if(id.equals("iron_ingot")) iron+=s.count;
                        if(id.equals("iron_pickaxe")) picks+=s.count;
                        if(id.equals("bucket")) buckets+=s.count;
                        if(id.equals("flint_and_steel")) igniters+=s.count;
                        if(id.equals("bone")) bones+=s.count;
                    }
                    Loot found=new Loot(p,world.getHighestBlock(new BlockPos(temple ? (b[0]+b[3])/2+4 : b[0]-2,0,(b[2]+b[5])/2)),temple,iron,picks,buckets,igniters,bones);
                    chests.add(found);
                    if(iron+picks+buckets+igniters>0)
                        loot.add(found);
                }
            }
        }
        loot.sort(Comparator.comparingLong(l->distance(l.pos.getX(),l.pos.getZ())));
        chests.sort(Comparator.comparingLong(l->distance(l.pos.getX(),l.pos.getZ())));
        sites.sort(Comparator.comparingLong(s->distance(s.center.getX(),s.center.getZ())));phase=3;return true;
    }
    private long distance(int x,int z) {long dx=x-spawn.getX(),dz=z-spawn.getZ();return dx*dx+dz*dz;}
}
