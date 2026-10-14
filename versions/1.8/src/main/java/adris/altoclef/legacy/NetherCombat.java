package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.projectile.*;
import net.minecraft.util.math.*;

/** Interrupts Nether travel only for immediate defence or reachable combat. */
final class NetherCombat {
    private int nextApproach;
    private UUID opponent;
    private PathSearch.Pos approach;
    private Set<Integer> angryPigmen=Collections.emptySet();
    private java.util.concurrent.Future<Set<Integer>> angerSnapshot;
    private int nextAngerScan;
    static boolean hostile(LegacyBot bot,LivingEntity e) {
        if(!(e instanceof HostileEntity) && !(e instanceof GhastEntity) || !e.isAlive() || e instanceof EndermanEntity) return false;
        if(e instanceof ZombiePigmanEntity) return ((ZombiePigmanEntity)e).isAngry()
                || ((ZombiePigmanEntity)e).getTarget()==bot.mc.player || bot.mc.player.getAttacker()==e;
        return true;
    }
    boolean tick(LegacyBot bot) {return tick(bot,true);}
    boolean tick(LegacyBot bot,boolean pursue) {
        if(angerSnapshot!=null && angerSnapshot.isDone()) {
            try {angryPigmen=angerSnapshot.get();} catch(Exception ignored) {angryPigmen=Collections.emptySet();}
            angerSnapshot=null;
        }
        if(angerSnapshot==null && bot.mc.getServer()!=null && bot.ticks>=nextAngerScan) {
            nextAngerScan=bot.ticks+20;final int dimension=bot.mc.player.dimension;
            angerSnapshot=bot.mc.getServer().method_10815((java.util.concurrent.Callable<Set<Integer>>)()-> {
                Set<Integer> result=new HashSet<>();
                for(Object e:bot.mc.getServer().getWorld(dimension).loadedEntities)
                    if(e instanceof ZombiePigmanEntity && ((ZombiePigmanEntity)e).isAngry()) result.add(((ZombiePigmanEntity)e).getEntityId());
                return result;
            });
        }
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        LivingEntity threat=null;Vec3d aim=null;double best=32*32;
        for(Object o:bot.mc.world.loadedEntities) {
            if(o instanceof FireballEntity && !((Entity)o).removed) {
                FireballEntity f=(FireballEntity)o;
                double dx=bot.mc.player.x-f.x,dy=eye.y-f.y,dz=bot.mc.player.z-f.z;
                if(dx*f.velocityX+dy*f.velocityY+dz*f.velocityZ<=0 || bot.mc.player.squaredDistanceTo(f)>16) continue;
                if(bot.mc.world.rayTrace(eye,new Vec3d(f.x,f.y+.25,f.z))!=null) continue;
                bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.sneakKey,true);
                bot.look(f.x,f.y+.25,f.z);LegacyFluidTask.syncAim(bot);
                bot.mc.interactionManager.attackEntity(bot.mc.player,f);bot.mc.player.swingHand();return true;
            }
            if(!(o instanceof LivingEntity) || !hostile(bot,(LivingEntity)o)
                    && !(o instanceof ZombiePigmanEntity && ((LivingEntity)o).isAlive() && angryPigmen.contains(((Entity)o).getEntityId()))) continue;
            LivingEntity e=(LivingEntity)o;double distance=bot.mc.player.squaredDistanceTo(e);
            if(distance>=best) continue;
            Vec3d visible=null;
            for(double height:new double[]{e.getEyeHeight(),.7,.3}) {
                Vec3d point=new Vec3d(e.x,e.y+height,e.z);
                if(bot.mc.world.rayTrace(eye,point)==null) {visible=point;break;}
            }
            if(visible==null) continue;
            threat=e;best=distance;aim=visible;
        }
        if(threat==null) {opponent=null;approach=null;return false;}
        if(!threat.getUuid().equals(opponent)) {opponent=threat.getUuid();approach=null;}
        int weapon=-1;
        for(String sword:new String[]{"diamond_sword","iron_sword","stone_sword","wooden_sword","golden_sword"}) {
            weapon=bot.findInventory(LegacyItems.usableTool(sword));if(weapon>=0) break;
        }
        if(weapon<0) throw new TaskFailure("Carry a usable sword for Nether combat.");
        double reach=Math.pow(eye.x-aim.x,2)+Math.pow(eye.y-aim.y,2)+Math.pow(eye.z-aim.z,2);
        if(best<=9 || best<=16 && reach<=9) {
            bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.useKey,false);
            if(weapon>=0) bot.equip(weapon);
            bot.look(aim.x,aim.y,aim.z);LegacyFluidTask.syncAim(bot);
            if(bot.ticks%8==0) {bot.mc.interactionManager.attackEntity(bot.mc.player,threat);bot.mc.player.swingHand();}
            return true;
        }
        // Never chase airborne ghasts across a ledge. Return their fireballs.
        if(!pursue || threat instanceof GhastEntity || best>144 || !bot.mc.player.onGround) return false;
        if(bot.ticks>=nextApproach) {
            nextApproach=bot.ticks+20;approach=null;
            PathSearch.Pos from=bot.navigation.feet();
            int x=(int)Math.floor(threat.x),y=(int)Math.floor(threat.y),z=(int)Math.floor(threat.z);
            outer: for(int dy:new int[]{0,-1,1,-2}) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                PathSearch.Pos p=new PathSearch.Pos(x+dx,y+dy,z+dz);
                if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z)) continue;
                List<PathSearch.Pos> path=PathSearch.find(bot.navigation,from,p,1200);
                if(from.equals(p) || !path.isEmpty() && path.get(path.size()-1).equals(p)) {approach=p;break outer;}
            }
        }
        if(approach!=null) {
            if(weapon>=0) bot.equip(weapon);
            try {bot.navigation.move(approach,.3);return true;}
            catch(TaskFailure blocked) {approach=null;bot.navigation.reset();}
        }
        return false;
    }
}
