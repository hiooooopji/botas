package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Disarms the temple plate before exposing and collecting all nine TNT. */
final class TempleTntTask implements LegacyTask {
    private final BlockPos entry;
    private BlockPos plate,target;
    private LegacyTask child;
    private final AcquisitionTask drops=new AcquisitionTask(LegacyItems.parse("tnt"),9,"get");
    private int idleTicks;
    TempleTntTask(BlockPos entry) {this.entry=entry;}
    public String describe() {return "disarm temple trap and collect 9 TNT"+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);drops.stop(bot);bot.cancelMining();bot.releaseMovement();}
    private boolean is(LegacyBot bot,BlockPos p,String name) {return LegacyWorld.state(bot.mc.world,p).getBlock()==Block.get(name);}
    public boolean tick(LegacyBot bot) {
        if(bot.count(LegacyItems.parse("tnt"))>=9) {bot.say("Temple trap disarmed; collected nine TNT.");return true;}
        if(child!=null) {
            if(child instanceof MovementTasks.GoTo && plate!=null
                    && Math.abs(bot.navigation.feet().x-plate.x)<=6 && Math.abs(bot.navigation.feet().z-plate.z)<=6) {
                child.stop(bot);child=null;bot.navigation.reset();return false;
            }
            if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;
        }
        if(plate==null) {
            BlockPos center=entry==null ? LegacyPos.of(bot.mc.field_3805) : entry;
            double best=Double.POSITIVE_INFINITY;
            BlockPos remainingTnt=null;double tntDistance=Double.POSITIVE_INFINITY;
            for(int x=center.x-64;x<=center.x+64;x++) for(int z=center.z-64;z<=center.z+64;z++)
                for(int y=35;y<=75;y++) {
                    BlockPos p=new BlockPos(x,y,z);
                    if(!LegacyWorld.isLoaded(bot.mc.world,p,false)) continue;
                    if(is(bot,p,"tnt")) {
                        double distance=LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p);
                        if(distance<tntDistance) {tntDistance=distance;remainingTnt=p;}
                    }
                    if(!is(bot,p,"stone_pressure_plate")) continue;
                    if(!is(bot,LegacyPos.add(p,0,-2,0),"tnt")) continue;
                    double distance=LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p);
                    if(distance<best) {best=distance;plate=p;}
                }
            // A resumed run may have already disarmed the plate. Locate the
            // remaining 3x3 TNT bed and recover its center from the intact bed.
            if(plate==null) {
                if(remainingTnt!=null) plate=LegacyPos.add(remainingTnt,0,2,0);
            }
            if(plate==null) throw new TaskFailure("No loaded temple TNT trap found nearby.");
        }
        if(is(bot,plate,"stone_pressure_plate")) {
            if(bot.hit(plate)!=null) {bot.mine(plate);return false;}
            if(Math.abs(bot.navigation.feet().x-plate.x)>7 || Math.abs(bot.navigation.feet().z-plate.z)>7) {
                child=new MovementTasks.GoTo(plate.x+4,null,plate.z);return false;
            }
            for(int[] d:new int[][]{{1,-1},{-1,-1},{1,1},{-1,1}}) {
                child=StoneAccessTask.planOre(bot,LegacyPos.add(plate,d[0],0,d[1]),"temple trap access");
                if(child!=null) return false;
            }
            throw new TaskFailure("Cannot safely reach the temple pressure plate.");
        }
        // Never mine TNT while any plate in this chamber remains armed.
        for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++) {
            BlockPos p=LegacyPos.add(plate,dx,0,dz);
            if(is(bot,p,"stone_pressure_plate")) {plate=p;return false;}
        }
        if(drops.pickup(bot)) {target=null;return false;}
        if(target!=null && !is(bot,target,"tnt")) {target=null;bot.cancelMining();}
        if(target==null) {
            double best=Double.POSITIVE_INFINITY;
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                BlockPos p=LegacyPos.add(plate,dx,-2,dz);
                if(!is(bot,p,"tnt")) continue;
                BlockPos roof=LegacyPos.up(p);
                if(roof.equals(LegacyPos.down(LegacyPos.of(bot.mc.field_3805)))) continue;
                double score=LegacyPos.squaredDistanceToCenter(bot.mc.field_3805,p);
                if(bot.hit(p)!=null) score-=1000;
                if(score<best) {best=score;target=p;}
            }
            if(target==null) {
                if(++idleTicks>300) throw new TaskFailure("Could not collect every temple TNT drop; have "+bot.count(LegacyItems.parse("tnt")));
                bot.releaseMovement();return false;
            }
        }
        BlockPos roof=LegacyPos.up(target);
        if(!LegacyWorld.isAir(bot.mc.world,roof)) {bot.mine(roof);return false;}
        bot.mine(target);return false;
    }
}
