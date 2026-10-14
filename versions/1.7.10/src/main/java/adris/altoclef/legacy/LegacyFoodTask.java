package adris.altoclef.legacy;

import java.util.Collections;

/** Tops up usable food with ordinary meat, then cooks the harvest. */
final class LegacyFoodTask implements LegacyTask {
    private final int target;
    private final boolean gatherOnly;
    private LegacyTask child;
    private LegacyFurnaceGroup batch;
    private boolean cropsChecked;
    LegacyFoodTask(int target) {this(target,false);}
    LegacyFoodTask(int target,boolean gatherOnly) {this.target=target;this.gatherOnly=gatherOnly;}
    public String describe() {return "get food x"+target+(child==null ? "" : " -> "+child.describe());}
    public void stop(LegacyBot bot) {if(child!=null) child.stop(bot);}
    public boolean tick(LegacyBot bot) {
        // Let an emergency meal restore health before resuming the hunt.
        // If hunger is too low to heal, continue obtaining/cooking food.
        if(bot.mc.field_3805.getHealth()<16 && bot.mc.field_3805.getHungerManager().getFoodLevel()>=18) {
            bot.releaseMovement();bot.cancelMining();return false;
        }
        if(child!=null) {if(child.tick(bot)) {child.stop(bot);child=null;bot.navigation.reset();}return false;}
        if(batch!=null && !batch.finished) {child=batch.collect();return false;}
        int needed=target-bot.count(LegacyItems.food());
        if(needed<=0) return true;
        if(!cropsChecked) {
            cropsChecked=true;
            net.minecraft.util.math.BlockPos carrots=bot.nearestBlock(state -> state.getBlock()==net.minecraft.block.Block.get("carrots") && state.getData()>=7,24);
            if(carrots!=null && bot.count(LegacyItems.parse("carrot"))<20) {child=new CarrotDetourTask(carrots);return false;}
            net.minecraft.util.math.BlockPos wheat=bot.nearestBlock(state -> state.getBlock()==net.minecraft.block.Block.get("wheat") && state.getData()>=7,24);
            if(wheat!=null) {child=new AcquisitionTask(LegacyItems.parse("bread"),bot.count(LegacyItems.parse("bread"))+needed,"get");return false;}
        }
        if(bot.count(LegacyItems.meat(false))<needed) {
            child=new AcquisitionTask(LegacyItems.meat(false),needed,"get");return false;
        }
        if(gatherOnly) return true;
        batch=LegacyFurnaceGroup.food(bot,needed);child=batch.load(Collections.emptySet());return false;
    }
}
