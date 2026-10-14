package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

/** A remembered survival furnace job; the server cooks while other tasks run. */
final class LegacyFurnaceBatch {
    final LegacyItems.Target output,input;
    int amount;
    BlockPos pos;
    int readyAt,collected;
    boolean loaded,finished;
    LegacyFurnaceBatch(String output,int amount) {
        this.output=LegacyItems.parse(output);input=LegacyItems.smeltingInput(this.output);this.amount=amount;
        if(input==null || amount<1 || amount>64) throw new TaskFailure("Invalid furnace batch");
    }
    LegacyTask load(Set<BlockPos> reserved) {return new Work(false,reserved,0);}
    LegacyTask collect() {return new Work(true,Collections.emptySet(),amount);}
    LegacyTask collectSome(int count) {return new Work(true,Collections.emptySet(),Math.min(amount,collected+count));}
    private final class Work implements LegacyTask {
        final boolean collecting;
        final int collectGoal;
        final Set<BlockPos> excluded;
        final AcquisitionTask parent=new AcquisitionTask(output,amount,"get");
        LegacyTask child;
        WorkstationTasks.Open opening;
        WorkstationTasks.Transfer transfer;
        boolean inputLoaded,opened;
        int nextTick,stalled,lastCollected=-1;
        Work(boolean collecting,Set<BlockPos> reserved,int goal) {this.collecting=collecting;collectGoal=goal;excluded=new HashSet<>(reserved);}
        public String describe() {return (collecting ? "collect " : "queue ")+output+" x"+amount
                +(child==null ? "" : " -> "+child.describe());}
        public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);bot.releaseMovement();}
        public boolean tick(LegacyBot bot) {
            if(child!=null) {
                if(child.tick(bot)) {
                    child.stop(bot);child=null;bot.navigation.reset();
                    if(opening!=null) {pos=opening.pos;opening=null;opened=true;nextTick=bot.ticks+4;}
                }
                return false;
            }
            if(bot.ticks<nextTick) return false;
            nextTick=bot.ticks+2;
            if(pos!=null && !bot.mc.world.isLoaded(pos,false)) {
                bot.navigation.move(new PathSearch.Pos(pos.getX(),bot.navigation.feet().y,pos.getZ()),.5);return false;
            }
            if(collecting && pos!=null) {
                String id=String.valueOf(Block.REGISTRY.getIdentifier(bot.mc.world.getBlockState(pos).getBlock()));
                if(!id.equals("minecraft:furnace") && !id.equals("minecraft:lit_furnace"))
                    throw new TaskFailure("Queued furnace was removed at "+pos);
            }
            if(!opened || !(bot.mc.player.openScreenHandler instanceof FurnaceScreenHandler)) {
                bot.closeOwnedScreen();opening=new WorkstationTasks.Open("furnace",FurnaceScreenHandler.class,parent);
                opening.rejectedPlaces.addAll(excluded);opening.pos=pos;opening.fixedPosition=collecting;child=opening;return false;
            }
            ScreenHandler h=bot.mc.player.openScreenHandler;
            if(transfer!=null) {if(transfer.tick(bot)) transfer=null;return false;}
            if(!collecting && !inputLoaded && (h.getSlot(0).getStack()!=null || h.getSlot(2).getStack()!=null)) {
                // Never mix a new batch with another player's unfinished recipe.
                excluded.add(pos);pos=null;opened=false;bot.closeOwnedScreen();return false;
            }
            if(collecting) {
                if(collected>=collectGoal) {finished=collected>=amount;bot.closeOwnedScreen();return true;}
                ItemStack result=h.getSlot(2).getStack();
                if(result!=null) {
                    if(!output.matches(result)) throw new TaskFailure("Queued furnace output changed");
                    int before=bot.count(output);bot.click(2,0,1);
                    int received=bot.count(output)-before;
                    if(received<=0) throw new TaskFailure("Make inventory space for furnace output");
                    collected+=received;return false;
                }
                if(lastCollected==collected) stalled+=2;else {lastCollected=collected;stalled=0;}
                if(stalled>1200) throw new TaskFailure("Queued furnace stopped making progress at "+pos);
                return false;
            }
            if(!inputLoaded) {
                int loadAmount=amount-collected;
                if(loadAmount<=0) {loaded=true;finished=true;bot.closeOwnedScreen();return true;}
                int available=bot.count(input);
                if(available<loadAmount && LegacyItems.readyFood(output.stack())) {
                    // Survival eating can consume raw food between planning and
                    // loading. Cook what remains; the final food task tops up.
                    amount=collected+available;loadAmount=available;
                    if(loadAmount==0) {loaded=true;finished=true;bot.closeOwnedScreen();return true;}
                }
                if(available<loadAmount) throw new TaskFailure("Missing input for queued "+output);
                inputLoaded=true;transfer=new WorkstationTasks.Transfer(input,0,loadAmount);return false;
            }
            Inventory furnace=h.getSlot(0).inventory;
            ItemStack in=h.getSlot(0).getStack(),stored=h.getSlot(1).getStack();
            int remaining=in==null ? 0 : in.count;
            int burning=furnace.getProperty(0),progress=furnace.getProperty(2);
            int storedTicks=stored==null ? 0 : stored.count*FurnaceBlockEntity.getBurnTime(stored);
            if(SmeltFuelPlan.required(remaining,progress,burning,storedTicks,1)>0) {
                LegacyItems.Target fuel=null;int needed=0;
                for(String name:new String[]{"coal","planks","stick"}) {
                    for(ItemStack stack:bot.mc.player.inventory.main) {
                        if(stack==null || !LegacyItems.id(stack.getItem()).equals(name)) continue;
                        LegacyItems.Target candidate=LegacyItems.of(stack);
                        if(stored!=null && (!candidate.matches(stored) || !Objects.equals(stored.getNbt(),stack.getNbt()))) continue;
                        if(candidate.item==input.item) continue;
                        int n=SmeltFuelPlan.required(remaining,progress,burning,storedTicks,FurnaceBlockEntity.getBurnTime(stack));
                        if(bot.count(candidate)>=n) {fuel=candidate;needed=n;break;}
                    }
                    if(fuel!=null) break;
                }
                if(fuel==null) {
                    if(stored!=null) {bot.click(1,0,1);return false;}
                    bot.closeOwnedScreen();opened=false;
                    // Planks provide four times the fuel of a raw log. Gather wood
                    // on the surface instead of starting a separate coal mine.
                    int planks=SmeltFuelPlan.required(remaining,progress,burning,storedTicks,300);
                    child=parent.obtain(LegacyItems.parse("planks"),Math.max(1,planks));
                    return false;
                }
                transfer=new WorkstationTasks.Transfer(fuel,1,needed);return false;
            }
            loaded=true;readyAt=bot.ticks+Math.max(0,remaining*200-progress)+10;
            bot.say("Queued "+output+" x"+amount+" at "+pos+" with fuel for the batch; continuing other work.");
            bot.closeOwnedScreen();return true;
        }
    }

}
