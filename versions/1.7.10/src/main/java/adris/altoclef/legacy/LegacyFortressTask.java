package adris.altoclef.legacy;

import java.util.concurrent.Future;
import net.minecraft.util.math.BlockPos;

final class LegacyFortressTask implements LegacyTask {
    private NetherFortressScan scan;
    private Future<Boolean> pending;
    private LegacyTask travel;
    private LegacyTask exitPortal;
    private final NetherCombat combat=new NetherCombat();
    private final NetherLavaEscape lavaEscape=new NetherLavaEscape();
    private int previousDistance=-1,ticks;
    private BlockPos destination;
    private String interruption;
    boolean arrived;
    private LegacyBlazeTask hunt;
    private NetherReturnTask returning;
    private BlockPos entryPortal;
    private boolean rememberedPortal;
    private LegacySwordTowerTask tower;
    private boolean buildTower=true;
    boolean returningToOverworld() {return returning!=null;}
    boolean rodsReady;
    LegacyFortressTask() {}
    LegacyFortressTask(boolean buildTower) {this.buildTower=buildTower;}
    LegacyFortressTask(BlockPos knownFortress) {destination=knownFortress;arrived=true;}
    public String describe() {return "fortress -> "+(tower!=null ? tower.describe() : interruption!=null ? interruption : returning!=null ? returning.describe() : arrived ? (hunt==null ? "find blaze spawner" : hunt.describe()) : travel==null ? "scan within 32 chunks: "+(scan==null ? "starting" : scan.progress) : travel.describe());}
    public void stop(LegacyBot bot) {
        if(tower!=null) tower.stop(bot);
        if(travel!=null) travel.stop(bot);
        if(hunt!=null) hunt.stop(bot);
        if(returning!=null) returning.stop(bot);
        if(exitPortal!=null) exitPortal.stop(bot);
        if(pending!=null) pending.cancel(false);
        if(previousDistance>=0) {bot.mc.options.viewDistance=previousDistance;previousDistance=-1;}
        bot.releaseMovement();bot.cancelMining();
    }
    public boolean tick(LegacyBot bot) {
        if(tower!=null) return tower.tick(bot);
        if(returning!=null && bot.mc.field_3805.dimension==0) {
            bot.say("Returned to the Overworld with "+bot.count(LegacyItems.parse("blaze_rod"))+" blaze rods.");
            if(!buildTower) return true;
            returning.stop(bot);
            if(previousDistance>=0) {bot.mc.options.viewDistance=previousDistance;previousDistance=-1;}
            tower=new LegacySwordTowerTask(12);return false;
        }
        if(bot.mc.field_3805.dimension!=-1) throw new TaskFailure("Enter the Nether before using @fortress.");
        if(bot.mc.getServer()==null) throw new TaskFailure("Fortress structure scanning currently requires single-player.");
        if(++ticks>48000 && !arrived) throw new TaskFailure("Fortress run exceeded its time limit.");
        interruption=null;
        if(!rememberedPortal) {
            rememberedPortal=true;
            entryPortal=bot.nearestBlock(state->state.getBlock()==net.minecraft.block.Block.get("portal"),16);
            if(entryPortal!=null) while(LegacyWorld.state(bot.mc.world,LegacyPos.down(entryPortal)).getBlock()==net.minecraft.block.Block.get("portal")) entryPortal=LegacyPos.down(entryPortal);
        }
        if(!rodsReady && bot.count(LegacyItems.parse("blaze_rod"))>=7) {
            rodsReady=true;if(hunt!=null) hunt.stop(bot);if(travel!=null) {travel.stop(bot);travel=null;}
            returning=new NetherReturnTask(entryPortal);
            bot.say("Collected seven blaze rods. Returning to the Overworld.");
        }
        // Once inside the return portal, allow its contact timer to finish.
        if(returning!=null && LegacyWorld.state(bot.mc.world,LegacyPos.of(bot.mc.field_3805)).getBlock()==net.minecraft.block.Block.get("portal")) return returning.tick(bot);
        if((returning==null || LegacyWorld.state(bot.mc.world,LegacyPos.of(bot.mc.field_3805)).getBlock().getMaterial()==net.minecraft.block.material.Material.LAVA) && lavaEscape.tick(bot)) {interruption=lavaEscape.describe();return false;}
        if(combat.tick(bot,returning==null)) {interruption="combat defence";return false;}
        if(bot.mc.field_3805.getHungerManager().getFoodLevel()<18 || bot.mc.field_3805.isUsingItem()) {
            int food=bot.findInventory(LegacyItems.food());
            if(food>=0) {
                bot.releaseMovement();bot.cancelMining();bot.equip(food);
                if(!bot.mc.field_3805.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.field_3805,bot.mc.world,bot.mc.field_3805.inventory.getMainHandStack());
                bot.key(bot.mc.options.useKey,true);return false;
            }
        }
        bot.key(bot.mc.options.useKey,false);
        if(returning!=null) return returning.tick(bot);
        if(arrived) {
            if(hunt==null) hunt=new LegacyBlazeTask(destination);
            hunt.tick(bot);
            return false;
        }
        if(exitPortal!=null) {
            if(exitPortal.tick(bot)) {exitPortal.stop(bot);exitPortal=null;}
            return false;
        }
        if(scan==null && LegacyWorld.state(bot.mc.world,LegacyPos.of(bot.mc.field_3805)).getBlock()==net.minecraft.block.Block.get("portal")) {
            PathSearch.Pos feet=bot.navigation.feet();
            for(int radius=1;radius<=6;radius++) for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                PathSearch.Pos p=new PathSearch.Pos(feet.x+dx,feet.y,feet.z+dz);
                if(!PathSearch.standing(bot.navigation,p.x,p.y,p.z)
                        || LegacyWorld.state(bot.mc.world,new BlockPos(p.x,p.y,p.z)).getBlock()==net.minecraft.block.Block.get("portal")) continue;
                java.util.List<PathSearch.Pos> path=PathSearch.find(bot.navigation,feet,p,1000);
                if(!path.isEmpty() && path.get(path.size()-1).equals(p)) {exitPortal=new MovementTasks.GoTo(p.x,p.y,p.z);return false;}
            }
            throw new TaskFailure("No safe exit from the arrival portal.");
        }
        if(travel!=null) {
            if(travel.tick(bot)) {
                travel.stop(bot);arrived=true;
                bot.say("Reached Nether fortress at "+destination+". Finding a blaze spawner next.");
            }
            return false;
        }
        bot.releaseMovement();
        if(scan==null) {
            previousDistance=bot.mc.options.viewDistance;bot.mc.options.viewDistance=32;
            scan=new NetherFortressScan(LegacyPos.of(bot.mc.field_3805));
            bot.say("Scanning Nether fortresses within 32 chunks (512 blocks).");
        }
        if(pending==null) {pending=LegacyServerTasks.submit(bot.mc.getServer(),(java.util.concurrent.Callable<Boolean>)()->scan.advance(bot.mc.getServer().getWorld(-1)));return false;}
        if(!pending.isDone()) return false;
        boolean done;
        try {done=pending.get();} catch(Exception e) {throw new TaskFailure("Fortress scan failed: "+e.getMessage());}
        pending=null;
        if(!done) return false;
        destination=scan.result();
        bot.mc.options.viewDistance=Math.min(previousDistance,12);
        if(destination==null) throw new TaskFailure("No fortress found within 32 chunks. Travel farther and retry @fortress.");
        bot.say("Fortress coordinates: "+destination.x+" "+destination.y+" "+destination.z+". Planning travel and bridge supplies.");
        travel=new NetherTravelTask(destination);return false;
    }
}
