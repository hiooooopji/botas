package adris.altoclef.legacy;

/** Keep a single TAS clock running from supplies/portal preparation through the rehearsal. */
final class TasCreditsRunTask implements LegacyTask {
    private final LegacyTask preparation;
    SimulatedCreditsWarpTask warp;
    TasCreditsRunTask(LegacyTask preparation) {this.preparation=preparation;}
    boolean enteringEnd() {
        return preparation instanceof SeedChestTradeTask && ((SeedChestTradeTask)preparation).enteringEnd()
                || preparation instanceof TasEndEntryTask && ((TasEndEntryTask)preparation).entering;
    }
    public boolean tick(LegacyBot bot) {
        if(warp!=null)return warp.tick(bot);
        if(!preparation.tick(bot))return false;
        preparation.stop(bot);bot.navigation.reset();
        warp=new SimulatedCreditsWarpTask();
        return false;
    }
    public String describe() {return warp==null?preparation.describe():warp.describe();}
    public void stop(LegacyBot bot) {if(warp!=null)warp.stop(bot);else preparation.stop(bot);}
}
