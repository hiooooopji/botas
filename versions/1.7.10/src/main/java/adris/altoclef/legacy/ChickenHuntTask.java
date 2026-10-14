package adris.altoclef.legacy;

/** Get four feathers, keeping the chicken drops, before ordinary meat hunting. */
final class ChickenHuntTask implements LegacyTask {
    private final AcquisitionTask feathers=new AcquisitionTask(LegacyItems.parse("feather"),4,"get");
    private final AcquisitionTask chicken=new AcquisitionTask(LegacyItems.parse("chicken"),Integer.MAX_VALUE,"get");
    private LegacyFurnaceGroup mealBatch;
    private LegacyTask meal;
    public String describe() {return "collect 4 feathers"+(meal==null ? " -> "+feathers.describe() : " -> cook a meal -> "+meal.describe());}
    public void stop(LegacyBot bot) {feathers.stop(bot);chicken.stop(bot);if(meal!=null) meal.stop(bot);bot.releaseMovement();bot.cancelMining();}
    public boolean tick(LegacyBot bot) {
        if(meal!=null) {if(meal.tick(bot)) {meal.stop(bot);meal=null;bot.navigation.reset();}return false;}
        if(mealBatch!=null) {
            if(!mealBatch.finished) {meal=mealBatch.collect();return false;}
            mealBatch=null;
        }
        int hunger=bot.mc.field_3805.getHungerManager().getFoodLevel();
        if((hunger<=10 || hunger<18 && bot.mc.field_3805.getHealth()<16)
                && bot.count(LegacyItems.food())==0 && bot.count(LegacyItems.parse("chicken"))>0) {
            bot.cancelMining();bot.releaseMovement();mealBatch=LegacyFurnaceGroup.food(bot,0);
            meal=mealBatch.load(java.util.Collections.emptySet());return false;
        }
        // Keep meat from each kill even though feathers are the hunting target.
        if(feathers.canYieldToDrop() && chicken.pickup(bot)) return false;
        return feathers.tick(bot);
    }
}
