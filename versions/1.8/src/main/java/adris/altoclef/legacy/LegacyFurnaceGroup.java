package adris.altoclef.legacy;

import java.util.*;
import net.minecraft.util.math.BlockPos;

/** Split a long batch so idle collection does not wait on a single furnace. */
final class LegacyFurnaceGroup {
    final List<LegacyFurnaceBatch> batches=new ArrayList<>();
    boolean loaded,finished;
    private LegacyFurnaceGroup() {}
    static LegacyFurnaceGroup food(LegacyBot bot,int needed) {
        LegacyFurnaceGroup group=new LegacyFurnaceGroup();
        List<String> meats=new ArrayList<>(Arrays.asList("beef","porkchop","mutton"));
        meats.sort(Comparator.comparingInt((String id)->bot.count(LegacyItems.parse(id))).reversed());
        for(String id:meats) {
            int amount=Math.min(needed,Math.min(64,bot.count(LegacyItems.parse(id))));
            if(amount>0) {group.batches.add(new LegacyFurnaceBatch("cooked_"+id,amount));needed-=amount;}
        }
        return group;
    }
    int coalNeeded() {int coal=0;for(LegacyFurnaceBatch batch:batches) coal+=(batch.amount+7)/8;return coal;}
    Set<BlockPos> positions() {
        Set<BlockPos> result=new HashSet<>();for(LegacyFurnaceBatch batch:batches) if(batch.pos!=null) result.add(batch.pos);return result;
    }
    LegacyFurnaceBatch pendingMeal() {
        for(LegacyFurnaceBatch batch:batches) if(batch.loaded && !batch.finished) return batch;
        return null;
    }
    static int furnaceCount(int amount) {return SmeltFuelPlan.furnaceCount(amount);}
    static int coalNeeded(int amount) {return SmeltFuelPlan.parallelCoal(amount);}
    LegacyFurnaceGroup(String output,int amount) {
        int n=furnaceCount(amount);
        for(int i=0;i<n;i++) batches.add(new LegacyFurnaceBatch(output,amount/n+(i<amount%n ? 1 : 0)));
    }
    LegacyTask load(Set<BlockPos> reserved) {return new Work(false,reserved);}
    LegacyTask collect() {return new Work(true,Collections.emptySet());}
    private final class Work implements LegacyTask {
        final boolean collecting;
        final Set<BlockPos> reserved;
        int index;
        LegacyTask child;
        Work(boolean collecting,Set<BlockPos> reserved) {this.collecting=collecting;this.reserved=new HashSet<>(reserved);}
        public String describe() {return "parallel furnaces "+Math.min(index+1,batches.size())+"/"+batches.size()+(child==null ? "" : " -> "+child.describe());}
        public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);}
        public boolean tick(LegacyBot bot) {
            if(index==batches.size()) {if(collecting) finished=true;else loaded=true;return true;}
            LegacyFurnaceBatch batch=batches.get(index);
            if(collecting && batch.finished) {index++;return false;}
            if(child==null) child=collecting ? batch.collect() : batch.load(reserved);
            if(child.tick(bot)) {
                child.stop(bot);child=null;
                if(batch.pos!=null) reserved.add(batch.pos);
                index++;bot.navigation.reset();
            }
            return false;
        }
    }
}
