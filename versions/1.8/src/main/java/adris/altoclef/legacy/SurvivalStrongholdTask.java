package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;
import net.minecraft.util.math.*;
import net.minecraft.world.gen.feature.FeatureState;

/** Seed locator plus actual Survival travel and a short-drop entrance shaft. */
final class SurvivalStrongholdTask implements LegacyTask {
    private Future<BlockPos> locate;
    private Future<List<int[]>> scan;
    private LegacyTask child;
    private List<int[]> rooms;
    private int[] portalRoom;
    private boolean approachedPortal;
    private BlockPos target,entrance;
    private int phase,top,scanRoom;
    private double best=Double.POSITIVE_INFINITY;
    boolean arrived;
    boolean combatPearlCollected;
    int pearlLandings;
    boolean canPauseForCombat() {return child instanceof StructureTravelTask && ((StructureTravelTask)child).canPauseForCombat()
            || child instanceof TasPearlTravelTask && ((TasPearlTravelTask)child).canPauseForCombat();}
    BlockPos entrance() {return entrance;}
    int[] portalRoom() {return portalRoom.clone();}
    boolean nearPortal() {return entrance!=null && portalRoom!=null && portalDistance(entrance.getX(),entrance.getZ())<=8;}
    public String describe() {return "stronghold -> "+(child!=null?child.describe():phase==0?"locate from seed":phase==1?"choose entrance":phase==2?"reach access column":"dig into stronghold at "+entrance);}
    public boolean tick(LegacyBot bot) {
        if(bot.mc.getServer()==null || bot.mc.player.dimension!=0 || bot.mc.player.abilities.creativeMode)
            throw new TaskFailure("Stronghold route requires a Survival Overworld.");
        if(child!=null) {if(child.tick(bot)) {
            if(child instanceof TasPearlTravelTask) {
                combatPearlCollected|=((TasPearlTravelTask)child).collectedPearl;
                pearlLandings+=((TasPearlTravelTask)child).landings;
            }
            child.stop(bot);child=null;bot.navigation.reset();
        }return false;}
        if(phase==0) {
            if(locate==null) {
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();final BlockPos from=new BlockPos(bot.mc.player);
                locate=server.method_10815(()->server.getWorld(0).getChunkProvider().getNearestStructurePos(server.getWorld(0),"Stronghold",from));return false;
            }
            if(!locate.isDone()) return false;
            try {target=locate.get();}catch(Exception e){throw new TaskFailure("Stronghold location failed: "+e);}
            if(target==null) throw new TaskFailure("No stronghold location found.");
            bot.say("TAS_STRONGHOLD: seed locator found "+target+". Travelling in Survival.");
            child=bot.tasTravel()?new TasPearlTravelTask(target,12):new StructureTravelTask(target,12);phase=1;return false;
        }
        if(phase==1) {
            bot.releaseMovement();
            if(scan==null) {
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();
                scan=server.method_10815(()-> {
                    List<int[]> boxes=new ArrayList<>();
                    FeatureState state=(FeatureState)server.getWorld(0).getOrCreateState(FeatureState.class,"Stronghold");
                    if(state==null) return boxes;
                    NbtCompound features=state.getFeatures();
                    for(Object key:features.getKeys()) {
                        NbtCompound f=features.getCompound((String)key);
                        if(Math.abs(f.getInt("ChunkX")-(target.getX()>>4))>1 || Math.abs(f.getInt("ChunkZ")-(target.getZ()>>4))>1) continue;
                        NbtList children=f.getList("Children",10);
                        for(int i=0;i<children.size();i++) {
                            NbtCompound p=children.getCompound(i);int[] b=p.getIntArray("BB");
                            if(b.length==6 && !p.getString("id").equals("SHLi")) {
                                int[] room=Arrays.copyOf(b,7);room[6]=p.getString("id").equals("SHPR")?1:0;boxes.add(room);
                            }
                        }
                    }
                    return boxes;
                });return false;
            }
            if(!scan.isDone()) return false;
            if(rooms==null) {try{rooms=scan.get();}catch(Exception e){throw new TaskFailure("Stronghold room scan failed: "+e);}}
            if(!approachedPortal) {
                for(int[] room:rooms)if(room[6]==1)portalRoom=room;
                if(portalRoom==null)throw new TaskFailure("The generated stronghold has no recorded portal room.");
                approachedPortal=true;
                BlockPos center=new BlockPos((portalRoom[0]+portalRoom[3])/2,portalRoom[1],(portalRoom[2]+portalRoom[5])/2);
                bot.say("TAS_STRONGHOLD_PORTAL_ROOM: "+Arrays.toString(portalRoom));
                if(Math.hypot(center.getX()-bot.mc.player.x,center.getZ()-bot.mc.player.z)>12) {
                    child=bot.tasTravel()?new TasPearlTravelTask(center,8):new StructureTravelTask(center,8);return false;
                }
            }
            // One room per tick bounds collision/column work and keeps TAS controls responsive.
            if(scanRoom<rooms.size()) {findEntrance(bot,rooms.get(scanRoom++));return false;}
            if(entrance==null) throw new TaskFailure("No dry entrance shaft within eight blocks of the portal room was found.");
            bot.say("TAS_STRONGHOLD_ENTRANCE: "+entrance);
            child=new StructureTravelTask(entrance,2);phase=2;return false;
        }
        if(phase==2) {
            top=SurfaceAccessTask.terrainSurface(bot,entrance.getX(),entrance.getZ());
            if(!bot.navigation.move(new PathSearch.Pos(entrance.getX(),top,entrance.getZ()),.12)) return false;
            bot.navigation.reset();phase=3;
        }
        if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
        double dx=entrance.getX()+.5-bot.mc.player.x,dz=entrance.getZ()+.5-bot.mc.player.z;
        if(dx*dx+dz*dz>.025) {
            bot.releaseMovement();bot.look(entrance.getX()+.5,bot.mc.player.y+1.62,entrance.getZ()+.5);
            bot.key(bot.mc.options.forwardKey,true);bot.key(bot.mc.options.sneakKey,true);return false;
        }
        if(bot.mc.player.y<=entrance.getY()+.1) {arrived=true;bot.releaseMovement();bot.say("Arrived beside the stronghold portal room at "+entrance);return true;}
        BlockPos feet=new BlockPos(bot.mc.player),floor=feet.down();
        for(BlockPos p:new BlockPos[]{feet.up(),feet}) if(!bot.mc.world.isAir(p)) {bot.mine(p);return false;}
        int gap=0;while(gap<4 && !bot.navigation.floor(floor.getX(),floor.getY()-1-gap,floor.getZ()))gap++;
        if(gap>=4) throw new TaskFailure("Stronghold shaft exposed an unsafe fall.");
        for(int y=floor.getY();y>=floor.getY()-gap-1;y--) for(Direction d:Direction.values())
            if(bot.mc.world.getBlockState(new BlockPos(floor.getX(),y,floor.getZ()).offset(d)).getBlock().getMaterial().isFluid())
                throw new TaskFailure("Fluid exposed beside stronghold entrance.");
        bot.mine(floor);return false;
    }
    private void findEntrance(LegacyBot bot,int[] b) {
        for(int x=b[0]+1;x<b[3];x++) for(int z=b[2]+1;z<b[5];z++) {
            double portalDistance=portalDistance(x,z);if(portalDistance>8)continue;
            int surface=SurfaceAccessTask.terrainSurface(bot,x,z);
            if(surface<0 || !PathSearch.standing(bot.navigation,x,surface,z)) continue;
            for(int y=b[4]-2;y>b[1];y--) {
                if(!bot.mc.world.isAir(new BlockPos(x,y,z)) || !bot.mc.world.isAir(new BlockPos(x,y+1,z)) || !bot.navigation.floor(x,y-1,z)) continue;
                boolean safe=true;int air=0,stone=0;
                for(int sy=surface-1;sy>=y && safe;sy--) {
                    BlockPos p=new BlockPos(x,sy,z);Block block=bot.mc.world.getBlockState(p).getBlock();
                    if(!bot.mc.world.isLoaded(p,false) || block.getMaterial().isFluid() || block==Block.get("bedrock") || block==Block.get("obsidian") || bot.mc.world.getBlockEntity(p)!=null) {safe=false;break;}
                    if(block.isFullCube()) {air=0;if(block.getMaterial()==net.minecraft.block.material.Material.STONE)stone++;}
                    else if(++air>3) {safe=false;break;}
                    for(Direction side:Direction.values()) if(bot.mc.world.getBlockState(p.offset(side)).getBlock().getMaterial().isFluid()) safe=false;
                }
                if(!safe || stone>55) continue;
                if(y<portalRoom[1] || y>portalRoom[4])continue;
                double score=portalDistance*1000+(b[6]==1?0:2000)+Math.hypot(x-bot.mc.player.x,z-bot.mc.player.z)+stone*1.5+(surface-y)*.3;
                if(score<best) {best=score;entrance=new BlockPos(x,y,z);}
            }
        }
    }
    private double portalDistance(int x,int z) {
        if(portalRoom==null)return Double.POSITIVE_INFINITY;
        return Math.hypot(Math.max(0,Math.max(portalRoom[0]-x,x-portalRoom[3])),Math.max(0,Math.max(portalRoom[2]-z,z-portalRoom[5])));
    }
    public void stop(LegacyBot bot) {if(child!=null)child.stop(bot);if(locate!=null&&!locate.isDone())locate.cancel(false);if(scan!=null&&!scan.isDone())scan.cancel(false);bot.releaseMovement();bot.cancelMining();}
}
