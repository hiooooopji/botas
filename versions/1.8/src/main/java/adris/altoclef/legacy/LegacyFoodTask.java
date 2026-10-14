package adris.altoclef.legacy;

import java.util.Collections;

/** Combines ready food, crop recipes and cooked meat toward one food total. */
final class LegacyFoodTask implements LegacyTask {
    private final int target;
    private final boolean gatherOnly;
    private LegacyTask child;
    private LegacyFurnaceGroup batch;
    LegacyFoodTask(int target) {this(target,false);}
    LegacyFoodTask(int target,boolean gatherOnly) {this.target=target;this.gatherOnly=gatherOnly;}
    public String describe() {return "get food x"+target+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);}
    public boolean tick(LegacyBot bot) {
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(batch!=null && !batch.finished) {child=batch.collect();return false;}
        int needed=target-bot.count(LegacyItems.food());
        if(needed<=0) return true;
        int wheat=bot.count(LegacyItems.parse("wheat"));
        if(wheat>=3) {
            child=new AcquisitionTask(LegacyItems.parse("bread"),bot.count(LegacyItems.parse("bread"))+Math.min(needed,wheat/3),"craft");return false;
        }
        if(bot.count(LegacyItems.meat(false))<needed) {
            net.minecraft.util.math.BlockPos crops=bot.nearestBlock(state -> {
                String id=String.valueOf(net.minecraft.block.Block.REGISTRY.getIdentifier(state.getBlock()));
                return (id.equals("minecraft:carrots") || id.equals("minecraft:wheat")) && state.getBlock().getData(state)>=7;
            },24);
            if(crops!=null) {
                String id=String.valueOf(net.minecraft.block.Block.REGISTRY.getIdentifier(bot.mc.world.getBlockState(crops).getBlock()));
                // Harvest one nearby mature plant, then reconsider rather than
                // commit to searching for a whole missing field.
                String item=id.endsWith("carrots") ? "carrot" : "wheat";
                child=new AcquisitionTask(LegacyItems.parse(item),bot.count(LegacyItems.parse(item))+1,"get");return false;
            }
            child=new AcquisitionTask(LegacyItems.meat(false),needed,"get");return false;
        }
        if(gatherOnly) return true;
        batch=LegacyFurnaceGroup.food(bot,needed);child=batch.load(Collections.emptySet());return false;
    }
}
