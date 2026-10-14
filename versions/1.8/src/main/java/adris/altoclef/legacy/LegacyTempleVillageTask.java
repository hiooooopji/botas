package adris.altoclef.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

/** Stone tool, nearest generated temple TNT, sugar cane, then village crop trades. */
final class LegacyTempleVillageTask implements LegacyTask {
    private SurfaceStructureScan scan;
    private SurfaceStructureScan.Site temple,village;
    private Future<Boolean> pending;
    private LegacyTask child;
    private int stage,ticks;
    private boolean stopped;
    private final boolean templeOnly;
    private final boolean caneOnly;
    private boolean optionalTemple;
    private boolean exitStarted,templePhaseComplete;
    LegacyTempleVillageTask() {this(false,false);}
    LegacyTempleVillageTask(boolean templeOnly) {this(templeOnly,false);}
    private LegacyTempleVillageTask(boolean templeOnly,boolean caneOnly) {
        this.templeOnly=templeOnly;this.caneOnly=caneOnly;
        if(caneOnly) {stage=5;templePhaseComplete=true;}
    }
    static LegacyTempleVillageTask sugarCanePhase() {return new LegacyTempleVillageTask(false,true);}
    static LegacyTempleVillageTask nearbyTemple() {
        LegacyTempleVillageTask result=new LegacyTempleVillageTask(true);result.optionalTemple=true;return result;
    }
    boolean templePhaseComplete() {return templePhaseComplete;}
    public boolean canTickDuringServerLag() {return child!=null && child.canTickDuringServerLag();}
    public String describe() {
        if(templeOnly && templePhaseComplete) return "phase 1 complete: temple looted and safely outside";
        if(caneOnly && stage==6) return "phase 2 complete: collected 30 sugar cane";
        String[] stages={"get stone pickaxe","scan surface structures","travel to nearest desert temple",
                "loot temple chests","mine temple TNT","get sugar cane x30","travel to village",
                "enter village","craft bone meal from all bones","grow carrots and potatoes",
                "trade crops for emeralds","complete"};
        String label=stage<=5 && !templePhaseComplete ? "phase 1: loot temple and get out"
                : stage==5 ? "phase 2: collect 30 sugar cane" : "temple-village route";
        return label+" -> "+(child==null ? stage==5 && !templePhaseComplete ? "leave temple" : stages[Math.min(stage,stages.length-1)] : child.describe());
    }
    public void stop(LegacyBot bot) {
        stopped=true;if(pending!=null) pending.cancel(false);
        if(child!=null) child.stop(bot);
        bot.cancelMining();bot.releaseMovement();
    }
    private List<SurfaceStructureScan.Loot> templeChests(LegacyBot bot) {
        List<SurfaceStructureScan.Loot> found=new ArrayList<>();
        int cx=temple.center.getX(),cz=temple.center.getZ();
        for(int x=cx-3;x<=cx+3;x++) for(int z=cz-3;z<=cz+3;z++)
            for(int y=Math.max(1,temple.bounds[1]-24);y<=Math.min(254,temple.bounds[4]);y++) {
                BlockPos p=new BlockPos(x,y,z);
                if(!bot.mc.world.isLoaded(p,false) || bot.mc.world.getBlockState(p).getBlock()!=Block.get("chest")) continue;
                found.add(new SurfaceStructureScan.Loot(p,temple.entry,true,0,0,0,0,0));
            }
        found.sort(Comparator.comparingDouble(l->bot.mc.player.squaredDistanceTo(l.pos.getX()+.5,l.pos.getY()+.5,l.pos.getZ()+.5)));
        return found;
    }
    public boolean tick(LegacyBot bot) {
        if(bot.mc.player.dimension!=0 || bot.mc.getServer()==null) throw new TaskFailure("The temple-village route requires a single-player Overworld.");
        if(++ticks>72000) throw new TaskFailure("Temple-village route timed out.");
        if(LegacyOverworldTask.defend(bot)) return false;
        if(bot.mc.player.getHungerManager().getFoodLevel()<18 || bot.mc.player.isUsingItem()) {
            int food=bot.findInventory(LegacyItems.food());
            // Temple loot and uncooked meat can keep a long trip alive when
            // prepared food has run out. Prefer raw meat before rotten flesh.
            if(food<0 && bot.mc.player.getHungerManager().getFoodLevel()<=6) {
                for(String item:new String[]{"beef","porkchop","mutton","rotten_flesh"}) {
                    food=bot.findInventory(LegacyItems.parse(item));
                    if(food>=0) break;
                }
            }
            if(food>=0) {
                bot.releaseMovement();bot.cancelMining();bot.equip(food);
                if(!bot.mc.player.isUsingItem()) bot.mc.interactionManager.interactItem(bot.mc.player,bot.mc.world,bot.mc.player.inventory.getMainHandStack());
                bot.key(bot.mc.options.useKey,true);return false;
            }
        }
        bot.key(bot.mc.options.useKey,false);
        if(child!=null) {
            if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}
            return false;
        }
        if(stage==0) {
            if(bot.count(LegacyItems.usableTool("stone_pickaxe"))>0) {stage=1;return false;}
            child=new AcquisitionTask(LegacyItems.parse("stone_pickaxe"),1,"get");return false;
        }
        if(stage==1) {
            if(scan==null) {
                scan=new SurfaceStructureScan(bot.mc.getServer().getWorld(0).getSpawnPos(),templeOnly);
                bot.say("Scanning surface structures within 512 blocks on seed "+bot.mc.getServer().getWorld(0).getSeed()+".");
            }
            if(pending==null) {
                pending=bot.mc.getServer().method_10815((java.util.concurrent.Callable<Boolean>)() -> !stopped && scan.advance(bot.mc.getServer().getWorld(0)));
                return false;
            }
            if(!pending.isDone()) return false;
            boolean complete;
            try {complete=pending.get();}catch(Exception e) {throw new TaskFailure("Structure scan failed: "+e.getMessage());}
            pending=null;if(!complete) return false;
            for(SurfaceStructureScan.Site site:scan.sites) if(site.temple && temple==null) temple=site;
            if(temple!=null) {
                for(SurfaceStructureScan.Site site:scan.sites) if(!site.temple &&
                        (village==null || site.center.getSquaredDistance(temple.center)<village.center.getSquaredDistance(temple.center))) village=site;
            } else for(SurfaceStructureScan.Site site:scan.sites) if(!site.temple && village==null) village=site;
            if(templeOnly && temple==null){
                if(optionalTemple){templePhaseComplete=true;bot.say("No desert temple within 512 blocks; continuing the library expedition.");return true;}
                throw new TaskFailure("No generated desert temple found within 512 blocks of spawn.");
            }
            if(!templeOnly && village==null) throw new TaskFailure("No generated village found within 512 blocks of spawn.");
            bot.say("Nearest desert temple: "+(temple==null ? "none within 512 blocks" : temple.center)
                    +(templeOnly ? "." : "; village: "+village.center+"."));
            stage=temple==null || bot.count(LegacyItems.parse("tnt"))>=9 ? 5 : 2;return false;
        }
        if(stage==2) {
            child=new StructureTravelTask(temple.entry,12);stage=3;return false;
        }
        if(stage==3) {
            PathSearch.Pos feet=bot.navigation.feet();
            long dx=feet.x-temple.center.getX(),dz=feet.z-temple.center.getZ();
            if(bot.count(LegacyItems.parse("bone"))>0 || dx*dx+dz*dz<=64
                    && (feet.y<=temple.entry.getY()-10 || scan.chests.isEmpty())) {
                bot.say("Temple chest loot is already carried or exhausted; continuing with its remaining TNT.");
                stage=4;return false;
            }
            List<SurfaceStructureScan.Loot> chests=templeChests(bot);
            bot.say("Nearest temple has "+chests.size()+" generated chests in its center chamber.");
            if(!chests.isEmpty()) child=new StructureLootTask(chests,true);
            stage=4;return false;
        }
        if(stage==4) {child=new TempleTntTask(temple);stage=5;return false;}
        if(stage==5) {
            if(temple!=null && !exitStarted) {exitStarted=true;child=new TempleExitTask(temple);return false;}
            templePhaseComplete=true;
            if(templeOnly) {bot.say("Phase 1 complete: temple looted, 9 TNT collected, safely outside.");return true;}
            if(bot.count(LegacyItems.parse("reeds"))>=30) {
                stage=6;
                if(caneOnly) {bot.say("Phase 2 complete: collected 30 sugar cane.");return true;}
                return false;
            }
            child=new AcquisitionTask(LegacyItems.parse("reeds"),30,"get");return false;
        }
        if(stage==6) {child=new StructureTravelTask(village.entry,16);stage=7;return false;}
        if(stage==7) {child=new StructureTravelTask(village.center,12);stage=8;return false;}
        if(stage==8) {
            int bones=bot.count(LegacyItems.parse("bone"));
            if(bones>0) child=new AcquisitionTask(LegacyItems.parse("dye:15"),bones*3,"craft");
            else bot.say("No bones found in the temple loot; using naturally mature village crops.");
            stage=9;return false;
        }
        if(stage==9) {child=new VillageCropGrowthTask(village);stage=10;return false;}
        if(stage==10) {
            Set<String> crops=new HashSet<>(Arrays.asList("carrot","potato"));
            child=new VillagerTradeTask(LegacyItems.parse("emerald"),3,null,false,crops);
            stage=11;return false;
        }
        bot.say("Temple-village route complete: "+bot.count(LegacyItems.parse("tnt"))+" TNT, "
                +bot.count(LegacyItems.parse("reeds"))+" sugar cane and "
                +bot.count(LegacyItems.parse("emerald"))+" emeralds.");
        return true;
    }
}
