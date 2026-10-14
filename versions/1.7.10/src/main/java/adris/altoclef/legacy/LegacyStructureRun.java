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
    private final boolean templeOnly;
    LegacyStructureRun() {this(false);}
    LegacyStructureRun(boolean enterNether) {this(enterNether,false);}
    LegacyStructureRun(boolean enterNether,boolean templeOnly) {this.enterNether=enterNether;this.templeOnly=templeOnly;}
    boolean enteringNether() {return enterNether && stage==3;}
    private SurfaceStructureScan scan;
    private Future<Boolean> pending;
    private LegacyTask child;
    private int stage,lootIndex,craftIndex,attempt=1,transition;
    private boolean stopped,stonePlanned;
    private net.minecraft.util.math.BlockPos templeEntry;
    private boolean woolStarted;
    private boolean tntStarted;
    private boolean starterBoat,initialLogs,pickaxeChosen;
    private String kitPickaxe="iron_pickaxe";
    private final List<SurfaceStructureScan.Loot> route=new ArrayList<>();
    private final Set<net.minecraft.util.math.BlockPos> visitedChests=new HashSet<>();
    private static final String[] KIT={"iron_pickaxe","stone_pickaxe","stone_sword","stone_axe","stone_shovel","bucket","flint_and_steel","shears"};
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
        if(bot.mc.world==null || bot.mc.field_3805==null || bot.mc.getServer()==null) return true;
        bot.bindStructureWorld();scan=null;pending=null;stonePlanned=starterBoat=false;stage=lootIndex=craftIndex=0;pickaxeChosen=false;kitPickaxe="iron_pickaxe";initialLogs=false;route.clear();visitedChests.clear();transition=0;return false;
    }
    private void retry(LegacyBot bot,String reason) {
        if(bot.glitchless) throw new TaskFailure(reason+" Staying on the chosen seed.");
        bot.say(reason+" Saving this world and trying a new seed. @stop cancels retries.");
        if(child!=null) child.stop(bot);child=null;attempt++;transition=1;
    }
    static int inventoryIron(LegacyBot bot) {
        return IronKitBudget.oneBucketWithShears(bot.count(LegacyItems.parse("iron_ingot")),
                bot.count(LegacyItems.parse("iron_pickaxe")),
                bot.count(LegacyItems.parse("bucket"))+bot.count(LegacyItems.parse("water_bucket"))+bot.count(LegacyItems.parse("lava_bucket")),
                bot.count(LegacyItems.parse("flint_and_steel")),bot.count(LegacyItems.parse("shears")));
    }
    private int remainingStone(LegacyBot bot) {
        return (bot.count(LegacyItems.parse("stone_pickaxe"))==0 ? 3 : 0)
                +(bot.count(LegacyItems.parse("stone_sword"))==0 ? 2 : 0)
                +(bot.count(LegacyItems.parse("stone_axe"))==0 ? 3 : 0)
                +(bot.count(LegacyItems.parse("stone_shovel"))==0 ? 1 : 0);
    }
    public boolean tick(LegacyBot bot) {
        if(stage==3 && bot.mc.field_3805.dimension==-1) {
            if(child!=null) child.stop(bot);child=null;
            bot.say("Structure seed run complete: entered the Nether after "+attempt+" seed attempt(s).");return true;
        }
        if(LegacyOverworldTask.defend(bot)) return false;
        int edible=bot.findInventory(LegacyItems.food());
        if(edible>=0 && (bot.mc.field_3805.getHungerManager().getFoodLevel()<18 || bot.mc.field_3805.isUsingItem())) {
            bot.releaseMovement();bot.cancelMining();bot.equip(edible);
            if(!bot.mc.field_3805.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.field_3805,bot.mc.world,bot.mc.field_3805.inventory.getMainHandStack());
            bot.key(bot.mc.options.useKey,true);return false;
        }
        bot.key(bot.mc.options.useKey,false);
        if(child!=null) {
            if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;
        }
        if(stage==0) {
            // Resume a disarmed, already looted temple without relying on its now-empty chests.
            if(bot.glitchless && bot.count(LegacyItems.parse("tnt"))>=9
                    && bot.count(LegacyItems.usableTool("shears"))>0) {
                net.minecraft.util.math.BlockPos wool=bot.nearestBlock(s->s.getBlock()==net.minecraft.block.Block.get("wool"),32,
                        java.util.Collections.emptySet(),p->p.y>=60);
                if(wool!=null) {
                    templeEntry=new net.minecraft.util.math.BlockPos(wool.x,Math.max(70,wool.y),wool.z);
                    stage=2;tntStarted=true;bot.say("Resuming temple wool and kit preparation from the saved run.");return false;
                }
            }

            if(!starterBoat) {
                starterBoat=true;
                if(!bot.glitchless && inventoryIron(bot)<9) {child=new AcquisitionTask(LegacyItems.parse("boat"),1,"get");return false;}
            }
            if(inventoryIron(bot)>=9) {stage=2;return false;}
            if(scan==null) {
                scan=new SurfaceStructureScan(bot.mc.getServer().getWorld(0).getWorldSpawnPos());
                bot.say("Scanning seed "+bot.mc.getServer().getWorld(0).getSeed()+" (attempt "+attempt+").");
            }
            if(pending==null) {
                pending=LegacyServerTasks.submit(bot.mc.getServer(),(java.util.concurrent.Callable<Boolean>)() -> !stopped && scan.advance(bot.mc.getServer().getWorld(0)));
                return false;
            }
            if(!pending.isDone()) return false;
            boolean finished;
            try {finished=pending.get();}catch(Exception e) {throw new TaskFailure("Structure scan failed: "+e.getMessage());}
            pending=null;if(!finished) return false;
            if(templeOnly) {
                Map<String,List<SurfaceStructureScan.Loot>> temples=new LinkedHashMap<>();
                for(SurfaceStructureScan.Loot l:scan.loot) if(l.temple)
                    temples.computeIfAbsent(l.entry.x+","+l.entry.z,k->new ArrayList<>()).add(l);
                scan.loot.clear();
                for(List<SurfaceStructureScan.Loot> group:temples.values()) {
                    int rawIron=0;for(SurfaceStructureScan.Loot l:group) rawIron+=l.iron;
                    if(rawIron>=9 || bot.glitchless) {scan.loot.addAll(group);break;}
                }
                if(scan.loot.isEmpty()) {
                    if(bot.glitchless) {bot.say("No useful temple found nearby; continuing ordinary preparation on the same seed.");stage=3;child=new LegacyOverworldTask(false);return false;}
                    throw new TaskFailure("No nearby desert temple with nine iron ingots in this seed.");
                }
            }
            int iron=SurfaceStructureScan.usableIron(scan.loot,bot.count(LegacyItems.parse("iron_ingot")),
                    bot.count(LegacyItems.parse("iron_pickaxe")),
                    bot.count(LegacyItems.parse("bucket"))+bot.count(LegacyItems.parse("water_bucket"))+bot.count(LegacyItems.parse("lava_bucket")),
                    bot.count(LegacyItems.parse("flint_and_steel")));
            bot.say("Surface structure scan: "+scan.loot.size()+" useful chests, "+iron+" iron-equivalent toward the kit, including carried supplies.");
            if(!bot.glitchless && iron+2*Math.min(1,bot.count(LegacyItems.parse("shears")))<9) {retry(bot,"Nearby structures do not contain the required iron kit.");return false;}
            route.addAll(scan.loot);stage=1;return false;
        }
        if(stage==1) {
            if(inventoryIron(bot)>=9) {
                bot.say("Structure loot collected: "+bot.count(LegacyItems.parse("iron_ingot"))+" iron ingots.");
                stage=2;return false;
            }
            while(lootIndex<route.size() && visitedChests.contains(route.get(lootIndex).pos)) lootIndex++;
            if(lootIndex>=route.size()) {
                if(bot.glitchless) {stage=2;bot.say("Temple looted; gathering any remaining kit materials on this seed.");return false;}
                throw new TaskFailure("Structure loot changed; the scanned iron kit is no longer available.");
            }
            SurfaceStructureScan.Loot loot=route.get(lootIndex);
            if(loot.temple) templeEntry=loot.entry;
            if(loot.temple && bot.count(LegacyItems.parse("stone_pickaxe"))==0) {
                child=new AcquisitionTask(LegacyItems.parse("stone_pickaxe"),1,"get");return false;
            }
            if(loot.temple && bot.count(LegacyItems.usableTool("stone_axe"))==0) {
                child=new AcquisitionTask(LegacyItems.usableTool("stone_axe"),1,"get");return false;
            }
            if(loot.temple && bot.count(LegacyItems.usableTool("stone_shovel"))==0) {
                child=new AcquisitionTask(LegacyItems.usableTool("stone_shovel"),1,"get");return false;
            }
            if(loot.temple && bot.count(LegacyItems.usableTool("stone_sword"))==0) {
                child=new AcquisitionTask(LegacyItems.usableTool("stone_sword"),1,"get");return false;
            }
            if(loot.temple && !initialLogs) {
                initialLogs=true;child=new AcquisitionTask(LegacyItems.anyLogs(),10,"get");return false;
            }
            List<SurfaceStructureScan.Loot> group=new ArrayList<>();
            for(SurfaceStructureScan.Loot candidate:route)
                if(!visitedChests.contains(candidate.pos) && (candidate==loot || loot.temple && candidate.temple && loot.entry.equals(candidate.entry))) {
                    group.add(candidate);visitedChests.add(candidate.pos);
                }
            child=new StructureLootTask(group);lootIndex++;return false;
        }
        if(!pickaxeChosen) {
            pickaxeChosen=true;
            int iron=inventoryIron(bot);
            if(templeEntry!=null && iron>=6 && iron<9 && bot.count(LegacyItems.parse("iron_pickaxe"))==0) {
                kitPickaxe="golden_pickaxe";
                bot.say("Temple supplied only "+iron+" iron-equivalent: reserving three iron for the bucket, two for shears and one for flint and steel; using a gold pickaxe.");
            }
        }
        if(templeOnly && templeEntry!=null && !tntStarted && bot.count(LegacyItems.parse("tnt"))<9) {
            tntStarted=true;child=new TempleTntTask(templeEntry);return false;
        }
        if(templeOnly && templeEntry!=null && !woolStarted && bot.count(LegacyItems.parse("wool"))<18) {
            if(templeEntry==null) {
                // Recovery near an already looted temple: locate its surviving
                // high wall decorations rather than revisiting empty chests.
                net.minecraft.util.math.BlockPos wool=bot.nearestBlock(s->s.getBlock()==net.minecraft.block.Block.get("wool"),64,
                        java.util.Collections.emptySet(),p->p.y>=66);
                if(wool==null) throw new TaskFailure("Return to the temple to finish collecting 18 wool.");
                templeEntry=new net.minecraft.util.math.BlockPos(wool.x,72,wool.z);
            }
            woolStarted=true;child=new TempleWoolTask(templeEntry);return false;
        }
        if(craftIndex==1 && !stonePlanned) {
            stonePlanned=true;
            int stone=remainingStone(bot);
            if(stone>0) {child=new AcquisitionTask(LegacyItems.parse("cobblestone"),stone,"get");return false;}
        }
        if(craftIndex<KIT.length) {
            String id=KIT[craftIndex++];
            if(id.equals("iron_pickaxe")) id=kitPickaxe;
            // Do not start a gold-ore dependency: that would craft an iron pick
            // and consume the iron reserved for the bucket/shears/igniter.
            if(id.equals("golden_pickaxe") && bot.count(LegacyItems.parse(id))==0
                    && bot.count(LegacyItems.parse("gold_ingot"))<3) {
                bot.say("Not enough looted gold for a gold pickaxe; keeping the stone pickaxe and reserving the six iron.");return false;
            }
            int amount=id.equals("bucket") ? Math.max(0,1-bot.count(LegacyItems.parse("water_bucket"))-bot.count(LegacyItems.parse("lava_bucket"))) : 1;
            if(amount>0) {
                AcquisitionTask craft=new AcquisitionTask(LegacyItems.parse(id),amount,"get");
                craft.protectedCobblestone=remainingStone(bot);child=craft;
            }
            return false;
        }
        if(bot.count(LegacyItems.anyLogs())<10) {child=new AcquisitionTask(LegacyItems.anyLogs(),10,"get");return false;}
        if(bot.count(LegacyItems.food())<7) {child=new LegacyFoodTask(7);return false;}
        bot.say("Structure kit complete: "+(bot.count(LegacyItems.parse(kitPickaxe))>0 ? kitPickaxe : "stone_pickaxe")+", stone tools, one bucket, flint and steel, shears, ten logs and seven food.");
        if(enterNether) {stage=3;child=LegacyOverworldTask.afterPreparation();return false;}
        return true;
    }
}
