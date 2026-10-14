package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

/** Real bed placement, pressure-plate crafting and Eye insertion; completes on End arrival. */
final class TasEndEntryTask implements LegacyTask {
    private final int[] room;
    private LegacyTask child;
    private Future<Boolean> respawn;
    private PathSearch.Pos stance;
    private int orientation,phase,ticks,waitUntil;
    private BlockPos portal;
    BlockPos bedFoot,bedHead;
    boolean spawnSet,entering,entered;
    int eyesUsed;
    private int eyesBefore;
    private boolean spawnerCleared;
    private boolean entranceSealed;
    private PathSearch.Pos floorExit;
    private PathSearch.Pos column;
    private BlockPos platform;
    private final List<BlockPos> bridge=new ArrayList<>();
    private int platformStage,bridgeIndex;
    private int lastAttack;
    private int bedAttempts;
    private final Set<BlockPos> rejectedBeds=new HashSet<>();
    TasEndEntryTask(int[] room) {this.room=room.clone();}
    public String describe() {return "prepare portal room -> "+(child!=null?child.describe():phase==0?"secure room and craft wooden pressure plate":phase==1?"place bed":phase==2?"set bed respawn":phase==3?"fill End portal":"enter End");}
    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension==1) {entered=true;bot.releaseMovement();bot.cancelMining();bot.say("TAS_END_ARRIVED: End entry confirmed; bed respawn is saved in the Overworld.");return true;}
        if(++ticks>2400)throw new TaskFailure("Portal-room setup timed out.");
        if(bot.mc.player.dimension!=0)throw new TaskFailure("End entry must start in the Overworld.");
        if(defendHostiles(bot))return false;
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(bot.ticks<waitUntil) {bot.releaseMovement();return false;}
        if(phase==0) {
            if(!spawnerCleared) {
                BlockPos spawner=bot.nearestBlock(s->s.getBlock()==Block.get("mob_spawner"),24,Collections.emptySet(),
                        p->p.getX()>=room[0] && p.getX()<=room[3] && p.getZ()>=room[2] && p.getZ()<=room[5] && p.getY()>=room[1] && p.getY()<=room[4]);
                if(spawner!=null) {bot.mine(spawner);return false;}
                spawnerCleared=true;bot.cancelMining();bot.navigation.reset();
            }
            if(!descendToRoomFloor(bot))return false;
            if(!entranceSealed) {
                if(bot.count(LegacyItems.parse("planks"))<2) {child=new AcquisitionTask(LegacyItems.parse("planks"),2,"craft");return false;}
                // Close natural doorways before placing the respawn bed. The
                // return journey is through the portal, so no exit door is needed.
                for(int x=room[0];x<=room[3];x++)for(int z=room[2];z<=room[5];z++) {
                    if(x!=room[0] && x!=room[3] && z!=room[2] && z!=room[5])continue;
                    BlockPos p=new BlockPos(x,room[1]+1,z);
                    if(bot.mc.world.isAir(p) && bot.mc.world.isAir(p.up()) && net.minecraft.world.World.isOpaque(bot.mc.world,p.down())) {
                        child=new LegacyPlaceTask(p,"planks");return false;
                    }
                    if(bot.mc.world.getBlockState(p).getBlock()==Block.get("planks") && bot.mc.world.isAir(p.up())) {
                        child=new LegacyPlaceTask(p.up(),"planks");return false;
                    }
                }
                entranceSealed=true;bot.say("TAS_PORTAL_ROOM_SECURED: natural doorway sealed before placing the bed.");
            }
            if(bot.count(LegacyItems.parse("wooden_pressure_plate"))<1) {child=new AcquisitionTask(LegacyItems.parse("wooden_pressure_plate"),1,"craft");return false;}
            phase=1;
        }
        if(phase==1) {
            if(bedFoot==null)chooseBed(bot);
            if(bot.mc.world.getBlockState(bedFoot).getBlock()==Block.get("bed") && bot.mc.world.getBlockState(bedHead).getBlock()==Block.get("bed")) {phase=2;bot.navigation.reset();}
            else {
                if(bedAttempts>=4) {
                    rejectedBeds.add(bedFoot);bedFoot=bedHead=null;bedAttempts=0;bot.navigation.reset();return false;
                }
                if(!bot.navigation.movePrecisely(stance,.18))return false;
                int slot=bot.findInventory(LegacyItems.parse("bed"));
                if(slot<0)throw new TaskFailure("The bed is missing before placement.");
                bot.releaseMovement();bot.equip(slot);
                bot.mc.player.yaw=orientation*90;bot.mc.player.pitch=65;LegacyFluidTask.syncAim(bot);
                Vec3d top=new Vec3d(bedFoot.getX()+.5,bedFoot.getY()-.001,bedFoot.getZ()+.5);
                bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),bedFoot.down(),Direction.UP,top);
                bedAttempts++;
                waitUntil=bot.ticks+2;return false;
            }
        }
        if(phase==2) {
            bot.releaseMovement();
            if(respawn==null) {
                final net.minecraft.server.integrated.IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
                final String name=bot.mc.player.getGameProfile().getName();
                respawn=server.method_10815(()-> {
                    ServerPlayerEntity p=server.getPlayerManager().getPlayer(name);
                    if(p==null || p.dimension!=0 || server.getWorld(0).getBlockState(bedFoot).getBlock()!=Block.get("bed")
                            || server.getWorld(0).getBlockState(bedHead).getBlock()!=Block.get("bed")
                            || PlayerEntity.findRespawnPosition(server.getWorld(0),bedHead,false)==null)return false;
                    // The private TAS route records the actual placed bed without
                    // changing the time of day or waiting until night to sleep.
                    p.setPlayerSpawn(bedHead,false);server.getPlayerManager().saveAllPlayerData();return bedHead.equals(p.getSpawnPosition());
                });return false;
            }
            if(!respawn.isDone())return false;
            try {if(!respawn.get())throw new TaskFailure("The server could not confirm a usable bed respawn.");}
            catch(TaskFailure e){throw e;}catch(Exception e){throw new TaskFailure("Bed respawn confirmation failed: "+e);}
            spawnSet=true;bot.say("TAS_BED_SPAWN: "+bedHead+"; wooden pressure plate ready.");
            eyesBefore=bot.count(LegacyItems.parse("ender_eye"));phase=3;
        }
        if(phase==3) {
            if(!preparePlatform(bot))return false;
            BlockPos missing=null;double nearest=Double.POSITIVE_INFINITY;int frames=0;
            portal=null;
            for(int x=room[0];x<=room[3];x++)for(int z=room[2];z<=room[5];z++)for(int y=room[1];y<=room[4];y++) {
                BlockPos p=new BlockPos(x,y,z);BlockState state=bot.mc.world.getBlockState(p);
                if(state.getBlock()==Block.get("end_portal"))portal=p;
                if(state.getBlock()!=Block.get("end_portal_frame"))continue;
                frames++;
                if((state.getBlock().getData(state)&4)==0 && bot.mc.player.squaredDistanceToCenter(p)<nearest) {
                    nearest=bot.mc.player.squaredDistanceToCenter(p);missing=p;
                }
            }
            if(frames!=12)throw new TaskFailure("Expected twelve loaded End portal frames in the recorded room, found "+frames);
            if(missing!=null) {
                BlockHitResult hit=bot.hit(missing);
                if(hit==null)throw new TaskFailure("Portal frame is occluded from the central platform: "+missing);
                int slot=bot.findInventory(LegacyItems.parse("ender_eye"));if(slot<0)throw new TaskFailure("Not enough Eyes of Ender.");
                bot.releaseMovement();bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),missing,hit.direction,hit.pos);
                bot.navigation.reset();waitUntil=bot.ticks+2;return false;
            }
            if(portal==null) {waitUntil=bot.ticks+2;return false;}
            eyesUsed=eyesBefore-bot.count(LegacyItems.parse("ender_eye"));entering=true;phase=4;
            bot.say("TAS_END_PORTAL_READY: inserted "+eyesUsed+" Eyes of Ender. Entering the End.");
        }
        // All twelve frames are reachable from this single block. Once the
        // real portal is active, mine the support and fall directly into it.
        bot.releaseMovement();
        if(!bot.mc.world.isAir(platform))bot.mine(platform);
        return false;
    }
    private boolean descendToRoomFloor(LegacyBot bot) {
        int floorY=room[1]+1;
        if(bot.mc.player.onGround && bot.mc.player.y<=floorY+.1) {floorExit=null;return true;}
        if(floorExit==null) {
            if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
            int startX=(int)Math.floor(bot.mc.player.x),startZ=(int)Math.floor(bot.mc.player.z);
            double best=Double.POSITIVE_INFINITY;
            // Descend the original stairs (or a supported, at-most-three-block
            // drop) before asking the full-block planner to place the door seal.
            // Starting that planner on a half-height stair can strand it.
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int length=1;length<=6;length++) {
                int x=startX+d[0]*length,z=startZ+d[1]*length;
                if(x<=room[0] || x>=room[3] || z<=room[2] || z>=room[5]
                        || !PathSearch.standing(bot.navigation,x,floorY,z))continue;
                int previousY=(int)Math.ceil(bot.mc.player.y);boolean supported=true;
                for(int i=1;i<=length && supported;i++) {
                    int sx=startX+d[0]*i,sz=startZ+d[1]*i,nextY=Integer.MIN_VALUE;
                    for(int y=previousY;y>=Math.max(floorY,previousY-3);y--) {
                        Block b=bot.mc.world.getBlockState(new BlockPos(sx,y-1,sz)).getBlock();
                        if(!(b instanceof net.minecraft.block.StairsBlock) && !bot.navigation.floor(sx,y-1,sz))continue;
                        if(bot.navigation.clear(sx,y,sz) && bot.navigation.clear(sx,y+1,sz)) {nextY=y;break;}
                    }
                    if(nextY==Integer.MIN_VALUE)supported=false;else previousY=nextY;
                }
                if(supported && previousY==floorY && length<best) {best=length;floorExit=new PathSearch.Pos(x,floorY,z);}
            }
            if(floorExit==null)throw new TaskFailure("No supported descent from portal stairs to the room floor.");
            bot.navigation.reset();
        }
        bot.releaseMovement();bot.look(floorExit.x+.5,bot.mc.player.y+bot.mc.player.getEyeHeight(),floorExit.z+.5);
        bot.key(bot.mc.options.forwardKey,Math.hypot(bot.mc.player.x-floorExit.x-.5,bot.mc.player.z-floorExit.z-.5)>.15);
        return false;
    }
    private boolean preparePlatform(LegacyBot bot) {
        if(platformStage==0) {
            if(bot.count(LegacyItems.parse("planks"))<10) {child=new AcquisitionTask(LegacyItems.parse("planks"),10,"craft");return false;}
            int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE,y=0;
            for(int x=room[0];x<=room[3];x++)for(int z=room[2];z<=room[5];z++)for(int sy=room[1];sy<=room[4];sy++)
                if(bot.mc.world.getBlockState(new BlockPos(x,sy,z)).getBlock()==Block.get("end_portal_frame")) {
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);y=sy;
                }
            if(minX>maxX)throw new TaskFailure("No portal frame found for the entry platform.");
            platform=new BlockPos((minX+maxX)/2,y+1,(minZ+maxZ)/2);
            double best=Double.POSITIVE_INFINITY;PathSearch.Pos from=bot.navigation.feet();
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int x=platform.getX()+d[0]*3,z=platform.getZ()+d[1]*3;
                for(int sy=room[1];sy<=platform.getY();sy++) {
                    if(!PathSearch.standing(bot.navigation,x,sy,z))continue;
                    boolean clear=true;for(int h=sy;h<=platform.getY()+2;h++)if(!bot.navigation.clear(x,h,z))clear=false;
                    if(!clear)continue;
                    PathSearch.Pos p=new PathSearch.Pos(x,sy,z);List<PathSearch.Pos> route=PathSearch.find(bot.navigation,from,p,3000);
                    if(!from.equals(p) && (route.isEmpty() || !route.get(route.size()-1).equals(p)))continue;
                    double cost=from.distance(p);if(cost<best) {best=cost;column=p;}
                }
            }
            if(column==null)throw new TaskFailure("No dry approach column beside the End portal.");
            int dx=Integer.signum(platform.getX()-column.x),dz=Integer.signum(platform.getZ()-column.z);
            for(int i=1;i<=3;i++)bridge.add(new BlockPos(column.x+dx*i,platform.getY(),column.z+dz*i));
            platformStage=1;
        }
        if(platformStage==1) {
            if(!bot.navigation.movePrecisely(column,.18))return false;
            // Clear the ceiling only as each jump brings it within mining reach.
            platformStage=2;child=new SurfaceAccessTask(platform.getY()+1,true).allowMasonry();return false;
        }
        if(platformStage==2) {
            if(bridgeIndex<bridge.size()) {
                BlockPos next=bridge.get(bridgeIndex++);
                int dx=Integer.signum(platform.getX()-column.x),dz=Integer.signum(platform.getZ()-column.z);
                child=new BridgeStep(next,dx,dz);return false;
            }
            platformStage=3;
        }
        if(platformStage==3) {
            if(!bot.navigation.movePrecisely(new PathSearch.Pos(platform.getX(),platform.getY()+1,platform.getZ()),.15))return false;
            platformStage=4;bridgeIndex=0;
        }
        if(platformStage==4) {
            // The bridge above a frame would block an Eye-of-Ender click.
            // Remove it after crossing, leaving only our central support.
            for(BlockPos p:bridge)if(!p.equals(platform) && bot.mc.world.getBlockState(p).getBlock()==Block.get("planks")) {bot.mine(p);return false;}
            bot.cancelMining();platformStage=5;bot.say("TAS_PORTAL_PLATFORM: standing above the portal center.");
        }
        return true;
    }
    /** A one-wide bridge needs a crouched edge stance to see the next side face. */
    private static final class BridgeStep implements LegacyTask {
        private final BlockPos target,support;
        private final int dx,dz;
        private boolean atSupport;
        private int ticks;
        BridgeStep(BlockPos target,int dx,int dz) {this.target=target;this.dx=dx;this.dz=dz;support=target.add(-dx,0,-dz);}
        public String describe() {return "extend portal walkway at "+target;}
        public boolean tick(LegacyBot bot) {
            if(bot.mc.world.getBlockState(target).getBlock()==Block.get("planks"))return true;
            if(++ticks>160)throw new TaskFailure("Portal bridge edge placement timed out at "+target);
            if(!atSupport) {
                if(!bot.navigation.movePrecisely(new PathSearch.Pos(support.getX(),support.getY()+1,support.getZ()),.15))return false;
                atSupport=true;
            }
            bot.releaseMovement();bot.key(bot.mc.options.sneakKey,true);
            double x=support.getX()+.5+dx*.72,z=support.getZ()+.5+dz*.72;
            Vec3d aim=new Vec3d(support.getX()+.5+dx*.499,support.getY()+.8,support.getZ()+.5+dz*.499);
            Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
            BlockHitResult hit=bot.mc.world.rayTrace(eye,aim);
            Direction face=dx>0?Direction.EAST:dx<0?Direction.WEST:dz>0?Direction.SOUTH:Direction.NORTH;
            if(hit!=null && hit.getBlockPos().equals(support) && hit.direction==face) {
                int slot=bot.findInventory(LegacyItems.parse("planks"));if(slot<0)throw new TaskFailure("No plank left for portal walkway.");
                bot.equip(slot);bot.look(hit.pos.x,hit.pos.y,hit.pos.z);LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.onRightClick(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack(),support,face,hit.pos);
            } else {
                bot.look(x,eye.y,z);bot.key(bot.mc.options.forwardKey,Math.hypot(x-bot.mc.player.x,z-bot.mc.player.z)>.025);
            }
            return false;
        }
        public void stop(LegacyBot bot) {bot.releaseMovement();}
    }
    private void chooseBed(LegacyBot bot) {
        double best=Double.POSITIVE_INFINITY;
        int[][] dirs={{0,1},{-1,0},{0,-1},{1,0}};
        PathSearch.Pos from=bot.navigation.feet();
        for(int x=room[0];x<=room[3];x++)for(int z=room[2];z<=room[5];z++)for(int y=room[1];y<=room[4];y++)for(int dir=0;dir<4;dir++) {
            BlockPos foot=new BlockPos(x,y,z),head=foot.add(dirs[dir][0],0,dirs[dir][1]);
            if(rejectedBeds.contains(foot) || !bedSpace(bot,foot) || !bedSpace(bot,head))continue;
            for(int sx=x-2;sx<=x+2;sx++)for(int sz=z-2;sz<=z+2;sz++) {
                if(sx==x && sz==z || sx==head.getX() && sz==head.getZ() || !PathSearch.standing(bot.navigation,sx,y,sz))continue;
                PathSearch.Pos p=new PathSearch.Pos(sx,y,sz);double cost=from.distance(p);
                if(cost>=best || bot.hitFrom(new Vec3d(sx+.5,y+1.62,sz+.5),foot.down())==null)continue;
                List<PathSearch.Pos> path=PathSearch.find(bot.navigation,from,p,2000);
                if(!from.equals(p) && (path.isEmpty() || !path.get(path.size()-1).equals(p)))continue;
                best=cost;bedFoot=foot;bedHead=head;stance=p;orientation=dir;
            }
        }
        if(bedFoot==null)throw new TaskFailure("No dry two-block bed position is reachable in the portal room.");
        bot.say("TAS_BED_POSITION: foot="+bedFoot+" head="+bedHead+" stance="+stance);
    }
    private boolean defendHostiles(LegacyBot bot) {
        net.minecraft.entity.LivingEntity nearest=null;Vec3d aim=null;double distance=3.3*3.3;
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        for(Object entity:bot.mc.world.loadedEntities)if(entity instanceof net.minecraft.entity.LivingEntity) {
            net.minecraft.entity.LivingEntity mob=(net.minecraft.entity.LivingEntity)entity;
            if(!NetherCombat.hostile(bot,mob))continue;
            for(double height:new double[]{mob.getEyeHeight(),.7,.2}) {
                Vec3d point=new Vec3d(mob.x,mob.y+height,mob.z);double d=eye.squaredDistanceTo(point);
                if(d<distance && bot.mc.world.rayTrace(eye,point)==null) {nearest=mob;aim=point;distance=d;}
            }
        }
        if(nearest==null)return false;
        bot.releaseMovement();bot.cancelMining();
        int weapon=AcquisitionTask.combatWeapon(bot);if(weapon>=0)bot.equip(weapon);
        bot.look(aim.x,aim.y,aim.z);
        if(eye.squaredDistanceTo(aim)<3.3*3.3 && bot.ticks-lastAttack>=2) {
            bot.mc.interactionManager.attackEntity(bot.mc.player,nearest);bot.mc.player.swingHand();lastAttack=bot.ticks;
        }
        return true;
    }
    private boolean bedSpace(LegacyBot bot,BlockPos p) {
        if(!bot.mc.world.isAir(p) || !bot.mc.world.isAir(p.up()) || !bot.navigation.floor(p.getX(),p.getY()-1,p.getZ()))return false;
        // Match BedItem's actual placement rule. A stair's broad collision
        // bounds alone are not sufficient support for a bed.
        if(!net.minecraft.world.World.isOpaque(bot.mc.world,p.down()))return false;
        Block below=bot.mc.world.getBlockState(p.down()).getBlock();
        if(below==Block.get("end_portal_frame") || bot.mc.world.getBlockEntity(p.down())!=null)return false;
        for(Direction side:Direction.values())if(bot.mc.world.getBlockState(p.offset(side)).getBlock().getMaterial().isFluid()
                || bot.mc.world.getBlockState(p.down().offset(side)).getBlock().getMaterial().isFluid())return false;
        return true;
    }
    public void stop(LegacyBot bot) {if(child!=null)child.stop(bot);bot.releaseMovement();bot.cancelMining();}
}
