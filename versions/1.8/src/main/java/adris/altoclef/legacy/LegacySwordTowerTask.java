package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.*;

/** Survival sword-tower variant: shear leaves, build shelter, cycle spawns, bait and melee Endermen. */
final class LegacySwordTowerTask implements LegacyTask {
    private final int pearls;
    private LegacyTask child;
    private BlockPos origin;
    private final List<PathSearch.Pos> blueprint=SwordTowerPlan.shelter();
    private final ArrayDeque<int[]> sites=new ArrayDeque<>();
    private int stage,buildIndex,ticks,waitUntil,idleSince,targetSince,baitTicks,nextTargetScan;
    private int cycles,attacks,kills;
    private boolean sitesQueued;
    private EndermanEntity target;
    private final Map<UUID,Integer> rejected=new HashMap<>();
    private AcquisitionTask drops=new AcquisitionTask(LegacyItems.parse("ender_pearl"),1,"get");
    private final NetherCombat defence=new NetherCombat();
    private String status="find flat, open surface";
    LegacySwordTowerTask(int pearls) {this.pearls=pearls;}
    BlockPos origin() {return origin;}
    boolean readyForCombat() {return stage==8 && child==null;}
    boolean atResetHeight() {return stage==5 && child==null;}
    int completedCycles() {return cycles;}
    int swordAttacks() {return attacks;}
    int kills() {return kills;}
    public String describe() {return "sword tower -> "+status+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();bot.cancelMining();bot.key(bot.mc.options.useKey,false);bot.navigation.reset();}
    private BlockPos at(int x,int y,int z) {return origin.add(x,y,z);}
    private void go(BlockPos p) {child=new MovementTasks.GoTo(p.getX(),p.getY(),p.getZ());}
    private int sword(LegacyBot bot) {
        for(String id:new String[]{"diamond_sword","iron_sword","stone_sword","wooden_sword"}) {
            int slot=bot.findInventory(LegacyItems.usableTool(id));if(slot>=0) return slot;
        }
        return -1;
    }
    private int shearDurability(LegacyBot bot) {
        int remaining=0;
        for(ItemStack s:bot.mc.player.inventory.main) if(LegacyItems.usableTool("shears").matches(s)) remaining+=s.getMaxDamage()-s.getDamage()-1;
        return remaining;
    }
    private boolean supplies(LegacyBot bot,int leaves,int wear) {
        if(sword(bot)<0) {child=new AcquisitionTask(LegacyItems.usableTool("stone_sword"),1,"get");return false;}
        if(shearDurability(bot)<wear) {
            child=new AcquisitionTask(LegacyItems.usableTool("shears"),bot.count(LegacyItems.usableTool("shears"))+1,"get");return false;
        }
        if(bot.count(LegacyItems.anyLeaves())<leaves) {child=new AcquisitionTask(LegacyItems.anyLeaves(),leaves,"get");return false;}
        return true;
    }
    private boolean flatSite(LegacyBot bot,BlockPos p) {
        if(p.getY()<3 || p.getY()+SwordTowerPlan.RESET_HEIGHT>253) return false;
        for(int x=-3;x<=5;x++) for(int z=-3;z<=3;z++) {
            if(!bot.navigation.floor(p.getX()+x,p.getY()-1,p.getZ()+z)) return false;
            for(int y=0;y<5;y++) if(!bot.navigation.clear(p.getX()+x,p.getY()+y,p.getZ()+z)) return false;
        }
        for(int y=3;y<=SwordTowerPlan.RESET_HEIGHT+1;y++) if(!bot.navigation.clear(p.getX()+2,p.getY()+y,p.getZ()+2)) return false;
        return true;
    }
    private void findSite(LegacyBot bot) {
        if(!sitesQueued) {
            sitesQueued=true;PathSearch.Pos feet=bot.navigation.feet();
            for(int r=0;r<=32;r++) for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++)
                if(Math.max(Math.abs(dx),Math.abs(dz))==r) sites.add(new int[]{feet.x+dx,feet.z+dz});
        }
        for(int i=0;i<12 && !sites.isEmpty();i++) {
            int[] s=sites.remove();int y=SurfaceAccessTask.terrainSurface(bot,s[0],s[1]);
            BlockPos p=new BlockPos(s[0],y,s[1]);
            if(!flatSite(bot,p)) continue;
            PathSearch.Pos feet=bot.navigation.feet(),goal=new PathSearch.Pos(p.getX(),y,p.getZ());
            List<PathSearch.Pos> path=PathSearch.find(bot.navigation,feet,goal,4000);
            if(!feet.equals(goal) && (path.isEmpty() || !path.get(path.size()-1).equals(goal))) continue;
            origin=p;bot.say("Sword tower site: "+origin+"; leaf budget "+SwordTowerPlan.leavesRequired()+", reset height "+(y+SwordTowerPlan.RESET_HEIGHT)+".");
            stage=1;return;
        }
        if(sites.isEmpty()) throw new TaskFailure("No reachable flat tower site within 32 blocks. Move to open plains or desert and retry @tower.");
    }
    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension!=0) throw new TaskFailure("Build the sword tower in the Overworld.");
        if(++ticks>72000) throw new TaskFailure("Sword tower timed out before gathering the pearl target.");
        if(bot.mc.player.getHungerManager().getFoodLevel()<18 || bot.mc.player.isUsingItem()) {
            int food=bot.findInventory(LegacyItems.food());
            if(food>=0 && bot.mc.player.onGround) {
                bot.releaseMovement();bot.cancelMining();bot.equip(food);
                if(!bot.mc.player.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                bot.key(bot.mc.options.useKey,true);return false;
            }
        }
        bot.key(bot.mc.options.useKey,false);
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;}return false;}
        if(stage==0) {findSite(bot);return false;}
        if(stage==1) {
            status="gather leaves with shears";
            int remainingHarvest=Math.max(0,SwordTowerPlan.leavesRequired()-bot.count(LegacyItems.anyLeaves()));
            if(!supplies(bot,SwordTowerPlan.leavesRequired(),remainingHarvest+SwordTowerPlan.PILLAR_BLOCKS+8)) return false;
            go(origin);stage=2;return false;
        }
        if(stage==2) {
            status="build shelter "+buildIndex+"/"+blueprint.size();
            if(buildIndex<blueprint.size()) {
                PathSearch.Pos p=blueprint.get(buildIndex++);
                int slot=bot.findInventory(LegacyItems.anyLeaves());
                if(slot<0) throw new TaskFailure("Tower leaf budget exhausted before shelter completion.");
                child=new LegacyPlaceTask(at(p.x,p.y,p.z),LegacyItems.id(bot.mc.player.inventory.main[slot].getItem()));return false;
            }
            stage=3;return false;
        }
        if(stage==3) {
            status="prepare spawn-reset climb";
            if(!supplies(bot,SwordTowerPlan.PILLAR_BLOCKS+8,SwordTowerPlan.PILLAR_BLOCKS+8)) return false;
            go(at(2,3,2));stage=4;return false;
        }
        if(stage==4) {
            status="build reset pillar";
            child=new SurfaceAccessTask(origin.getY()+SwordTowerPlan.RESET_HEIGHT,true);
            stage=5;waitUntil=0;return false;
        }
        if(stage==5) {
            status="wait above mob despawn range";bot.releaseMovement();
            if(waitUntil==0) {waitUntil=bot.ticks+60;bot.say("Sword tower reset height reached. Returning by shearing the pillar.");}
            if(bot.ticks>=waitUntil) {stage=6;waitUntil=0;}
            return false;
        }
        if(stage==6) {
            status="shear pillar downward and recover blocks";
            if(!bot.mc.player.onGround) {bot.releaseMovement();return false;}
            PathSearch.Pos feet=bot.navigation.feet();
            if(feet.x!=origin.getX()+2 || feet.z!=origin.getZ()+2) throw new TaskFailure("Player moved off the reset pillar; stopped descending.");
            if(feet.y<=origin.getY()+3) {bot.cancelMining();cycles++;go(origin);stage=7;return false;}
            BlockPos below=new BlockPos(feet.x,feet.y-1,feet.z);
            if(!bot.navigation.floor(feet.x,feet.y-2,feet.z)) throw new TaskFailure("Missing support below reset pillar; refusing a long fall.");
            if(bot.mc.world.getBlockState(below).getBlock()!=Block.get("leaves") && bot.mc.world.getBlockState(below).getBlock()!=Block.get("leaves2"))
                throw new TaskFailure("Reset pillar changed; refusing to mine an unrelated block.");
            if(bot.findInventory(LegacyItems.usableTool("shears"))<0) throw new TaskFailure("No usable shears left for pillar recovery.");
            bot.mine(below);return false;
        }
        if(stage==7) {idleSince=bot.ticks;stage=8;drops=new AcquisitionTask(LegacyItems.parse("ender_pearl"),pearls,"get");return false;}
        return hunt(bot);
    }
    private boolean hunt(LegacyBot bot) {
        int count=bot.count(LegacyItems.parse("ender_pearl"));
        status="bait Endermen / sword combat; pearls "+count+"/"+pearls;
        if(count>=pearls) {bot.releaseMovement();bot.cancelMining();bot.say("Sword tower collected "+count+" ender pearls.");return true;}
        if(target!=null && !target.isAlive()) {kills++;target=null;idleSince=bot.ticks;}
        if(defence.tick(bot,false)) return false;
        if(target==null && drops.pickup(bot)) {idleSince=bot.ticks;return false;}
        if(target!=null && (target.removed || bot.mc.player.squaredDistanceTo(target)>64*64 || bot.ticks-targetSince>600)) {
            rejected.put(target.getUuid(),bot.ticks+200);target=null;
        }
        if(target==null && bot.ticks>=nextTargetScan) {
            nextTargetScan=bot.ticks+20;double best=64*64;
            Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
            for(Object o:bot.mc.world.loadedEntities) if(o instanceof EndermanEntity) {
                EndermanEntity e=(EndermanEntity)o;double d=bot.mc.player.squaredDistanceTo(e);
                if(!e.isAlive() || e.removed || d>=best || rejected.getOrDefault(e.getUuid(),0)>bot.ticks) continue;
                if(bot.mc.world.rayTrace(eye,new Vec3d(e.x,e.y+e.getEyeHeight(),e.z))!=null) continue;
                target=e;best=d;targetSince=bot.ticks;baitTicks=0;
            }
        }
        if(target==null) {
            // Stay under the roof between hunts. Production never changes time or spawns mobs.
            if(bot.navigation.move(new PathSearch.Pos(origin.getX(),origin.getY(),origin.getZ()),.15)) {
                bot.releaseMovement();
                long time=bot.mc.world.getTimeOfDay()%24000;
                if(time<13000 || time>23000) {status="wait for night under shelter";idleSince=bot.ticks;}
                else if(bot.ticks-idleSince>900) {stage=3;idleSince=bot.ticks;}
            }
            return false;
        }
        idleSince=bot.ticks;
        double dx=target.x-(origin.getX()+.5),dz=target.z-(origin.getZ()+.5);
        int x=Math.abs(dx)>=Math.abs(dz) ? (dx>0 ? 1 : -1) : 0;
        int z=Math.abs(dz)>Math.abs(dx) ? (dz>0 ? 1 : -1) : 0;
        PathSearch.Pos stance=new PathSearch.Pos(origin.getX()+x,origin.getY(),origin.getZ()+z);
        if(!bot.navigation.move(stance,.12)) return false;
        bot.releaseMovement();int weapon=sword(bot);
        if(weapon<0) {child=new AcquisitionTask(LegacyItems.usableTool("stone_sword"),1,"get");return false;}
        bot.equip(weapon);
        Vec3d eye=new Vec3d(bot.mc.player.x,bot.mc.player.y+bot.mc.player.getEyeHeight(),bot.mc.player.z);
        Vec3d aim=new Vec3d(target.x,target.y+.5,target.z);
        double reach=Math.pow(eye.x-aim.x,2)+Math.pow(eye.y-aim.y,2)+Math.pow(eye.z-aim.z,2);
        if(reach<=9 && bot.mc.world.rayTrace(eye,aim)==null) {
            bot.look(aim.x,aim.y,aim.z);LegacyFluidTask.syncAim(bot);
            if(bot.ticks%10==0) {bot.mc.interactionManager.attackEntity(bot.mc.player,target);bot.mc.player.swingHand();attacks++;}
        } else if(baitTicks++<15) {
            bot.look(target.x,target.y+target.getEyeHeight(),target.z);LegacyFluidTask.syncAim(bot);
        } else {
            // Looking away lets an angry Enderman walk to the shelter; holding
            // eye contact continuously freezes it outside sword reach in 1.8.
            bot.look(target.x,target.y+.2,target.z);
            if(baitTicks>120) baitTicks=0;
        }
        return false;
    }
}
