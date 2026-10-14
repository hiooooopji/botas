package adris.altoclef.legacy;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ChestScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/** Travel, dig a safe approach and transfer loot using ordinary survival interactions. */
final class StructureLootTask implements LegacyTask {
    private SurfaceStructureScan.Loot loot;
    private final java.util.List<SurfaceStructureScan.Loot> chests;
    private final boolean collectBones;
    private int chestIndex;
    private LegacyTask child;
    private int phase,nextClick,travelExits;
    StructureLootTask(java.util.List<SurfaceStructureScan.Loot> chests) {this(chests,false);}
    StructureLootTask(java.util.List<SurfaceStructureScan.Loot> chests,boolean collectBones) {
        this.chests=new java.util.ArrayList<>(chests);this.loot=chests.get(0);this.collectBones=collectBones;
    }
    public String describe() {return "loot "+(loot.temple ? "desert temple" : "village blacksmith")+" at "+loot.pos+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.cancelMining();bot.releaseMovement();bot.closeOwnedScreen();}
    public boolean tick(LegacyBot bot) {
        if(child!=null) {
            // The roof is a digging approach marker, not a walking destination.
            // Let the chest access planner take over once the whole local route
            // fits within its search bounds.
            if(child instanceof MovementTasks.GoTo && loot.temple
                    && Math.abs(bot.navigation.feet().x-loot.entry.getX())<=18
                    && Math.abs(bot.navigation.feet().z-loot.entry.getZ())<=18) {
                child.stop(bot);child=null;bot.navigation.reset();return false;
            }
            try {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}}
            catch(TaskFailure blocked) {
                if(child instanceof MovementTasks.GoTo) {
                    RiverCrossingTask crossing=RiverCrossingTask.plan(bot,loot.entry);
                    if(crossing!=null) {
                        child.stop(bot);bot.navigation.reset();child=crossing;phase=0;
                        bot.say("Crossing surface water toward the structure.");return false;
                    }
                }
                if(child instanceof MovementTasks.GoTo && travelExits<3 && SurfaceAccessTask.underground(bot)) {
                    child.stop(bot);bot.navigation.reset();travelExits++;
                    // Leave the actual pit/chamber, without using a distant
                    // structure's roof height as the ascent target.
                    child=new SurfaceAccessTask();phase=0;
                } else throw blocked;
            }
            return false;
        }
        if(phase==0) {
            if(travelExits<3 && SurfaceAccessTask.underground(bot)) {
                travelExits++;child=new SurfaceAccessTask();return false;
            }
            phase++;child=new MovementTasks.GoTo(loot.entry.getX(),null,loot.entry.getZ());return false;
        }
        if(phase==1) {
            if(!bot.mc.world.isLoaded(loot.pos,false)) return false;
            phase++;WorkstationTasks.Open open=new WorkstationTasks.Open("chest",ChestScreenHandler.class,null);
            open.pos=loot.pos;open.fixedPosition=true;child=open;return false;
        }
        if(phase==2) {
            if(!(bot.mc.player.openScreenHandler instanceof ChestScreenHandler)) {phase=1;return false;}
            if(bot.ticks<nextClick) return false;
            nextClick=bot.ticks+3;
            ScreenHandler h=bot.mc.player.openScreenHandler;
            for(int slot=0;slot<h.slots.size();slot++) {
                if(h.getSlot(slot).inventory==bot.mc.player.inventory) continue;
                ItemStack s=h.getSlot(slot).getStack();if(s==null) continue;
                String id=LegacyItems.id(s.getItem());
                if(!(id.equals("iron_ingot") || id.equals("iron_pickaxe") || id.equals("bucket") || id.equals("flint_and_steel")
                        || collectBones && (id.equals("bone") || id.equals("rotten_flesh"))
                        || LegacyItems.readyFood(s) || id.equals("wheat") || id.equals("diamond") || id.equals("gold_ingot"))) continue;
                int before=bot.count(LegacyItems.of(s));bot.click(slot,0,1);
                if(bot.count(LegacyItems.of(s))<=before) throw new TaskFailure("Make inventory space for structure loot.");
                return false;
            }
            bot.closeOwnedScreen();
            if(++chestIndex<chests.size()) {loot=chests.get(chestIndex);phase=1;return false;}
            phase++;
        }
        // Stay clear of the temple's pressure plate. Navigation and digging both
        // exclude it and the TNT floor; remove the plate if it is safely in reach.
        if(phase==3 && loot.temple) {
            BlockPos plate=bot.nearestBlock(s -> String.valueOf(Block.REGISTRY.getIdentifier(s.getBlock())).contains("pressure_plate"),4);
            if(plate!=null && bot.hit(plate)!=null) {bot.mine(plate);return false;}
        }
        if(phase==3) {
            phase++;
        }
        return true;
    }
}
