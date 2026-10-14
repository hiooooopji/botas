package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.math.BlockPos;

/** Craft, launch, board and steer a boat using normal Survival interactions. */
final class BoatTravelTask implements LegacyTask {
    private final PathSearch.Pos bank;
    private final List<PathSearch.Pos> route;
    final int riverExit;
    private LegacyTask child;
    private BoatEntity boat;
    private int phase,ticks,next,attempts,lastAction,lastProgress,aimTicks;
    private double bestDistance=Double.MAX_VALUE;
    boolean boarded;
    private BoatTravelTask(PathSearch.Pos bank,List<PathSearch.Pos> route,int riverExit){
        this.bank=bank;this.route=route;this.riverExit=riverExit;
    }
    static boolean water(LegacyBot bot,int x,int y,int z){
        BlockPos p=new BlockPos(x,y,z);
        return bot.mc.world.isLoaded(p,false) && bot.mc.world.getBlockState(p).getBlock().getMaterial()==Material.WATER;
    }
    private static PathSearch.Terrain terrain(LegacyBot bot){
        return new PathSearch.Terrain(){
            public boolean clear(int x,int y,int z){
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(!bot.navigation.clear(x+dx,y,z+dz))return false;
                return true;
            }
            public boolean floor(int x,int y,int z){
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(!water(bot,x+dx,y,z+dz))return false;
                return true;
            }
        };
    }
    static BoatTravelTask plan(LegacyBot bot,List<PathSearch.Pos> river){
        int first=-1,last=-1,waterCount=0;
        for(int i=0;i<river.size();i++){
            PathSearch.Pos p=river.get(i);
            if(water(bot,p.x,p.y-1,p.z)){if(first<0)first=i;last=i;waterCount++;}
            else if(first>=0)break;
        }
        if(first<0 || waterCount<12)return null;
        PathSearch.Pos bank=first==0?bot.navigation.feet():river.get(first-1);
        if(!PathSearch.standing(bot.navigation,bank.x,bank.y,bank.z))return null;
        PathSearch.Terrain waterTerrain=terrain(bot);
        for(int a=first;a<=Math.min(last,first+3);a++){
            PathSearch.Pos launch=river.get(a);
            if(bank.distance(launch)>3 || !PathSearch.standing(waterTerrain,launch.x,launch.y,launch.z))continue;
            for(int b=last;b>=Math.max(a+8,last-4);b--){
                PathSearch.Pos end=river.get(b);
                if(!PathSearch.standing(waterTerrain,end.x,end.y,end.z))continue;
                List<PathSearch.Pos> path=PathSearch.findWithin(waterTerrain,launch,end,6000,96);
                if(!path.isEmpty() && path.get(path.size()-1).equals(end)){
                    path.add(0,launch);return new BoatTravelTask(bank,path,b);
                }
            }
        }
        return null;
    }
    public String describe(){return child!=null?"prepare boat -> "+child.describe():phase<2?"launch boat":phase==2?"board boat":"sail across water ("+next+"/"+route.size()+")";}
    public void stop(LegacyBot bot){if(child!=null)child.stop(bot);bot.releaseMovement();bot.cancelMining();bot.navigation.reset();}
    public boolean tick(LegacyBot bot){
        if(++ticks>2400)throw new TaskFailure("Boat crossing timed out.");
        if(child!=null){if(child.tick(bot)){child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(phase==0){
            if(bot.count(LegacyItems.parse("boat"))==0){child=new AcquisitionTask(LegacyItems.parse("boat"),1,"get");return false;}
            if(!bot.navigation.move(bank,.2))return false;
            phase=1;lastAction=-100;bot.say("Using a boat for this long water crossing.");return false;
        }
        PathSearch.Pos launch=route.get(0);
        if(phase==1){
            for(Object entity:bot.mc.world.loadedEntities)if(entity instanceof BoatEntity){
                BoatEntity candidate=(BoatEntity)entity;
                if(!candidate.removed && candidate.rider==null && candidate.squaredDistanceTo(launch.x+.5,launch.y,launch.z+.5)<9){boat=candidate;phase=2;attempts=0;return false;}
            }
            if(ticks-lastAction<20)return false;
            int slot=bot.findInventory(LegacyItems.parse("boat"));
            if(slot<0){if(++aimTicks>60)throw new TaskFailure("Used boat item but no boat appeared.");return false;}
            bot.releaseMovement();bot.equip(slot);bot.look(launch.x+.5,launch.y-.1,launch.z+.5);
            // Item use raycasts on the server using the most recently received
            // player rotation. Send the aim before sending the use packet.
            if(++aimTicks<3)return false;
            aimTicks=0;if(++attempts>4)throw new TaskFailure("Boat placement was not confirmed.");
            boolean used=bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
            bot.say("Boat launch attempt "+attempts+" at "+launch+" from "+bot.navigation.feet()+" used="+used);
            lastAction=ticks;return false;
        }
        if(phase==2){
            if(bot.mc.player.vehicle==boat){boarded=true;phase=3;lastProgress=ticks;return false;}
            if(boat.removed || bot.mc.player.squaredDistanceTo(boat)>25)throw new TaskFailure("Boat drifted out of boarding reach.");
            bot.releaseMovement();bot.look(boat.x,boat.y+.3,boat.z);
            if(ticks-lastAction>=10){
                if(++attempts>10)throw new TaskFailure("Could not board the boat.");
                bot.mc.interactionManager.interactEntity(bot.mc.player,boat);lastAction=ticks;
            }
            return false;
        }
        if(bot.mc.player.vehicle!=boat){
            if(phase==4)return true;
            throw new TaskFailure("Boat was lost during the crossing.");
        }
        if(phase==4){bot.releaseMovement();bot.key(bot.mc.options.sneakKey,true);return false;}
        PathSearch.Pos end=route.get(route.size()-1);
        double distance=Math.hypot(boat.x-end.x-.5,boat.z-end.z-.5);
        if(distance<bestDistance-.25){bestDistance=distance;lastProgress=ticks;}
        if(ticks-lastProgress>180)throw new TaskFailure("Boat is not advancing toward the shore.");
        bot.releaseMovement();
        if(distance<1.6){phase=4;bot.key(bot.mc.options.sneakKey,true);return false;}
        while(next+1<route.size() && Math.hypot(boat.x-route.get(next).x-.5,boat.z-route.get(next).z-.5)<1.2)next++;
        PathSearch.Terrain waterTerrain=terrain(bot);
        for(int i=Math.min(route.size()-1,next+6);i>next;i--)if(PathSearch.walkStraight(waterTerrain,boat.x,boat.z,route.get(i))){next=i;break;}
        PathSearch.Pos aim=route.get(next);
        if(!PathSearch.standing(waterTerrain,aim.x,aim.y,aim.z))throw new TaskFailure("Boat route became obstructed.");
        double dx=aim.x+.5-boat.x,dz=aim.z+.5-boat.z;
        double speed=Math.hypot(boat.velocityX,boat.velocityZ);
        // Aim ahead of the hull's drift, and brake with ordinary back input near shore.
        bot.look(boat.x+dx-boat.velocityX*8,bot.mc.player.y+bot.mc.player.getEyeHeight(),boat.z+dz-boat.velocityZ*8);
        bot.key(bot.mc.options.forwardKey,distance>4 || speed<.12);
        bot.key(bot.mc.options.backKey,distance<=4 && speed>.16);
        return false;
    }
}
