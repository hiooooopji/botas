package adris.altoclef.legacy;

/** Plans one ordinary task update while frozen, then permits exactly one paired simulation tick. */
final class TasTask implements LegacyTask {
    final LegacyTask child;
    private boolean started;
    private long firstTick, startedAt;
    long simulatedTicks, elapsedNanos;
    boolean complete;
    boolean enteringEnd() {return child instanceof SeedChestTradeTask && ((SeedChestTradeTask)child).enteringEnd()
            || child instanceof TasEndEntryTask && ((TasEndEntryTask)child).entering
            || child instanceof TasCreditsRunTask && ((TasCreditsRunTask)child).enteringEnd();}
    private SimulatedCreditsWarpTask warp() {return child instanceof SimulatedCreditsWarpTask?(SimulatedCreditsWarpTask)child
            :child instanceof TasCreditsRunTask?((TasCreditsRunTask)child).warp:null;}
    boolean handlesDeath() {return warp()!=null && warp().handlesDeath();}
    boolean allowsWorld(int dimension) {return warp()!=null?warp().allowsWorld(dimension):enteringEnd() && dimension==1;}
    boolean transitioning() {return enteringEnd() || warp()!=null;}
    boolean allowsScreen(LegacyBot bot) {return warp()!=null && warp().allowsScreen(bot);}
    TasTask(LegacyTask child) {this.child=child;}
    boolean ready(LegacyBot bot) {
        if(!started) {
            LegacyTickControl.beginPlanner(bot);
            firstTick=LegacyTickControl.clock().completed();startedAt=System.nanoTime();started=true;
            LegacyTasRecorder.begin(bot,firstTick);
        }
        updateTimer(bot);
        return LegacyTickControl.plannerReady();
    }
    private void updateTimer(LegacyBot bot) {
        simulatedTicks=LegacyTickControl.clock().completed()-firstTick;
        bot.timer.simulationTicks(simulatedTicks);
    }
    void advance() {LegacyTickControl.clock().step(1);}
    public boolean tick(LegacyBot bot) {
        updateTimer(bot);
        if(!child.tick(bot)) return false;
        complete=true;elapsedNanos=System.nanoTime()-startedAt;
        bot.say(String.format(java.util.Locale.ROOT,"TAS finished: %d simulation ticks (%.1f game seconds), %.1f seconds elapsed.",
                simulatedTicks,simulatedTicks/20.0,elapsedNanos/1e9));
        return true;
    }
    public String describe() {return "TAS -> "+child.describe();}
    public void stop(LegacyBot bot) {
        if(started) updateTimer(bot);
        child.stop(bot);bot.releaseMovement();
        LegacyTasRecorder.finish(complete);
        if(started) LegacyTickControl.endPlanner();
    }
}
