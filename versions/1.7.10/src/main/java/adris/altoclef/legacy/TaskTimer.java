package adris.altoclef.legacy;

/** Monotonic active-task time, excluding explicit pauses and paused menus. */
public final class TaskTimer {
    private long elapsed, since;
    private boolean running;
    public void reset(long now) { elapsed=0; since=now; running=false; }
    public void setRunning(long now, boolean active) {
        if (running) elapsed += Math.max(0, now-since);
        since=now; running=active;
    }
    public long elapsedNanos(long now) { return elapsed + (running ? Math.max(0,now-since) : 0); }
    public String display(long now) {
        long seconds=elapsedNanos(now)/1000000000L;
        return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
    }
}
