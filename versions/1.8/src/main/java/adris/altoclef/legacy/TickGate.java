package adris.altoclef.legacy;

/** Pure scheduling model: one client tick, its ordered packet fence, then one server tick. */
public final class TickGate {
    private boolean paused, runningServer;
    private double rate=20;
    private int requested;
    private long issued, clientFinished, acknowledged, serverFinished, nextTick;
    public synchronized void rate(double value) {
        if(!Double.isFinite(value) || value<.25 || value>20) throw new IllegalArgumentException("Tick rate must be 0.25..20.");
        rate=value;paused=false;requested=0;nextTick=0;
    }
    public synchronized void pause() {paused=true;requested=0;}
    public synchronized void step(int count) {
        if(count<1 || count>1200 || requested+count>1200) throw new IllegalArgumentException("Step count must be 1..1200.");
        paused=true;requested+=count;
    }
    public synchronized long beginClient(long now) {
        if(issued!=serverFinished || runningServer || paused && requested==0 || !paused && now<nextTick) return 0;
        if(paused) requested--;
        nextTick=now+(long)(1_000_000_000L/rate);
        return ++issued;
    }
    public synchronized void finishClient(long sequence) {if(sequence==issued) clientFinished=sequence;}
    public synchronized void acknowledge(long sequence) {
        if(sequence==issued && sequence>serverFinished) acknowledged=sequence;
    }
    public synchronized boolean beginServer() {
        if(runningServer || clientFinished<=serverFinished || acknowledged!=clientFinished) return false;
        runningServer=true;return true;
    }
    public synchronized void finishServer() {
        if(runningServer) {serverFinished=clientFinished;runningServer=false;}
    }
    public synchronized boolean idle() {return issued==serverFinished && !runningServer && requested==0;}
    public synchronized boolean paused() {return paused;}
    public synchronized double rate() {return rate;}
    public synchronized long completed() {return serverFinished;}
}
