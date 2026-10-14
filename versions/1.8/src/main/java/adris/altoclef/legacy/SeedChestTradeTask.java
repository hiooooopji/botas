package adris.altoclef.legacy;

import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;

/** Natural chest, batched rollback, cleric trades, gold tools and Survival stronghold travel. */
final class SeedChestTradeTask implements LegacyTask {
    static final String[] ITEMS={"gold_ingot"};
    private SurfaceStructureScan scan;
    private Future<Boolean> scanning;
    private Future<BlockPos> selected;
    private LegacyTask child;
    private int phase,item,emeraldBefore;
    private final Set<BlockPos> visited=new HashSet<>();
    BlockPos chest;
    boolean stacksReady,complete;
    int goldSpent;
    private int goldBefore;
    private RouteLogTask logTask;
    private int earlyToolsStage;
    int villagePearls;
    boolean bedReady,combatPearlCollected;
    private TasPearlTravelTask villageTravel;
    private LegacyItems.Target wood;
    private BatchDropDupeTask initialBatch;
    private Future<ClericApproach> assistFuture;
    private volatile ClericApproach assist;
    private IntegratedServer assistServer;
    private volatile boolean cancelled;
    private int toolIndex;
    private VillagerTradeTask goldTrade;
    private UUID merchant;
    private int redstoneAfter;
    boolean emeraldStackReady,redstoneStackReady,eyesStackReady,toolsReady;
    private BatchDropDupeTask eyeBatch;
    int logsMined,batchTypes;
    volatile double clericTravelled,clericStartDistance=Double.NaN;
    SurvivalStrongholdTask stronghold;
    TasEndEntryTask endEntry;
    boolean enteringEnd() {return endEntry!=null && endEntry.entering;}
    private static final String[] TOOLS={"golden_pickaxe","golden_axe","golden_shovel"};
    private static final int[] TOOL_COUNTS={2,2,1};
    public String describe() {
        String[] phases={"scan natural chests for gold","collect one route log","loot original items",
                "batch drop-duplicate gold and wood; cleric walks here","find cleric","trade gold for emerald",
                "duplicate emerald stack","buy redstone","duplicate redstone and refill supplies","unlock and buy Eye of Ender",
                "duplicate Eye of Ender stack","craft gold tools and a bed","travel to stronghold","prepare bed respawn and enter End","arrived in End; stopped"};
        return phases[Math.min(phase,phases.length-1)]+(child==null?"":" -> "+child.describe());
    }
    public boolean tick(LegacyBot bot) {
        if(child!=null) {
            if(child.tick(bot)) {
                child.stop(bot);child=null;bot.navigation.reset();
                if(endEntry!=null && endEntry.entered) {phase=14;complete=true;return true;}
            }
            return false;
        }
        if(phase==0) {
            IntegratedServer server=bot.mc.getServer();LegacyPlayerState.requirePrivate(server);
            if(bot.mc.player.dimension!=0) throw new TaskFailure("Start @tasloot in the Overworld.");
            bot.releaseMovement();
            if(scan==null) {scan=new SurfaceStructureScan(server.getWorld(0).getSpawnPos());bot.say("Scanning natural structure chests within 512 blocks of spawn.");}
            try {
                if(selected!=null) {
                    if(!selected.isDone()) return false;
                    chest=selected.get();
                    if(chest==null) throw new TaskFailure("No natural chest containing gold was found within 512 blocks of spawn.");
                    bot.say("TAS_LOOT_CHEST: "+chest+" contains original gold.");
                    logTask=new RouteLogTask(chest);child=logTask;phase=1;return false;
                }
                if(scanning==null) {scanning=server.method_10815(()->scan.advance(server.getWorld(0)));return false;}
                if(!scanning.isDone()) return false;
                boolean done=scanning.get();scanning=null;
                if(done) selected=server.method_10815(()-> {
                    for(SurfaceStructureScan.Loot loot:scan.chests) {
                        if(loot.temple) continue;
                        Object be=server.getWorld(0).getBlockEntity(loot.pos);
                        if(!(be instanceof ChestBlockEntity)) continue;
                        ChestBlockEntity box=(ChestBlockEntity)be;int[] counts=new int[ITEMS.length];
                        for(int slot=0;slot<box.getInvSize();slot++) {
                            ItemStack s=box.getInvStack(slot);
                            for(int i=0;i<ITEMS.length;i++) if(LegacyItems.parse(ITEMS[i]).matches(s)) counts[i]+=s.count;
                        }
                        org.apache.logging.log4j.LogManager.getLogger("Botas/Legacy").info("TAS_CHEST_SCAN {} gold={}",loot.pos,counts[0]);
                        if(counts[0]>0) return loot.pos;
                    }
                    return null;
                });
                return false;
            } catch(TaskFailure e) {throw e;} catch(Exception e) {throw new TaskFailure("Chest scan failed: "+e);}
        }
        if(phase==1) {
            logsMined=logTask.mined;
            int slot=bot.findInventory(LegacyItems.anyLogs());
            if(slot<0) throw new TaskFailure("The single route log was not collected.");
            wood=LegacyItems.of(bot.mc.player.inventory.main[slot]);
            // Keep one original log for the later drop batch while making a real melee weapon.
            if(earlyToolsStage==0) {earlyToolsStage++;child=new ResourceDupeTask(wood,3);return false;}
            if(earlyToolsStage==1) {earlyToolsStage++;child=new AcquisitionTask(LegacyItems.parse("wooden_sword"),1,"craft");return false;}
            bot.say("TAS_ROUTE_LOG: mined "+logsMined+" log; keeping it for the drop batch.");
            phase=2;item=0;villageTravel=new TasPearlTravelTask(chest,6);child=villageTravel;return false;
        }
        if(phase==2) {
            villagePearls=villageTravel.landings;
            combatPearlCollected=villageTravel.collectedPearl;
            if(item<ITEMS.length) {child=new InventoryTasks.Withdraw(LegacyItems.parse(ITEMS[item++]),64,chest);return false;}
            for(String id:ITEMS) if(bot.count(LegacyItems.parse(id))==0) throw new TaskFailure("Missing original chest loot: "+id);
            phase=3;
        }
        if(phase==3) {
            if(assistFuture==null) {
                assistServer=bot.mc.getServer();final String name=bot.mc.player.getGameProfile().getName();
                assistFuture=assistServer.method_10815(()-> {if(cancelled)return null;assist=ClericApproach.start(assistServer,assistServer.getPlayerManager().getPlayer(name));return assist;});
                return false;
            }
            if(!assistFuture.isDone()) return false;
            try {assistFuture.get();}catch(Exception e){throw new TaskFailure("Cleric approach failed: "+e);}
            if(initialBatch==null) {
                Map<LegacyItems.Target,Integer> goals=BatchDropDupeTask.stacks(ITEMS);goals.put(wood,64);
                initialBatch=new BatchDropDupeTask(goals);child=initialBatch;
                if(assist!=null) bot.say("The cleric is walking toward the player while wood and gold are duplicated together.");
                return false;
            }
            for(String id:ITEMS) if(bot.count(LegacyItems.parse(id))<64) throw new TaskFailure("Missing duplicated stack: "+id);
            if(bot.count(wood)<64) throw new TaskFailure("Missing duplicated log stack.");
            batchTypes=initialBatch.maxBatchTypes;
            stacksReady=true;bot.say("TAS_STACKS_READY: 64 gold ingots, 64 logs. Finding a cleric.");phase=4;
        }
        if(phase==4) {
            for(Object entity:bot.mc.world.loadedEntities) if(entity instanceof VillagerEntity) {
                VillagerEntity v=(VillagerEntity)entity;
                if(v.isAlive() && !v.isBaby() && v.profession()==2) {
                    // 1.8 mob spawn packets share numeric IDs, not server-side UUIDs.
                    if(assist!=null && v.getEntityId()!=assist.cleric.getEntityId()) continue;
                    emeraldBefore=bot.count(LegacyItems.parse("emerald"));goldBefore=bot.count(LegacyItems.parse("gold_ingot"));
                    // No trade has happened yet: if this cleric is trapped in a
                    // house, the initial trade may choose another reachable
                    // cleric. Pin the merchant only after gold actually trades.
                    goldTrade=new VillagerTradeTask(LegacyItems.parse("emerald"),emeraldBefore+1,null,false,Collections.singleton("gold_ingot"));child=goldTrade;
                    phase=5;return false;
                }
            }
            // Visit generated villages instead of wandering randomly for a merchant.
            SurfaceStructureScan.Site nearest=null;double best=Double.POSITIVE_INFINITY;
            for(SurfaceStructureScan.Site s:scan.sites) if(!s.temple && !visited.contains(s.center)) {
                double d=Math.pow(s.center.getX()-bot.mc.player.x,2)+Math.pow(s.center.getZ()-bot.mc.player.z,2);
                if(d<best) {best=d;nearest=s;}
            }
            if(nearest==null) throw new TaskFailure("Both stacks are ready, but no adult cleric was found in the scanned villages.");
            visited.add(nearest.center);
            child=new TasPearlTravelTask(nearest.center,12);return false;
        }
        if(phase==5) {
            if(bot.count(LegacyItems.parse("emerald"))<=emeraldBefore) throw new TaskFailure("Gold trade did not produce an emerald.");
            goldSpent=goldBefore-bot.count(LegacyItems.parse("gold_ingot"));
            if(goldSpent<=0) throw new TaskFailure("Emerald was not paid for with gold.");
            phase=6;bot.say("TAS_TRADE_COMPLETE: spent "+goldSpent+" gold for an emerald.");
            releaseCleric();
            merchant=goldTrade.merchantUuid();redstoneAfter=bot.ticks+45;
            child=new BatchDropDupeTask(BatchDropDupeTask.stacks("emerald"));return false;
        }
        if(phase==6) {
            if(bot.count(LegacyItems.parse("emerald"))<64) throw new TaskFailure("Emerald stack not ready.");
            if(bot.ticks<redstoneAfter) {bot.releaseMovement();return false;}
            emeraldStackReady=true;phase=7;
            child=new VillagerTradeTask(LegacyItems.parse("redstone"),1,null,false,Collections.singleton("emerald"),merchant);return false;
        }
        if(phase==7) {
            if(bot.count(LegacyItems.parse("redstone"))<1) throw new TaskFailure("Cleric did not provide redstone.");
            redstoneAfter=bot.ticks+45;
            phase=8;child=new BatchDropDupeTask(BatchDropDupeTask.stacks("emerald","redstone","gold_ingot"));return false;
        }
        if(phase==8) {
            if(bot.count(LegacyItems.parse("redstone"))<64 || bot.count(LegacyItems.parse("emerald"))<64) throw new TaskFailure("Trade-item stacks not ready.");
            redstoneStackReady=true;phase=9;
            bot.say("TAS_TRADE_STACKS: 64 emeralds and 64 redstone ready. Buying an Eye of Ender from the same cleric.");
        }
        if(phase==9) {
            if(bot.ticks<redstoneAfter) {bot.releaseMovement();return false;}
            child=new VillagerTradeTask(LegacyItems.parse("ender_eye"),1,null,false,Collections.singleton("emerald"),merchant);phase=10;return false;
        }
        if(phase==10) {
            if(eyeBatch==null) {
                if(bot.count(LegacyItems.parse("ender_eye"))<1)throw new TaskFailure("Cleric did not provide an Eye of Ender.");
                eyeBatch=new BatchDropDupeTask(BatchDropDupeTask.stacks("ender_eye","emerald"));child=eyeBatch;return false;
            }
            if(bot.count(LegacyItems.parse("ender_eye"))<64 || bot.count(LegacyItems.parse("emerald"))<64)throw new TaskFailure("Eye of Ender stack not ready.");
            eyesStackReady=true;releaseCleric();phase=11;bot.say("TAS_EYES_READY: 64 Eyes of Ender. Crafting gold tools.");
        }
        if(phase==11) {
            if(toolIndex<TOOLS.length) {
                int i=toolIndex++;child=new AcquisitionTask(LegacyItems.parse(TOOLS[i]),TOOL_COUNTS[i],"craft");return false;
            }
            for(int i=0;i<TOOLS.length;i++) if(bot.count(LegacyItems.parse(TOOLS[i]))<TOOL_COUNTS[i]) throw new TaskFailure("Missing crafted "+TOOLS[i]);
            if(bot.count(LegacyItems.parse("bed"))==0) {
                child=new TasBedTask();return false;
            }
            bedReady=true;bot.say("TAS_BED_READY: carrying one bed for the stronghold trip.");
            toolsReady=true;stronghold=new SurvivalStrongholdTask();child=stronghold;phase=12;
            bot.say("TAS_GOLD_KIT: two golden pickaxes, two golden axes, one golden shovel and a bed. Heading to the stronghold.");return false;
        }
        if(phase==12) {
            if(!stronghold.arrived) throw new TaskFailure("Stronghold entry was not confirmed.");
            combatPearlCollected|=stronghold.combatPearlCollected;
            endEntry=new TasEndEntryTask(stronghold.portalRoom());child=endEntry;phase=13;return false;
        }
        if(phase==13) {
            if(!endEntry.entered)throw new TaskFailure("End arrival was not confirmed.");
            phase=14;complete=true;return true;
        }
        return complete;
    }
    private void releaseCleric() {
        if(assistServer!=null) assistServer.submit(()-> {if(assist!=null) {clericTravelled=assist.travelled;clericStartDistance=assist.startingDistance;assist.close();}});
    }
    public void stop(LegacyBot bot) {
        cancelled=true;releaseCleric();
        if(child!=null) child.stop(bot);
        if(scanning!=null && !scanning.isDone()) scanning.cancel(false);
        if(selected!=null && !selected.isDone()) selected.cancel(false);
        bot.releaseMovement();bot.closeOwnedScreen();
    }
}
