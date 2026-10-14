package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.world.GameMode;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;

/** Opt-in structure-first single-player run; rejected seeds are saved, never deleted. */
final class LegacyStructureRun implements LegacyTask {
    private final boolean enterNether;
    LegacyStructureRun() {this(false);}
    LegacyStructureRun(boolean enterNether) {this.enterNether=enterNether;}
    boolean enteringNether() {return enterNether && stage==3;}
    private SurfaceStructureScan scan;
    private Future<Boolean> pending;
    private LegacyTask child;
    private int stage,lootIndex,craftIndex,attempt=1,transition;
    private boolean stopped,stonePlanned;
    private final List<SurfaceStructureScan.Loot> route=new ArrayList<>();
    private final Set<net.minecraft.util.math.BlockPos> visitedChests=new HashSet<>();
    private static final String[] KIT={"iron_pickaxe","stone_pickaxe","stone_sword","stone_axe","bucket","flint_and_steel"};
    public String describe() {return "structure "+(enterNether ? "Nether run" : "kit")+" (seed attempt "+attempt+") -> "+(stage==0 ? "scan surface structures within 512 blocks" : stage==1 ? "loot iron kit" : stage==3 ? "build portal and enter Nether" : "finish kit and food")+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {stopped=true;if(pending!=null) pending.cancel(false);if(child!=null) child.stop(bot);}
    boolean transition(LegacyBot bot) {
        if(transition==0 || stopped) return false;
        if(bot.structurePaused()) return true;
        if(transition==1) {
            bot.releaseMovement();bot.cancelMining();bot.closeOwnedScreen();
            // connect(null) stops/saves the integrated server itself. Disconnecting
            // first lets it auto-stop before stopRunning's queued logout executes,
            // leaving the client waiting forever on a dead server thread in 1.8.
            bot.mc.connect(null);bot.mc.setScreen(new TitleScreen());transition=2;return true;
        }
        if(transition==2) {
            transition=3;
            long seed=new Random().nextLong();
            String name="altoclef-structure-"+System.currentTimeMillis()+"-"+attempt;
            bot.mc.startIntegratedServer(name,name,new LevelInfo(seed,GameMode.SURVIVAL,true,false,LevelGeneratorType.DEFAULT));
            return true;
        }
        if(bot.mc.world==null || bot.mc.player==null || bot.mc.getServer()==null) return true;
        bot.bindStructureWorld();scan=null;pending=null;stonePlanned=false;stage=lootIndex=craftIndex=0;route.clear();visitedChests.clear();transition=0;return false;
    }
    private void retry(LegacyBot bot,String reason) {
        bot.say(reason+" Saving this world and trying a new seed. @stop cancels retries.");
        if(child!=null) child.stop(bot);child=null;attempt++;transition=1;
    }
    static int inventoryIron(LegacyBot bot) {
        return IronKitBudget.usable(bot.count(LegacyItems.parse("iron_ingot")),
                bot.count(LegacyItems.parse("iron_pickaxe")),
                bot.count(LegacyItems.parse("bucket"))+bot.count(LegacyItems.parse("water_bucket"))+bot.count(LegacyItems.parse("lava_bucket")),
                bot.count(LegacyItems.parse("flint_and_steel")));
    }
    private int remainingStone(LegacyBot bot) {
        return (bot.count(LegacyItems.parse("stone_pickaxe"))==0 ? 3 : 0)
                +(bot.count(LegacyItems.parse("stone_sword"))==0 ? 2 : 0)
                +(bot.count(LegacyItems.parse("stone_axe"))==0 ? 3 : 0);
    }
    public boolean tick(LegacyBot bot) {
        if(stage==3 && bot.mc.player.dimension==-1) {
            if(child!=null) child.stop(bot);child=null;
            bot.say("Structure seed run complete: entered the Nether after "+attempt+" seed attempt(s).");return true;
        }
        if(LegacyOverworldTask.defend(bot)) return false;
        int edible=bot.findInventory(LegacyItems.food());
        if(edible>=0 && (bot.mc.player.getHungerManager().getFoodLevel()<18 || bot.mc.player.isUsingItem())) {
            bot.releaseMovement();bot.cancelMining();bot.equip(edible);
            if(!bot.mc.player.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
            bot.key(bot.mc.options.useKey,true);return false;
        }
        bot.key(bot.mc.options.useKey,false);
        if(child!=null) {
            if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;
        }
        if(stage==0) {
            if(inventoryIron(bot)>=10) {stage=2;return false;}
            if(scan==null) {
                scan=new SurfaceStructureScan(bot.mc.getServer().getWorld(0).getSpawnPos());
                bot.say("Scanning seed "+bot.mc.getServer().getWorld(0).getSeed()+" (attempt "+attempt+").");
            }
            if(pending==null) {
                pending=bot.mc.getServer().method_10815((java.util.concurrent.Callable<Boolean>)() -> !stopped && scan.advance(bot.mc.getServer().getWorld(0)));
                return false;
            }
            if(!pending.isDone()) return false;
            boolean finished;
            try {finished=pending.get();}catch(Exception e) {throw new TaskFailure("Structure scan failed: "+e.getMessage());}
            pending=null;if(!finished) return false;
            int iron=SurfaceStructureScan.usableIron(scan.loot,bot.count(LegacyItems.parse("iron_ingot")),
                    bot.count(LegacyItems.parse("iron_pickaxe")),
                    bot.count(LegacyItems.parse("bucket"))+bot.count(LegacyItems.parse("water_bucket"))+bot.count(LegacyItems.parse("lava_bucket")),
                    bot.count(LegacyItems.parse("flint_and_steel")));
            bot.say("Surface structure scan: "+scan.loot.size()+" useful chests, "+iron+" iron-equivalent toward the kit, including carried supplies.");
            if(iron<10) {retry(bot,"Nearby structures do not contain the required iron kit.");return false;}
            route.addAll(scan.loot);stage=1;return false;
        }
        if(stage==1) {
            if(inventoryIron(bot)>=10) {stage=2;return false;}
            while(lootIndex<route.size() && visitedChests.contains(route.get(lootIndex).pos)) lootIndex++;
            if(lootIndex>=route.size()) throw new TaskFailure("Structure loot changed; the scanned iron kit is no longer available.");
            SurfaceStructureScan.Loot loot=route.get(lootIndex);
            if(loot.temple && bot.count(LegacyItems.parse("stone_pickaxe"))==0) {
                child=new AcquisitionTask(LegacyItems.parse("stone_pickaxe"),1,"get");return false;
            }
            List<SurfaceStructureScan.Loot> group=new ArrayList<>();
            for(SurfaceStructureScan.Loot candidate:route)
                if(!visitedChests.contains(candidate.pos) && (candidate==loot || loot.temple && candidate.temple && loot.entry.equals(candidate.entry))) {
                    group.add(candidate);visitedChests.add(candidate.pos);
                }
            child=new StructureLootTask(group);lootIndex++;return false;
        }
        if(craftIndex==1 && !stonePlanned) {
            stonePlanned=true;
            int stone=remainingStone(bot);
            if(stone>0) {child=new AcquisitionTask(LegacyItems.parse("cobblestone"),stone,"get");return false;}
        }
        if(craftIndex<KIT.length) {
            String id=KIT[craftIndex++];int amount=id.equals("bucket") ? Math.max(0,2-bot.count(LegacyItems.parse("water_bucket"))-bot.count(LegacyItems.parse("lava_bucket"))) : 1;
            if(amount>0) {
                AcquisitionTask craft=new AcquisitionTask(LegacyItems.parse(id),amount,"get");
                craft.protectedCobblestone=remainingStone(bot);child=craft;
            }
            return false;
        }
        if(bot.count(LegacyItems.anyLogs())<6) {child=new AcquisitionTask(LegacyItems.anyLogs(),6,"get");return false;}
        if(bot.count(LegacyItems.food())<7) {child=new LegacyFoodTask(7);return false;}
        bot.say("Structure kit complete: iron pickaxe, stone tools, two buckets, flint and steel, six logs and seven food.");
        if(enterNether) {stage=3;child=LegacyOverworldTask.afterPreparation();return false;}
        return true;
    }
}
