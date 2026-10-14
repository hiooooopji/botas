package adris.altoclef.legacy;

/** Acquire a seed item normally, then double and collect it until the requested stock exists. */
final class ResourceDupeTask implements LegacyTask {
    private final LegacyItems.Target target;
    private final int total;
    private final AcquisitionTask pickup;
    private LegacyDupeTask duplicate;
    private int expected, waitingSince;
    private boolean collecting;
    private TasDupeCycle cycle;
    ResourceDupeTask(LegacyItems.Target target,int total) {
        this.target=target;this.total=total;pickup=new AcquisitionTask(target,total,"get");
    }
    public String describe() {return "duplicate and collect " + target + " to x" + total;}
    public boolean tick(LegacyBot bot) {
        if(duplicate!=null) {
            if(duplicate.tick(bot)) {duplicate.stop(bot);duplicate=null;collecting=true;waitingSince=bot.ticks;}
            return false;
        }
        int have=bot.count(target);
        if(collecting) {
            if(have>=expected) {
                if(!cycle.finish(bot,java.util.Collections.singletonMap(target,expected)))return false;
                collecting=false;cycle=null;bot.navigation.reset();
            }
            else {
                if(bot.ticks-waitingSince>240) throw new TaskFailure("Could not collect duplicated "+target+". Copies remain where they were dropped.");
                if(!pickup.pickup(bot)) bot.releaseMovement();
                return false;
            }
        }
        if(have>=total) return true;
        if(have==0) throw new TaskFailure("Need one real "+target+" before resource duplication.");
        bot.closeOwnedScreen();bot.cancelMining();bot.releaseMovement();
        bot.mc.player.pitch=90; // Drop within pickup reach instead of chasing every duplicate forward.
        int amount=Math.min(have,total-have);
        expected=have+amount;
        cycle=new TasDupeCycle(bot);
        duplicate=new LegacyDupeTask(LegacyDupeTask.Mode.DROP,target,amount,null,new LegacyPlayerState());
        duplicate.tick(bot);
        return false;
    }
    public void stop(LegacyBot bot) {if(duplicate!=null) duplicate.stop(bot);pickup.stop(bot);bot.releaseMovement();}
}
