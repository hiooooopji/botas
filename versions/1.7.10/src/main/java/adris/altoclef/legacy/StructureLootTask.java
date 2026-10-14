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
    private int chestIndex;
    private LegacyTask child;
    private int phase,nextClick,travelExits,plateTicks;
    StructureLootTask(java.util.List<SurfaceStructureScan.Loot> chests) {this.chests=new java.util.ArrayList<>(chests);this.loot=chests.get(0);}
    public String describe() {return "loot "+(loot.temple ? "desert temple" : "village blacksmith")+" at "+loot.pos+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.cancelMining();bot.releaseMovement();bot.closeOwnedScreen();}
    public boolean tick(LegacyBot bot) {
        if(child!=null) {
            // The roof is a digging approach marker, not a walking destination.
            // Let the chest access planner take over once the whole local route
            // fits within its search bounds.
            if(child instanceof MovementTasks.GoTo && loot.temple
                    && Math.abs(bot.navigation.feet().x-loot.entry.x)<=6
                    && Math.abs(bot.navigation.feet().z-loot.entry.z)<=6) {
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
            // Temple entry is a roof marker, sometimes a partial slab column.
            // Travel toward its known elevation; hand off within six blocks
            // above instead of requiring a full standing cell on that column.
            phase++;child=new MovementTasks.GoTo(loot.entry.x,null,loot.entry.z);return false;
        }
        if(phase==1) {
            if(!LegacyWorld.isLoaded(bot.mc.world,loot.pos,false)) return false;
            phase++;WorkstationTasks.Open open=new WorkstationTasks.Open("chest",ChestScreenHandler.class,null);
            open.pos=loot.pos;open.fixedPosition=true;child=open;return false;
        }
        if(phase==2) {
            if(!(bot.mc.field_3805.openScreenHandler instanceof ChestScreenHandler)) {phase=1;return false;}
            if(bot.ticks<nextClick) return false;
            nextClick=bot.ticks+3;
            ScreenHandler h=bot.mc.field_3805.openScreenHandler;
            for(int slot=0;slot<h.slots.size();slot++) {
                if(h.getSlot(slot).inventory==bot.mc.field_3805.inventory) continue;
                ItemStack s=h.getSlot(slot).getStack();if(s==null) continue;
                String id=LegacyItems.id(s.getItem());
                if(!(id.equals("iron_ingot") || id.equals("iron_pickaxe") || id.equals("bucket") || id.equals("flint_and_steel")
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
            BlockPos plate=bot.nearestBlock(s -> String.valueOf(Block.REGISTRY.getId(s.getBlock())).contains("pressure_plate"),4);
            if(plate!=null && bot.hit(plate)!=null && plateTicks++<60) {bot.mine(plate);return false;}
            bot.cancelMining();
        }
        if(phase==3) {
            phase++;
        }
        return true;
    }
}
