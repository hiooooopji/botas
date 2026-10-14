package adris.altoclef.legacy;

/** Active wall time for ordinary tasks, or completed simulation ticks for TAS tasks. */
public final class TaskTimer {
    private long elapsed, since;
    private boolean running;
    private boolean simulation;
    private long simulationTicks;
    public void reset(long now) { elapsed=0; since=now; running=false; simulation=false;simulationTicks=0; }
    /** TAS time comes from acknowledged game ticks, not render frames or wall-clock waits. */
    public void simulationTicks(long ticks) {simulation=true;simulationTicks=Math.max(0,ticks);running=false;}
    public void setRunning(long now, boolean active) {
        if(simulation) return;
        if (running) elapsed += Math.max(0, now-since);
        since=now; running=active;
    }
    public long elapsedNanos(long now) { return simulation ? simulationTicks*50000000L : elapsed + (running ? Math.max(0,now-since) : 0); }
    public String display(long now) {
        long seconds=elapsedNanos(now)/1000000000L;
        String time=String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
        return simulation ? time+String.format(java.util.Locale.ROOT,".%03d | %d ticks",simulationTicks%20*50,simulationTicks) : time;
    }
}
